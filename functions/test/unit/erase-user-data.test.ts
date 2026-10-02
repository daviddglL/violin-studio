import { sha256Hex } from "../../src/common/hashing";
import { eraseUserData, ErasureDeps } from "../../src/erasure/erase-user-data";

type Doc = Record<string, unknown>;
const notFound = () => Object.assign(new Error("5 NOT_FOUND"), { code: 5 });

/** Firestore/Auth/Storage falsos en memoria, con registro de operaciones e inyección de fallos. */
function harness() {
  const docs = new Map<string, Doc>();
  const ops: string[] = [];
  const limits: number[] = [];
  const authUsers = new Map<string, Doc>();
  const claimsSet: Doc[] = [];
  const fail = { storage: 0, auth: 0, updateNotFound: false };
  const logs: Array<{ message: string; data: Doc }> = [];
  const sleeps: number[] = [];

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const ref = (path: string): any => ({
    path,
    get: async () => ({ exists: docs.has(path), data: () => docs.get(path) }),
    update: async (d: Doc) => {
      if (fail.updateNotFound || !docs.has(path)) throw notFound();
      docs.set(path, { ...docs.get(path), ...d });
      ops.push("update");
    },
    collection: (n: string) => collection(`${path}/${n}`),
  });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const collection = (name: string): any => ({
    doc: (id: string) => ref(`${name}/${id}`),
    where: (field: string, _op: string, value: unknown) => ({
      limit: (n: number) => ({
        get: async () => {
          limits.push(n);
          const hits = [...docs.entries()]
            .filter(([p, d]) => p.startsWith(`${name}/`) && d[field] === value)
            .slice(0, n)
            .map(([p]) => ({ ref: ref(p) }));
          return { empty: hits.length === 0, docs: hits };
        },
      }),
    }),
  });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const db: any = {
    collection,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    bulkWriter: () => ({ delete: (r: any) => void docs.delete(r.path), flush: async () => undefined, close: async () => undefined }),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    recursiveDelete: async (r: any) => {
      ops.push("recursiveDelete");
      for (const p of [...docs.keys()]) if (p === r.path || p.startsWith(`${r.path}/`)) docs.delete(p);
    },
  };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const auth: any = {
    getUser: async (uid: string) => {
      if (!authUsers.has(uid)) throw Object.assign(new Error("x"), { code: "auth/user-not-found" });
      return { customClaims: authUsers.get(uid) };
    },
    setCustomUserClaims: async (uid: string, c: Doc) => void (claimsSet.push(c), authUsers.set(uid, c)),
    deleteUser: async (uid: string) => {
      ops.push("auth.delete");
      if (fail.auth-- > 0) throw new Error("auth down");
      if (!authUsers.delete(uid)) throw Object.assign(new Error("x"), { code: "auth/user-not-found" });
    },
  };
  const bucket = {
    deleteFiles: async (o: { prefix: string }) => {
      ops.push(`storage:${o.prefix}`);
      if (fail.storage-- > 0) throw new Error("storage down");
    },
  };
  const deps: ErasureDeps = {
    db,
    auth,
    bucket,
    clock: () => new Date("2026-01-01T00:00:00Z"),
    sleep: async (ms) => void sleeps.push(ms),
    log: (message, data) => void logs.push({ message, data }),
  };
  return { docs, ops, limits, authUsers, claimsSet, fail, logs, sleeps, deps };
}

const seed = (h: ReturnType<typeof harness>, uid = "u1") => {
  h.docs.set(`users/${uid}`, { displayName: "Ana Pérez", birthDate: "1990-01-01", consentStatus: "granted", policyVersion: 1, role: "independent" });
  h.docs.set(`users/${uid}/consents/c1`, { type: "terms" });
  h.docs.set("guardianRequests/r1", { uid, guardianEmailHmac: "x" });
  h.docs.set("mail/m1", { uid, to: "ana@example.com" });
  h.docs.set("mail/other", { uid: "otro" });
  h.authUsers.set(uid, { role: "independent", consentOk: true });
};

