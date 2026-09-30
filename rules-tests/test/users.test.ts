import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, deleteField, doc, getDoc, getDocs, setDoc, updateDoc } from "firebase/firestore";
import { createEnv, OK, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
});

describe("users/{uid}: lectura", () => {
  test("el dueño lee su perfil", async () => {
    await seed(env, "users/alice", userDoc());
    await assertSucceeds(getDoc(doc(env.authenticatedContext("alice", OK).firestore(), "users/alice")));
  });

  test("el dueño lee su perfil sin email verificado ni consentimiento (estados de espera)", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "pending", policyVersion: null }));
    const db = env.authenticatedContext("alice", { email_verified: false }).firestore();
    await assertSucceeds(getDoc(doc(db, "users/alice")));
  });

  test("el dueño lee su perfil en parental_pending", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "parental_pending", isMinor: true }));
    await assertSucceeds(getDoc(doc(env.authenticatedContext("alice", OK).firestore(), "users/alice")));
  });

  test("otro usuario no lee el perfil", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(getDoc(doc(env.authenticatedContext("bob", OK).firestore(), "users/alice")));
  });

  test("otro usuario no lee el perfil en parental_pending", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "parental_pending", isMinor: true }));
    await assertFails(getDoc(doc(env.authenticatedContext("bob", OK).firestore(), "users/alice")));
  });

  test("un anónimo no lee el perfil", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "users/alice")));
  });

  test("list sobre users falla para un autenticado", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(getDocs(collection(env.authenticatedContext("alice", OK).firestore(), "users")));
  });

  test("list sobre users falla en parental_pending", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "parental_pending", isMinor: true }));
    await assertFails(getDocs(collection(env.authenticatedContext("alice", OK).firestore(), "users")));
  });
});

// Dueño con token al día: email verificado + consentOk, y doc en granted.
const ownerDb = () => env.authenticatedContext("alice", OK).firestore();
const update = (data: Record<string, unknown>, db = ownerDb()) => updateDoc(doc(db, "users/alice"), data);

describe("users/{uid}: update del dueño", () => {
  beforeEach(async () => {
    await seed(env, "users/alice", userDoc());
  });

  test.each([
    ["displayName", { displayName: "Alicia" }],
    ["instrument", { instrument: "cello" }],
    ["locale", { locale: "en" }],
    ["locale con región", { locale: "en-US" }],
    ["los tres campos a la vez", { displayName: "Ali", instrument: "double_bass", locale: "fr-FR" }],
  ])("permite editar %s", async (_, data) => {
    await assertSucceeds(update(data));
  });

  test.each(["violin", "viola", "cello", "double_bass", "other"])("permite instrumento %s", async (instrument) => {
    await assertSucceeds(update({ instrument }));
  });

  test("permite displayName de 40 caracteres y de 1", async () => {
    await assertSucceeds(update({ displayName: "a".repeat(40) }));
    await assertSucceeds(update({ displayName: "b" }));
  });

  describe("campos protegidos, uno por uno", () => {
    test.each([
      ["role", { role: "student" }],
      ["birthDate", { birthDate: "2015-01-01" }],
      ["isMinor", { isMinor: true }],
      ["consentStatus", { consentStatus: "revoked" }],
      ["policyVersion", { policyVersion: 99 }],
      ["guardian", { guardian: { emailMasked: "p***@g***.com" } }],
      ["deletion", { deletion: { state: "in_progress" } }],
      ["createdAt", { createdAt: new Date() }],
      ["updatedAt", { updatedAt: new Date() }],
    ])("deniega modificar %s", async (_, data) => {
      await assertFails(update(data));
    });

    test.each([
      ["role", { role: "student" }],
      ["birthDate", { birthDate: "2015-01-01" }],
      ["isMinor", { isMinor: true }],
      ["consentStatus", { consentStatus: "revoked" }],
    ])("deniega %s aunque vaya junto a un campo permitido", async (_, data) => {
      await assertFails(update({ displayName: "Alicia", ...data }));
    });

    test("deniega escalar a teacher, solo o con campo permitido", async () => {
      await assertFails(update({ role: "teacher" }));
      await assertFails(update({ role: "teacher", displayName: "Alicia" }));
    });

    test("deniega borrar un campo protegido", async () => {
      await assertFails(update({ birthDate: deleteField() }));
    });

    test.each([
      ["claims", { claims: { consentOk: true } }],
      ["consentOk", { consentOk: true }],
      ["campo desconocido", { foo: "bar" }],
    ])("deniega añadir el campo extra %s", async (_, data) => {
      await assertFails(update(data));
    });
  });

  describe("tipos y valores inválidos", () => {
    test.each([
      ["displayName número", { displayName: 5 }],
      ["displayName null", { displayName: null }],
      ["displayName vacío", { displayName: "" }],
      ["displayName solo espacios", { displayName: "   " }],
      ["displayName de 41 caracteres", { displayName: "a".repeat(41) }],
      ["displayName sin recortar", { displayName: " Alicia " }],
      ["displayName con salto de línea", { displayName: "Ali\ncia" }],
      ["displayName con carácter de control", { displayName: "Ali\u0000cia" }],
      ["displayName con espacio de ancho cero", { displayName: "Ali​cia" }],
      ["instrument fuera del enum", { instrument: "guitar" }],
      ["instrument mayúsculas", { instrument: "Violin" }],
      ["instrument número", { instrument: 3 }],
      ["locale inválido", { locale: "espanol" }],
      ["locale con mayúsculas", { locale: "ES" }],
      ["locale con región en minúsculas", { locale: "es-es" }],
      ["locale con sufijo", { locale: "es-ES-x" }],
      ["locale con salto de línea final", { locale: "es\n" }],
      ["locale número", { locale: 1 }],
    ])("deniega %s", async (_, data) => {
      await assertFails(update(data));
    });
  });

  test("deniega un update sin cambios efectivos que dejaría un doc inválido", async () => {
    await seed(env, "users/alice", userDoc({ instrument: "guitar" }));
    await assertFails(update({ displayName: "Alicia" }));
  });
});

