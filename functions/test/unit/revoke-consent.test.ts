import { HttpsError } from "firebase-functions/v2/https";
import { revokeConsentCore } from "../../src/consent/revoke-consent";

/** Firestore falso mínimo: registra escrituras (con su payload), aplica el update al doc y sirve un único doc de usuario. */
function fakeDb(initial: Record<string, unknown> | null) {
  let userDoc: Record<string, unknown> | null = initial === null ? null : { ...initial };
  const writes: { op: string; path: string; data?: Record<string, unknown> }[] = [];
  const ref = (path: string): unknown => ({
    path,
    get: async () => ({ exists: path.split("/").length === 2 && userDoc !== null, data: () => userDoc ?? undefined }),
    collection: (n: string) => ({ doc: (id: string) => ref(`${path}/${n}/${id}`) }),
  });
  const tx = {
    get: async (r: { path: string }) => ({
      exists: r.path.split("/").length === 2 && userDoc !== null,
      data: () => userDoc ?? undefined,
    }),
    create: (r: { path: string }, data: Record<string, unknown>) => void writes.push({ op: "create", path: r.path, data }),
    set: (r: { path: string }) => void writes.push({ op: "set", path: r.path }),
    delete: (r: { path: string }) => void writes.push({ op: "delete", path: r.path }),
    update: (r: { path: string }, data: Record<string, unknown>) => {
      writes.push({ op: "update", path: r.path, data });
      userDoc = { ...userDoc, ...data };
    },
  };
  const db = {
    collection: (n: string) => ({ doc: (id: string) => ref(`${n}/${id}`) }),
    runTransaction: async (fn: (t: unknown) => unknown) => fn(tx),
  };
  return { db, writes };
}
/** Auth falso con espías; `claims` es el claim previo del token y las primeras `failTimes` escrituras fallan. */
function fakeAuth(claims: Record<string, unknown> = {}, failTimes = 0) {
  let fails = failTimes;
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const setCustomUserClaims = jest.fn(async (_uid?: string, _claims?: object) => {
    if (fails-- > 0) throw new Error("auth caído");
  });
  return { getUser: async () => ({ customClaims: claims }), setCustomUserClaims };
}
const captura = async (p: Promise<unknown>) => p.then(() => undefined, (e: HttpsError) => e);
const mk = (db: unknown, auth: unknown = fakeAuth(), log: unknown = () => undefined) =>
  ({ db, auth, currentVersion: 3, log }) as never;