describe("eraseUserData", () => {
  test("paso 1: marca deletion in_progress y baja consentOk antes de seguir; sin doc se omite", async () => {
    const h = harness();
    seed(h);
    h.fail.storage = 99;
    await expect(eraseUserData(h.deps, "u1", { deleteAuth: true })).rejects.toThrow("storage down");
    expect(h.docs.get("users/u1")?.deletion).toEqual({ state: "in_progress", startedAt: new Date("2026-01-01T00:00:00Z") });
    expect(h.claimsSet.at(-1)).toMatchObject({ consentOk: false });

    const sin = harness();
    await expect(eraseUserData(sin.deps, "nadie", { deleteAuth: false })).resolves.toBeDefined();
    expect(sin.ops).not.toContain("update");
  });

  test("paso 2: borra guardianRequests y mail por uid en lotes de 400 hasta vaciar", async () => {
    const h = harness();
    seed(h);
    for (let i = 0; i < 950; i++) h.docs.set(`mail/b${i}`, { uid: "u1" });
    const res = await eraseUserData(h.deps, "u1", { deleteAuth: false });
    expect([...h.docs.keys()].filter((p) => p.startsWith("mail/"))).toEqual(["mail/other"]);
    expect(h.docs.has("guardianRequests/r1")).toBe(false);
    expect(new Set(h.limits)).toEqual(new Set([400]));
    expect(res.deleted).toMatchObject({ mail: 951, guardianRequests: 1 });
  });

  test("pasos 3 y 4: Storage por prefijo y recursiveDelete de users/{uid} con consents", async () => {
    const h = harness();
    seed(h);
    await eraseUserData(h.deps, "u1", { deleteAuth: false });
    expect(h.ops).toContain("storage:users/u1/");
    expect(h.docs.has("users/u1")).toBe(false);
    expect(h.docs.has("users/u1/consents/c1")).toBe(false);
  });

  test("paso 5: deleteAuth borra Auth al final; user-not-found se ignora; false no lo toca", async () => {
    const h = harness();
    seed(h);
    await eraseUserData(h.deps, "u1", { deleteAuth: true });
    expect(h.authUsers.has("u1")).toBe(false);
    expect(h.ops.at(-1)).toBe("auth.delete");
    expect(h.ops.indexOf("recursiveDelete")).toBeLessThan(h.ops.indexOf("auth.delete"));

    const g = harness();
    await expect(eraseUserData(g.deps, "fantasma", { deleteAuth: true })).resolves.toBeDefined();

    const k = harness();
    seed(k);
    await eraseUserData(k.deps, "u1", { deleteAuth: false });
    expect(k.authUsers.has("u1")).toBe(true);
    expect(k.ops).not.toContain("auth.delete");
  });

  test("reanudable: un fallo de Storage deja Auth y el marcador; el reintento completa", async () => {
    const h = harness();
    seed(h);
    h.fail.storage = 4;
    await expect(eraseUserData(h.deps, "u1", { deleteAuth: true })).rejects.toThrow("storage down");
    expect(h.authUsers.has("u1")).toBe(true);
    expect(h.docs.get("users/u1")).toHaveProperty("deletion.state", "in_progress");
    expect(h.ops).not.toContain("auth.delete");

    await eraseUserData(h.deps, "u1", { deleteAuth: true });
    expect(h.authUsers.has("u1")).toBe(false);
    expect(h.docs.has("users/u1")).toBe(false);
  });

  test("fallo al borrar Auth con datos ya borrados: el reintento converge", async () => {
    const h = harness();
    seed(h);
    h.fail.auth = 4;
    await expect(eraseUserData(h.deps, "u1", { deleteAuth: true })).rejects.toThrow("auth down");
    expect(h.docs.has("users/u1")).toBe(false);
    expect(h.authUsers.has("u1")).toBe(true);
    await eraseUserData(h.deps, "u1", { deleteAuth: true });
    expect(h.authUsers.has("u1")).toBe(false);
  });

  test("backoff 200/800/2000 ms: cuenta de intentos y esperas", async () => {
    const h = harness();
    seed(h);
    h.fail.storage = 99;
    await expect(eraseUserData(h.deps, "u1", { deleteAuth: false })).rejects.toThrow();
    expect(h.sleeps).toEqual([200, 800, 2000]);
    expect(h.ops.filter((o) => o.startsWith("storage:"))).toHaveLength(4);

    const r = harness();
    seed(r);
    r.fail.storage = 2;
    await eraseUserData(r.deps, "u1", { deleteAuth: false });
    expect(r.sleeps).toEqual([200, 800]);
  });

  test("idempotente: segunda ejecución y ejecuciones simultáneas sin error", async () => {
    const h = harness();
    seed(h);
    await eraseUserData(h.deps, "u1", { deleteAuth: true });
    await expect(eraseUserData(h.deps, "u1", { deleteAuth: true })).resolves.toBeDefined();
    expect(h.docs.has("users/u1")).toBe(false);

    const c = harness();
    seed(c);
    await expect(
      Promise.all([eraseUserData(c.deps, "u1", { deleteAuth: true }), eraseUserData(c.deps, "u1", { deleteAuth: true })]),
    ).resolves.toHaveLength(2);
    expect(c.authUsers.has("u1")).toBe(false);
  });

  test("carrera: el doc desaparece entre leer y marcar -> not-found se ignora", async () => {
    const h = harness();
    seed(h);
    h.fail.updateNotFound = true;
    await expect(eraseUserData(h.deps, "u1", { deleteAuth: true })).resolves.toBeDefined();
    expect(h.authUsers.has("u1")).toBe(false);
  });

  test("logs sin PII: solo uidHash truncado, paso y contadores", async () => {
    const h = harness();
    seed(h);
    h.fail.storage = 1;
    await eraseUserData(h.deps, "u1", { deleteAuth: true });
    const texto = JSON.stringify(h.logs);
    for (const pii of ['"u1"', "ana@example.com", "Ana", "Pérez", "1990-01-01", "guardianEmailHmac"]) {
      expect(texto).not.toContain(pii);
    }
    const hash = sha256Hex("u1").slice(0, 12);
    expect(h.logs.length).toBeGreaterThan(2);
    expect(h.logs.every((l) => l.data.uidHash === hash)).toBe(true);
    expect(h.logs.map((l) => l.data.step)).toEqual(expect.arrayContaining(["mark", "collections", "storage", "userDoc", "auth"]));
    expect(h.logs.some((l) => l.message === "erasure.stepFailed")).toBe(true);
  });
});
