import { HttpsError } from "firebase-functions/v2/https";
import { parseRegisterProfileInput, registerProfileHandler, RegisterProfileDeps } from "../../src/profile/register-profile";

// La validación ocurre antes de tocar Firestore/Auth: cualquier acceso a deps prueba que se creó algo.
const explota = () => {
  throw new Error("no debe tocar el backend con una entrada inválida");
};
const deps: RegisterProfileDeps = {
  db: { collection: explota, runTransaction: explota } as never,
  auth: { getUser: explota, setCustomUserClaims: explota } as never,
  clock: () => new Date("2026-09-30T12:00:00Z"),
  guardianFlowEnabled: true,
};

const valido = { birthDate: "1996-05-10", displayName: "Ana", instrument: "violin", locale: "es-ES" };

async function rechazo(data: unknown): Promise<HttpsError> {
  try {
    await registerProfileHandler(deps, "u1", data);
  } catch (e) {
    return e as HttpsError;
  }
  throw new Error("se esperaba un rechazo");
}

describe("fecha de nacimiento inválida (P2)", () => {
  test.each([
    ["futura", "2027-01-01"],
    ["inexistente", "2023-02-30"],
    ["implausible", "1800-01-01"],
    ["mal formada", "10/05/1996"],
    ["tipo erróneo", 19960510],
    ["ausente", undefined],
  ])("%s -> invalid-argument/INVALID_BIRTH_DATE", async (_n, birthDate) => {
    const e = await rechazo({ ...valido, birthDate });
    expect(e.code).toBe("invalid-argument");
    expect(e.details).toMatchObject({ reason: "INVALID_BIRTH_DATE" });
  });
});

describe("campos de perfil inválidos (P3)", () => {
  test.each([
    ["displayName vacío", { displayName: "" }],
    ["displayName de 41 caracteres", { displayName: "a".repeat(41) }],
    ["displayName no texto", { displayName: 5 }],
    ["displayName solo espacios", { displayName: "   " }],
    ["displayName solo salto de línea", { displayName: "\n" }],
    ["displayName con carácter de control", { displayName: "a\u0000b" }],
    ["displayName con espacio de ancho cero", { displayName: "\u200B" }],
    ["displayName de 41 puntos de código", { displayName: "😀".repeat(41) }],
    // Misma lista blanca que firestore.rules: solo el espacio ASCII como separador.
    ["displayName con espacio de no separación", { displayName: "Ana María" }],
    ["displayName con espacio ideográfico", { displayName: "Ana　María" }],
    ["displayName con separador de línea", { displayName: "Ana María" }],
    ["displayName con carácter sin asignar", { displayName: "Ana͸" }],
    ["instrument fuera de lista", { instrument: "guitar" }],
    ["locale inválido", { locale: "espanol" }],
    ["locale con minúscula en región", { locale: "es-es" }],
  ])("%s -> invalid-argument", async (_n, parche) => {
    const e = await rechazo({ ...valido, ...parche });
    expect(e.code).toBe("invalid-argument");
  });

  test("el payload debe ser un objeto", async () => {
    expect((await rechazo("hola")).code).toBe("invalid-argument");
  });
});

describe("displayName normalizado", () => {
  const hoy = new Date("2026-09-30T12:00:00Z");
  test("se recorta y se guarda sin espacios laterales", () => {
    expect(parseRegisterProfileInput({ ...valido, displayName: " Ana " }, hoy).displayName).toBe("Ana");
  });
  test("40 emoji (puntos de código) se aceptan", () => {
    const nombre = "😀".repeat(40);
    expect(parseRegisterProfileInput({ ...valido, displayName: nombre }, hoy).displayName).toBe(nombre);
  });
  test("los espacios internos son válidos", () => {
    expect(parseRegisterProfileInput({ ...valido, displayName: "Ana María" }, hoy).displayName).toBe("Ana María");
  });
});
