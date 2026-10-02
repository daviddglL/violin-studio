import { HttpsError } from "firebase-functions/v2/https";
import { revokeConsentCore } from "../../src/consent/revoke-consent";

/** Firestore falso mínimo: registra escrituras (con su payload) y sirve un único doc de usuario. */
function fakeDb(userDoc: Record<string, unknown> | null) {
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
    update: (r: { path: string }, data: Record<string, unknown>) => void writes.push({ op: "update", path: r.path, data }),
  };
  const db = {
    collection: (n: string) => ({ doc: (id: string) => ref(`${n}/${id}`) }),
    runTransaction: async (fn: (t: unknown) => unknown) => fn(tx),
  };
  return { db, writes };
}
const auth = { getUser: async () => ({ customClaims: {} }), setCustomUserClaims: async () => undefined };
const captura = async (p: Promise<unknown>) => p.then(() => undefined, (e: HttpsError) => e);
const mk = (db: unknown) => ({ db, auth, currentVersion: 3, log: () => undefined }) as never;

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
    "estado %p -> failed-precondition sin escrituras", async (consentStatus) => {
      const { db, writes } = fakeDb({ isMinor: false, consentStatus, policyVersion: 3 });
      expect(await captura(revokeConsentCore(mk(db), "u1", "self"))).toMatchObject({ code: "failed-precondition" });
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
  test("el log no lleva PII ni uid", async () => {
    const { db } = fakeDb({ consentStatus: "granted", policyVersion: 3 });
    const log = jest.fn();
    await revokeConsentCore({ ...(mk(db) as object), log } as never, "u1", "self");
    expect(JSON.stringify(log.mock.calls)).not.toContain("u1");
  });
});
