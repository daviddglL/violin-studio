import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { registerProfileHandler, RegisterProfileDeps } from "../../src/profile/register-profile";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const HOY = new Date("2026-09-30T12:00:00Z");
const deps = (parche: Partial<RegisterProfileDeps> = {}): RegisterProfileDeps => ({
  db, auth, clock: () => HOY, guardianFlowEnabled: true, ...parche,
});

const payload = (parche: Record<string, unknown> = {}) => ({
  birthDate: "1996-05-10", displayName: "Ana", instrument: "violin", locale: "es-ES", ...parche,
});

async function nuevoUsuario() {
  return auth.createUser({ email: `rp-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
}
const leer = async (uid: string) => (await db.collection("users").doc(uid).get());

test("alta de adulto: rol independent, isMinor=false, consentStatus=pending, policyVersion=null (P3)", async () => {
  const { uid } = await nuevoUsuario();
  const res = await registerProfileHandler(deps(), uid, payload());
  const snap = await leer(uid);
  expect(snap.exists).toBe(true);
  const d = snap.data()!;
  expect(d).toMatchObject({
    role: "independent", isMinor: false, consentStatus: "pending", policyVersion: null,
    birthDate: "1996-05-10", displayName: "Ana", instrument: "violin", locale: "es-ES",
  });
  expect(d.createdAt).toBeInstanceOf(Timestamp);
  expect(d.updatedAt).toBeInstanceOf(Timestamp);
  expect(res).toEqual({ isMinor: false, consentStatus: "pending", requiredPolicyVersion: 1 });
});

test("alta de menor con flujo activo: isMinor=true, pending y sin consentOk", async () => {
  const { uid } = await nuevoUsuario();
  const res = await registerProfileHandler(deps(), uid, payload({ birthDate: "2013-06-01" }));
  expect(res).toMatchObject({ isMinor: true, consentStatus: "pending" });
  expect((await leer(uid)).data()).toMatchObject({ isMinor: true, consentStatus: "pending" });
  expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
});

test("alta de menor con flujo desactivado: UNDERAGE_NOT_ALLOWED y no se crea doc", async () => {
  const { uid } = await nuevoUsuario();
  const e = await registerProfileHandler(deps({ guardianFlowEnabled: false }), uid, payload({ birthDate: "2013-06-01" }))
    .catch((x: HttpsError) => x);
  expect(e).toBeInstanceOf(HttpsError);
  expect((e as HttpsError).code).toBe("failed-precondition");
  expect((e as HttpsError).details).toMatchObject({ reason: "UNDERAGE_NOT_ALLOWED" });
  expect((await leer(uid)).exists).toBe(false);
});

test("idempotencia: la segunda llamada devuelve el perfil existente sin modificar birthDate/role/isMinor", async () => {
  const { uid } = await nuevoUsuario();
  await registerProfileHandler(deps(), uid, payload({ birthDate: "1990-01-01" }));
  const antes = (await leer(uid)).data()!;
  const res = await registerProfileHandler(deps(), uid, payload({ birthDate: "2015-01-01", displayName: "Otro", instrument: "cello" }));
  const despues = (await leer(uid)).data()!;
  expect(res).toEqual({ isMinor: false, consentStatus: "pending", requiredPolicyVersion: 1 });
  expect(despues).toEqual(antes);
});

test("idempotencia: una segunda llamada con flujo desactivado no falla si el perfil ya existe", async () => {
  const { uid } = await nuevoUsuario();
  await registerProfileHandler(deps(), uid, payload({ birthDate: "2013-06-01" }));
  const res = await registerProfileHandler(deps({ guardianFlowEnabled: false }), uid, payload({ birthDate: "2013-06-01" }));
  expect(res).toMatchObject({ isMinor: true });
});

test("un role enviado por el cliente se ignora: role=independent (U2)", async () => {
  const { uid } = await nuevoUsuario();
  await registerProfileHandler(deps(), uid, payload({ role: "teacher", isMinor: false, consentStatus: "granted" }));
  expect((await leer(uid)).data()).toMatchObject({ role: "independent", consentStatus: "pending" });
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
});

test("los logs no contienen birthDate, displayName ni email (P2)", async () => {
  const { uid } = await nuevoUsuario();
  const entradas: unknown[] = [];
  await registerProfileHandler(
    deps({ log: (mensaje, datos) => entradas.push({ mensaje, datos }) }),
    uid,
    payload({ displayName: "Ana Secreta" }),
  );
  const logs = JSON.stringify(entradas);
  expect(entradas.length).toBeGreaterThan(0);
  for (const secreto of ["1996-05-10", "Ana Secreta", "@example.com"]) expect(logs).not.toContain(secreto);
});

test("tras el alta los claims son {role:independent, consentOk:false} (P5)", async () => {
  const { uid } = await nuevoUsuario();
  await registerProfileHandler(deps(), uid, payload());
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
});

test("recuperación: si la sincronización de claims falla tras crear el perfil, el reintento la completa", async () => {
  const { uid } = await nuevoUsuario();
  let fallos = 1;
  const authFlaky = Object.create(auth, {
    setCustomUserClaims: {
      value: (u: string, c: object) => {
        if (fallos-- > 0) return Promise.reject(new Error("fallo transitorio de Auth"));
        return auth.setCustomUserClaims(u, c);
      },
    },
  });
  await expect(registerProfileHandler(deps({ auth: authFlaky }), uid, payload())).rejects.toThrow("fallo transitorio");
  expect((await leer(uid)).exists).toBe(true);
  expect((await auth.getUser(uid)).customClaims ?? {}).toEqual({});

  const res = await registerProfileHandler(deps({ auth: authFlaky }), uid, payload());
  expect(res).toEqual({ isMinor: false, consentStatus: "pending", requiredPolicyVersion: 1 });
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
});

describe("límites de edad a nivel de handler (reloj inyectable)", () => {
  const en = (iso: string) => deps({ clock: () => new Date(iso) });

  test("cumple exactamente 14 hoy -> isMinor=false", async () => {
    const { uid } = await nuevoUsuario();
    const res = await registerProfileHandler(en("2026-09-30T00:00:00Z"), uid, payload({ birthDate: "2012-09-30" }));
    expect(res.isMinor).toBe(false);
    expect((await leer(uid)).data()!.isMinor).toBe(false);
  });

  test("le falta un día para cumplir 14 -> isMinor=true", async () => {
    const { uid } = await nuevoUsuario();
    const res = await registerProfileHandler(en("2026-09-30T23:59:59Z"), uid, payload({ birthDate: "2012-10-01" }));
    expect(res.isMinor).toBe(true);
  });

  test("nacido un 29-feb: el 28-feb del año no bisiesto en que cumple 14 aún es menor", async () => {
    const { uid } = await nuevoUsuario();
    const res = await registerProfileHandler(en("2026-02-28T12:00:00Z"), uid, payload({ birthDate: "2012-02-29" }));
    expect(res.isMinor).toBe(true);
  });

  test("nacido un 29-feb: el 1-mar del año no bisiesto en que cumple 14 ya es adulto", async () => {
    const { uid } = await nuevoUsuario();
    const res = await registerProfileHandler(en("2026-03-01T12:00:00Z"), uid, payload({ birthDate: "2012-02-29" }));
    expect(res.isMinor).toBe(false);
  });
});

test("concurrencia: dos altas paralelas con distinta birthDate crean un único doc y devuelven el mismo perfil", async () => {
  const { uid } = await nuevoUsuario();
  const [a, b] = await Promise.all([
    registerProfileHandler(deps(), uid, payload({ birthDate: "1990-01-01" })),
    registerProfileHandler(deps(), uid, payload({ birthDate: "2015-01-01" })),
  ]);
  expect(a).toEqual(b);
  const d = (await leer(uid)).data()!;
  expect(a.isMinor).toBe(d.isMinor);
  expect(["1990-01-01", "2015-01-01"]).toContain(d.birthDate);
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
});
