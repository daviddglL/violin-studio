import { HttpsError } from "firebase-functions/v2/https";
import { recordConsentHandler } from "../../src/consent/record-consent";

/** Firestore falso mínimo: registra escrituras y sirve un único doc de usuario. */
function fakeDb(userDoc: Record<string, unknown> | null) {
  const writes: string[] = [];
  const ref = (path: string): unknown => ({
    path,
    collection: (n: string) => ({ doc: (id: string) => ref(`${path}/${n}/${id}`) }),
  });
  const tx = {
    get: async (r: { path: string }) => ({
      exists: r.path.split("/").length === 2 && userDoc !== null,
      data: () => userDoc ?? undefined,
    }),
    getAll: async (...rs: { path: string }[]) => rs.map(() => ({ exists: false, data: () => undefined })),
    create: (r: { path: string }) => void writes.push(`create ${r.path}`),
    set: (r: { path: string }) => void writes.push(`set ${r.path}`),
    update: (r: { path: string }) => void writes.push(`update ${r.path}`),
  };
  const db = {
    collection: (n: string) => ({ doc: (id: string) => ref(`${n}/${id}`) }),
    runTransaction: async (fn: (t: unknown) => unknown) => fn(tx),
  };
  return { db, writes };
}
const auth = { getUser: async () => ({ customClaims: {} }), setCustomUserClaims: async () => undefined };
const deps = (db: unknown, currentVersion = 3) => ({ db, auth, currentVersion }) as never;
const captura = async (p: Promise<unknown>) => p.then(() => undefined, (e: HttpsError) => e);

describe("validación de versión (antes de tocar el backend)", () => {
  const explota = { runTransaction: () => { throw new Error("no debe tocar la base"); }, collection: () => { throw new Error("no"); } };
  test.each([["2.5"], ["3"], [null], [undefined], [true], [NaN], [Infinity]])("no entera/no numérica %p -> invalid-argument", async (v) => {
    const e = await captura(recordConsentHandler(deps(explota), "u1", { policyVersion: v }));
    expect(e).toMatchObject({ code: "invalid-argument", details: { reason: "INVALID_ARGUMENT", field: "policyVersion" } });
  });
  test("payload que no es objeto -> invalid-argument", async () => {
    expect(await captura(recordConsentHandler(deps(explota), "u1", null))).toMatchObject({ code: "invalid-argument" });
  });
  test("versión futura -> invalid-argument", async () => {
    expect(await captura(recordConsentHandler(deps(explota), "u1", { policyVersion: 4 }))).toMatchObject({
      code: "invalid-argument",
    });
  });
  test("versión < 1 -> invalid-argument", async () => {
    expect(await captura(recordConsentHandler(deps(explota), "u1", { policyVersion: 0 }))).toMatchObject({
      code: "invalid-argument",
    });
  });
  test("versión antigua -> failed-precondition/POLICY_OUTDATED con currentVersion", async () => {
    expect(await captura(recordConsentHandler(deps(explota), "u1", { policyVersion: 2 }))).toMatchObject({
      code: "failed-precondition",
      details: { reason: "POLICY_OUTDATED", currentVersion: 3 },
    });
  });
});

describe("precondiciones de perfil", () => {
  test("sin perfil -> failed-precondition/NO_PROFILE sin escrituras", async () => {
    const { db, writes } = fakeDb(null);
    const e = await captura(recordConsentHandler(deps(db), "u1", { policyVersion: 3 }));
    expect(e).toMatchObject({ code: "failed-precondition", details: { reason: "NO_PROFILE" } });
    expect(writes).toEqual([]);
  });
  test("menor -> permission-denied/GUARDIAN_REQUIRED sin escrituras", async () => {
    const { db, writes } = fakeDb({ isMinor: true, consentStatus: "pending", policyVersion: null });
    const e = await captura(recordConsentHandler(deps(db), "u1", { policyVersion: 3 }));
    expect(e).toMatchObject({ code: "permission-denied", details: { reason: "GUARDIAN_REQUIRED" } });
    expect(writes).toEqual([]);
  });
  test("isMinor ausente o no booleano se trata como menor (fail-closed)", async () => {
    for (const isMinor of [undefined, "false", 0, null]) {
      const { db, writes } = fakeDb({ isMinor, consentStatus: "pending" });
      const e = await captura(recordConsentHandler(deps(db), "u1", { policyVersion: 3 }));
      expect(e).toMatchObject({ code: "permission-denied", details: { reason: "GUARDIAN_REQUIRED" } });
      expect(writes).toEqual([]);
    }
  });
  test("cuenta con borrado en curso -> NO_PROFILE sin escrituras", async () => {
    const { db, writes } = fakeDb({ isMinor: false, consentStatus: "pending", deletion: { state: "in_progress" } });
    const e = await captura(recordConsentHandler(deps(db), "u1", { policyVersion: 3 }));
    expect(e).toMatchObject({ code: "failed-precondition", details: { reason: "NO_PROFILE" } });
    expect(writes).toEqual([]);
  });
});