describe("revokeConsentCore", () => {
  test("granted -> revoked: consent revocation, época +1 y sin borrados", async () => {
    const { db, writes } = fakeDb({ isMinor: false, consentStatus: "granted", policyVersion: 3, consentEpoch: 2 });
    expect(await revokeConsentCore(mk(db), "u1", "self")).toEqual({ consentStatus: "revoked" });
    const create = writes.find((w) => w.op === "create")!;
    expect(create.path).toBe("users/u1/consents/revocation_v3_self_e2");
    expect(create.data).toMatchObject({ type: "revocation", version: 3, grantedBy: "self" });
    expect(create.data).toHaveProperty("timestamp");
    const update = writes.find((w) => w.op === "update")!;
    expect(update.path).toBe("users/u1");
    expect(update.data).toMatchObject({ consentStatus: "revoked", consentEpoch: 3 });
    expect(writes.some((w) => w.op === "delete" || w.op === "set")).toBe(false);
  });
  test("época ausente cuenta como 0 y by=guardian se registra", async () => {
    const { db, writes } = fakeDb({ isMinor: true, consentStatus: "granted", policyVersion: 1 });
    await revokeConsentCore(mk(db), "u1", "guardian");
    expect(writes.find((w) => w.op === "create")!.path).toBe("users/u1/consents/revocation_v1_guardian_e0");
    expect(writes.find((w) => w.op === "update")!.data).toMatchObject({ consentEpoch: 1 });
  });
  test.each([["pending"], ["revoked"], ["parental_pending"], [undefined]])(
    "estado %p -> failed-precondition/NO_ACTIVE_CONSENT sin escrituras", async (consentStatus) => {
      const { db, writes } = fakeDb({ isMinor: false, consentStatus, policyVersion: 3 });
      expect(await captura(revokeConsentCore(mk(db), "u1", "self"))).toMatchObject({
        code: "failed-precondition", details: { reason: "NO_ACTIVE_CONSENT" },
      });
      expect(writes).toEqual([]);
    });
  test("sin perfil -> NO_PROFILE sin escrituras", async () => {
    const { db, writes } = fakeDb(null);
    expect(await captura(revokeConsentCore(mk(db), "u1", "self"))).toMatchObject({
      code: "failed-precondition", details: { reason: "NO_PROFILE" },
    });
    expect(writes).toEqual([]);
  });
  test("borrado en curso -> NO_PROFILE sin escrituras", async () => {
    const { db, writes } = fakeDb({ consentStatus: "granted", policyVersion: 3, deletion: { state: "in_progress" } });
    expect(await captura(revokeConsentCore(mk(db), "u1", "self"))).toMatchObject({
      code: "failed-precondition", details: { reason: "NO_PROFILE" },
    });
    expect(writes).toEqual([]);
  });
  test("el log es exactamente {by, closedEpoch}: sin uid ni PII", async () => {
    const { db } = fakeDb({ consentStatus: "granted", policyVersion: 3, consentEpoch: 4, email: "a@b.c" });
    const log = jest.fn();
    await revokeConsentCore(mk(db, fakeAuth(), log), "u1", "self");
    expect(log.mock.calls).toEqual([["revokeConsent", { by: "self", closedEpoch: 4 }]]);
  });
  test("tras el commit sincroniza claims con consentOk=false", async () => {
    const { db } = fakeDb({ role: "student", consentStatus: "granted", policyVersion: 3 });
    const a = fakeAuth({ role: "student", consentOk: true });
    await revokeConsentCore(mk(db, a), "u1", "self");
    expect(a.setCustomUserClaims).toHaveBeenCalledWith("u1", { role: "student", consentOk: false });
  });
  test.each([[1.5], [-2], ["3"], [NaN], [null]])(
    "época corrupta %p se trata como 0: cierra e0 y escribe época 1", async (consentEpoch) => {
      const { db, writes } = fakeDb({ consentStatus: "granted", policyVersion: 3, consentEpoch });
      await revokeConsentCore(mk(db), "u1", "self");
      expect(writes.find((w) => w.op === "create")!.path).toBe("users/u1/consents/revocation_v3_self_e0");
      expect(writes.find((w) => w.op === "update")!.data).toMatchObject({ consentEpoch: 1 });
    });
  test.each([[undefined], [2.5], ["3"], [null]])(
    "S4: policyVersion anómala %p revoca igual y registra anomaly", async (policyVersion) => {
      const { db, writes } = fakeDb({ consentStatus: "granted", policyVersion });
      const log = jest.fn();
      await revokeConsentCore(mk(db, fakeAuth(), log), "u1", "self");
      expect(writes.find((w) => w.op === "create")!.path).toBe("users/u1/consents/revocation_v0_self_e0");
      expect(log.mock.calls).toEqual([["revokeConsent", { by: "self", closedEpoch: 0, anomaly: "policyVersion" }]]);
    });
  test("W1: si el sync falla tras el commit, el reintento (NO_ACTIVE_CONSENT) cura el claim sin escribir", async () => {
    const { db, writes } = fakeDb({ consentStatus: "granted", policyVersion: 3 });
    const a = fakeAuth({ role: "independent", consentOk: true }, 1);
    await expect(revokeConsentCore(mk(db, a), "u1", "self")).rejects.toThrow("auth caído");
    const escrituras = writes.length;
    const e = await captura(revokeConsentCore(mk(db, a), "u1", "self"));
    expect(e).toMatchObject({ code: "failed-precondition", details: { reason: "NO_ACTIVE_CONSENT" } });
    expect(writes.length).toBe(escrituras);
    expect(a.setCustomUserClaims).toHaveBeenCalledTimes(2);
    expect(a.setCustomUserClaims).toHaveBeenLastCalledWith("u1", { role: "independent", consentOk: false });
  });
  test("W1: si el sync del reintento también falla se registra {code} sin PII y se lanza NO_ACTIVE_CONSENT", async () => {
    const { db } = fakeDb({ consentStatus: "revoked", policyVersion: 3 });
    const a = fakeAuth({ consentOk: true });
    a.setCustomUserClaims.mockRejectedValue(Object.assign(new Error("a@b.c"), { code: "auth/internal-error" }));
    const log = jest.fn();
    const e = await captura(revokeConsentCore(mk(db, a, log), "u1", "self"));
    expect(e).toMatchObject({ code: "failed-precondition", details: { reason: "NO_ACTIVE_CONSENT" } });
    expect(log.mock.calls).toEqual([["revokeConsent.syncFailed", { code: "auth/internal-error" }]]);
  });
  test("W1: sin perfil o con borrado en curso no se intenta sincronizar claims", async () => {
    for (const d of [null, { consentStatus: "granted", policyVersion: 3, deletion: { state: "in_progress" } }]) {
      const { db } = fakeDb(d);
      const a = fakeAuth({ consentOk: true });
      await captura(revokeConsentCore(mk(db, a), "u1", "self"));
      expect(a.setCustomUserClaims).not.toHaveBeenCalled();
    }
  });
});

describe("W1: precondición de solicitud del tutor (dentro de la transacción)", () => {
  const granted = (requestId: string) => ({ isMinor: true, consentStatus: "granted", policyVersion: 3, guardian: { requestId } });
  test("requestId distinto del activo -> NO_ACTIVE_CONSENT sin escrituras", async () => {
    const { db, writes } = fakeDb(granted("B"));
    const e = await captura(revokeConsentCore(mk(db), "u1", "guardian", { expectGuardianRequestId: "A" }));
    expect(e).toMatchObject({ code: "failed-precondition", details: { reason: "NO_ACTIVE_CONSENT" } });
    expect(writes).toEqual([]);
  });
  test("requestId coincidente revoca; sin opción (self) el comportamiento no cambia", async () => {
    const a = fakeDb(granted("A"));
    expect(await revokeConsentCore(mk(a.db), "u1", "guardian", { expectGuardianRequestId: "A" })).toEqual({ consentStatus: "revoked" });
    const b = fakeDb(granted("B"));
    expect(await revokeConsentCore(mk(b.db), "u1", "self")).toEqual({ consentStatus: "revoked" });
  });
  test("sin guardian.requestId en el doc y con precondición -> rechazo sin escrituras", async () => {
    const { db, writes } = fakeDb({ isMinor: true, consentStatus: "granted", policyVersion: 3 });
    expect(await captura(revokeConsentCore(mk(db), "u1", "guardian", { expectGuardianRequestId: "A" }))).toBeDefined();
    expect(writes).toEqual([]);
  });
});