describe("users/{uid}: create, delete y ajenos", () => {
  test("create denegado incluso al dueño", async () => {
    await assertFails(setDoc(doc(ownerDb(), "users/alice"), userDoc()));
  });

  test("create denegado al dueño con un doc mínimo válido", async () => {
    await assertFails(setDoc(doc(ownerDb(), "users/alice"), { displayName: "A", instrument: "violin", locale: "es" }));
  });

  test("create denegado a un anónimo", async () => {
    await assertFails(setDoc(doc(env.unauthenticatedContext().firestore(), "users/alice"), userDoc()));
  });

  test("delete denegado incluso al dueño", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(deleteDoc(doc(ownerDb(), "users/alice")));
  });

  test("set (reemplazo total) que cambia un campo protegido denegado", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(setDoc(doc(ownerDb(), "users/alice"), userDoc({ displayName: "Otro", role: "teacher" })));
  });

  test("update ajeno con campo permitido denegado", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(update({ displayName: "Hackeada" }, env.authenticatedContext("bob", OK).firestore()));
  });

  test("update de un anónimo denegado", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(update({ displayName: "Hackeada" }, env.unauthenticatedContext().firestore()));
  });

  test("update del dueño sobre un doc inexistente denegado", async () => {
    await assertFails(update({ displayName: "Alicia" }));
  });
});

describe("users/{uid}: gating por email y consentimiento", () => {
  beforeEach(async () => {
    await seed(env, "users/alice", userDoc());
  });
  const dbWith = (claims: Record<string, unknown>) => env.authenticatedContext("alice", claims).firestore();

  test.each([
    ["consentOk ausente", { email_verified: true }],
    ["consentOk falso", { email_verified: true, consentOk: false }],
    ["consentOk no booleano", { email_verified: true, consentOk: "true" }],
    ["email_verified falso", { email_verified: false, consentOk: true }],
    ["email_verified ausente", { consentOk: true }],
    ["sin claims", {}],
  ])("deniega con %s", async (_, claims) => {
    await assertFails(update({ displayName: "Alicia" }, dbWith(claims)));
  });

  test.each(["pending", "parental_pending", "revoked"])(
    "deniega con claim consentOk:true pero doc en %s (claim desfasado, R-e)",
    async (consentStatus) => {
      await seed(env, "users/alice", userDoc({ consentStatus }));
      await assertFails(update({ displayName: "Alicia" }));
    },
  );

  test("token no refrescado (consentOk falso) deniega y refrescado permite, con el mismo doc granted", async () => {
    await assertFails(update({ displayName: "Alicia" }, dbWith({ email_verified: true, consentOk: false })));
    await assertSucceeds(update({ displayName: "Alicia" }, dbWith(OK)));
  });

  test("doc en granted pero token antiguo sin consentOk: denegado hasta refrescar", async () => {
    await assertFails(update({ instrument: "viola" }, dbWith({ email_verified: true })));
    await assertSucceeds(update({ instrument: "viola" }, dbWith(OK)));
  });
});

describe("users/{uid}: diferencia documentada con el servidor", () => {
  test("las reglas cuentan unidades UTF-16: 20 emoji (40 unidades) pasan y 21 no; el servidor cuenta puntos de código", async () => {
    await seed(env, "users/alice", userDoc());
    await assertSucceeds(update({ displayName: "🎻".repeat(20) }));
    await assertFails(update({ displayName: "🎻".repeat(21) }));
  });
});
