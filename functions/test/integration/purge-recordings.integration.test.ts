import { Timestamp } from "firebase-admin/firestore";
import { readCursor, writeCursor } from "../../src/recordings/cursor";
import { bucketSweepStore, SweepStore } from "../../src/recordings/orphan-sweep";
import { purgeRecordingsHandler } from "../../src/recordings/purge";
import { bucket, db, DAY, exists, idOf, NOW, recDoc, recRef, reviewCount, rnd, seedRecording, seedReview, seedUser, upload, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

const MIN = 60_000;
const store = bucketSweepStore(bucket);
let logs: { message: string; data: Record<string, unknown> }[];
const deps = (over: Record<string, unknown> = {}) => ({ db, store, log: (message: string, data: Record<string, unknown>) => void logs.push({ message, data }), ...over });
const at = (ms: number) => Timestamp.fromMillis(NOW.getTime() + ms);
const count = async (uid: string) => (await userDoc(uid))?.recordingCount;
async function waitFor<T>(fn: () => Promise<T | undefined>, ms = 25000): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v !== undefined) return v;
    if (Date.now() > end) throw new Error("timeout");
    await new Promise((r) => setTimeout(r, 200));
  }
}

/** Grabacion con objeto y doc que caduca `offsetMs` respecto a NOW. */
async function rec(uid: string, offsetMs: number, over: Record<string, unknown> = {}, up: Parameters<typeof upload>[1] = {}) {
  const ev = await upload(uid, up);
  const id = await seedRecording(uid, ev, { expiresAt: at(offsetMs), ...over });
  return { ev, id };
}

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
});

describe("paso 1: grabaciones caducadas", () => {
  test("bordes: -1 min y = now caducan (objeto, doc y reviews); +1 min no", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const before = await rec(uid, -MIN);
    const exact = await rec(uid, 0);
    const after = await rec(uid, +MIN);
    await seedReview(uid, before.id);
    const s = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(s.expired).toMatchObject({ found: 2, deleted: 2, failed: 0 });
    for (const r of [before, exact]) {
      expect(await exists(r.ev.name)).toBe(false);
      expect(await recDoc(uid, r.id)).toBeUndefined();
    }
    expect(await reviewCount(uid, before.id)).toBe(0);
    expect(await exists(after.ev.name)).toBe(true);
    expect(await recDoc(uid, after.id)).toBeDefined();
  });

  test("caducan tambien los docs rejected (+7 d) y los de varios duenos; cada uno por su objectPath (demo en demos/)", async () => {
    const t = rnd("t");
    const s = rnd("s");
    await seedUser(t, { role: "teacher" });
    await seedUser(s);
    const demo = await rec(t, -MIN, { sharedWith: s }, { dir: "demos", id: "same", meta: { sharedWith: s, assignmentId: "a" } });
    const keep = await upload(t, { dir: "recordings", id: "same" });
    const rej = await rec(s, -MIN, { status: "rejected", rejectReason: "quota" });
    await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(await exists(demo.ev.name)).toBe(false);
    expect(await exists(keep.name)).toBe(true);
    expect(await exists(rej.ev.name)).toBe(false);
    expect(await recDoc(s, rej.id)).toBeUndefined();
  });

  test("fallo parcial: el lote continua, la fallida conserva doc y objeto y se cuenta; sin PII en logs; el dia siguiente la completa", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const bad = await rec(uid, -MIN);
    const goods = [await rec(uid, -MIN), await rec(uid, -2 * MIN)];
    const flaky: SweepStore = {
      ...store,
      remove: async (name, gen) => {
        if (name === bad.ev.name) throw Object.assign(new Error(`boom ${name}`), { code: 503 });
        return store.remove(name, gen);
      },
    };
    const s = await purgeRecordingsHandler(deps({ store: flaky, reconcile: false }), NOW);
    expect(s.expired).toMatchObject({ found: 3, deleted: 2, failed: 1 });
    expect(await recDoc(uid, bad.id)).toBeDefined();
    expect(await exists(bad.ev.name)).toBe(true);
    for (const g of goods) expect(await recDoc(uid, g.id)).toBeUndefined();
    const text = JSON.stringify(logs);
    expect(text).toContain("uidHash");
    for (const secret of [uid, bad.id, bad.ev.name]) expect(text).not.toContain(secret);
    const again = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(again.expired).toMatchObject({ found: 1, deleted: 1, failed: 0 });
    expect(await recDoc(uid, bad.id)).toBeUndefined();
  });

  test("repetida sin vencidas: sin efectos (los docs no se reescriben)", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const r = await rec(uid, +DAY);
    const t0 = (await recRef(uid, r.id).get()).updateTime!.toMillis();
    for (let i = 0; i < 2; i++) {
      const s = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
      expect(s.expired).toMatchObject({ found: 0, deleted: 0, failed: 0 });
    }
    expect((await recRef(uid, r.id).get()).updateTime!.toMillis()).toBe(t0);
    expect(await exists(r.ev.name)).toBe(true);
  });

  test("paginado: mas caducadas que el tamano de pagina", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const all = [];
    for (let i = 0; i < 5; i++) all.push(await rec(uid, -MIN * (i + 1)));
    const s = await purgeRecordingsHandler(deps({ pageSize: 2, reconcile: false }), NOW);
    expect(s.expired.deleted).toBe(5);
    for (const r of all) expect(await recDoc(uid, r.id)).toBeUndefined();
  });

  test("presupuesto de tiempo: deja de EMPEZAR borrados y lo pendiente sigue en la siguiente ejecucion", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    for (let i = 0; i < 4; i++) await rec(uid, -MIN * (i + 1));
    let t = 0;
    const s = await purgeRecordingsHandler(deps({ budgetMs: 1500, nowMs: () => (t += 1000), reconcile: false }), NOW);
    expect(s.budgetExhausted).toBe(true);
    expect(s.expired.deleted).toBeGreaterThanOrEqual(1);
    expect(s.expired.deleted).toBeLessThan(4);
    const rest = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(s.expired.deleted + rest.expired.deleted).toBe(4);
  });

  test("carrera: el doc cambia (se renueva) entre la consulta y el borrado -> skipped, no se borra", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const r = await rec(uid, -MIN);
    const s = await purgeRecordingsHandler(
      deps({ reconcile: false, beforeDelete: async () => void (await recRef(uid, r.id).update({ expiresAt: at(+DAY) })) }),
      NOW,
    );
    expect(s.expired).toMatchObject({ found: 1, deleted: 0, skipped: 1 });
    expect(await recDoc(uid, r.id)).toBeDefined();
    expect(await exists(r.ev.name)).toBe(true);
  });

  test("una consulta que falla entera se registra y no impide el paso de reconciliacion", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 9 });
    const broken = new Proxy(db, {
      get: (t, p) => {
        if (p === "collectionGroup") return () => { throw Object.assign(new Error("indice"), { code: 9 }); };
        const v = Reflect.get(t, p) as unknown;
        return typeof v === "function" ? v.bind(t) : v;
      },
    });
    const s = await purgeRecordingsHandler(deps({ db: broken }), NOW);
    expect(s.failedCategories).toEqual(["expired", "reconcile"]); // la consulta de duenos por docs ready tambien usa collectionGroup
    expect(await count(uid)).toBe(0); // pero la pasada por contador ya reparo a este dueno antes de fallar
  });
});

describe("paso 2: revocaciones pendientes (REQ-REC-09, B5b.3)", () => {
  const SINCE = Timestamp.fromMillis(1_700_000_000_000);
  const pending = (uid: string, epoch = 0, over: Record<string, unknown> = {}) =>
    seedUser(uid, { recordingConsent: "revoked", recordingConsentEpoch: epoch + 1, recordingPurge: { pending: true, since: SINCE, epoch }, ...over });
  const recE = (uid: string, epoch: number, over: Record<string, unknown> = {}) =>
    rec(uid, +DAY, { recordingConsentEpoch: epoch, ...over }, { meta: { recordingConsentEpoch: String(epoch) } });
  const isPending = async (uid: string) => (await userDoc(uid))?.recordingPurge?.pending;

  test("completa las revocaciones pendientes (docs, objetos, demos, revisiones, huerfanos) y baja pending; lo no pendiente no se toca", async () => {
    const [a, b, ok] = [rnd("a"), rnd("b"), rnd("c")];
    await pending(a);
    await pending(b, 0, { role: "teacher" });
    await seedUser(ok);
    const ra = await recE(a, 0);
    await seedReview(a, ra.id);
    const demo = await rec(b, +DAY, { sharedWith: "sx", recordingConsentEpoch: 0 }, { dir: "demos", meta: { sharedWith: "sx", assignmentId: "a", recordingConsentEpoch: "0" } });
    const orphan = await upload(a, { dir: "demos", meta: { sharedWith: "s", assignmentId: "a" } });
    const keep = await recE(ok, 0);
    const s = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(s.revocations).toMatchObject({ found: 2, completed: 2, incomplete: 0, failed: 0 });
    expect(await exists(ra.ev.name)).toBe(false);
    expect(await recDoc(a, ra.id)).toBeUndefined();
    expect(await reviewCount(a, ra.id)).toBe(0);
    expect(await exists(demo.ev.name)).toBe(false);
    expect(await recDoc(b, demo.id)).toBeUndefined();
    expect(await exists(orphan.name)).toBe(false);
    expect([await isPending(a), await isPending(b)]).toEqual([false, false]);
    expect(await exists(keep.ev.name)).toBe(true);
    expect(await recDoc(ok, keep.id)).toBeDefined();
    expect(await readCursor(db, "recordingPurgePending")).toBe("");
  });

  test("acotada por epoca (purge.epoch): una grabacion de epoca posterior sobrevive", async () => {
    const u = rnd("u");
    await pending(u, 1, { recordingConsentEpoch: 3 });
    const old = await recE(u, 1);
    const newer = await recE(u, 2);
    await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(await recDoc(u, old.id)).toBeUndefined();
    expect(await recDoc(u, newer.id)).toBeDefined();
    expect(await exists(newer.ev.name)).toBe(true);
    expect(await isPending(u)).toBe(false);
  });

  test("fallo en un usuario: se cuenta, el resto sigue, sin PII en logs; al dia siguiente se completa", async () => {
    const [bad, good] = [rnd("a"), rnd("b")];
    await pending(bad);
    await pending(good);
    const rb = await recE(bad, 0);
    const rg = await recE(good, 0);
    const flaky: SweepStore = {
      ...store,
      remove: async (n, g) => {
        if (n === rb.ev.name) throw Object.assign(new Error(`boom ${n}`), { code: 503 });
        return store.remove(n, g);
      },
    };
    const s = await purgeRecordingsHandler(deps({ store: flaky, reconcile: false }), NOW);
    expect(s.revocations).toMatchObject({ found: 2, completed: 1, incomplete: 1 });
    expect([await isPending(bad), await isPending(good)]).toEqual([true, false]);
    expect(await recDoc(good, rg.id)).toBeUndefined();
    const text = JSON.stringify(logs);
    for (const secret of [bad, good, rb.id, rb.ev.name]) expect(text).not.toContain(secret);
    const again = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(again.revocations).toMatchObject({ found: 1, completed: 1 });
    expect(await isPending(bad)).toBe(false);
    expect(await exists(rb.ev.name)).toBe(false);
  });

  test("usuario en borrado (deletion): no se escribe en el, se cuenta gone y no es un fallo", async () => {
    const u = rnd("u");
    await pending(u, 0, { deletion: { state: "in_progress", startedAt: Timestamp.now() } });
    const s = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(s.revocations).toMatchObject({ found: 1, gone: 1, failed: 0 });
    expect(await isPending(u)).toBe(true);
  });

  test("presupuesto: se deja de empezar usuarios, el cursor guarda donde se corto y la siguiente ejecucion sigue (sin repetir ni saltar)", async () => {
    const users = [rnd("a"), rnd("b"), rnd("c")];
    for (const u of users) {
      await pending(u);
      await recE(u, 0);
    }
    let t = 0;
    const first = await purgeRecordingsHandler(deps({ budgetMs: 1500, nowMs: () => (t += 1000), reconcile: false }), NOW);
    expect(first.budgetExhausted).toBe(true);
    const doneFirst = first.revocations.completed;
    expect(doneFirst).toBeLessThan(3);
    expect(await readCursor(db, "recordingPurgePending")).not.toBe("");
    const second = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(doneFirst + second.revocations.completed).toBe(3);
    for (const u of users) expect(await isPending(u)).toBe(false);
    expect(await readCursor(db, "recordingPurgePending")).toBe("");
  });

  test("con un cursor mas alla de todos los pendientes, la pasada da la vuelta a la cabeza y los procesa", async () => {
    const u = rnd("a");
    await pending(u);
    await recE(u, 0);
    await writeCursor(db, "recordingPurgePending", "zzzzzzzz");
    const s = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(s.revocations.completed).toBe(1);
    expect(await isPending(u)).toBe(false);
    expect(await readCursor(db, "recordingPurgePending")).toBe("");
  });

  test("repetida sin pendientes: sin efectos", async () => {
    const u = rnd("u");
    await seedUser(u, { recordingPurge: { pending: false, since: SINCE, epoch: 0 } });
    const r = await recE(u, 0);
    const s = await purgeRecordingsHandler(deps({ reconcile: false }), NOW);
    expect(s.revocations).toMatchObject({ found: 0, completed: 0, failed: 0 });
    expect(await recDoc(u, r.id)).toBeDefined();
  });

  test("una consulta de pendientes que falla entera se registra y no impide el resto", async () => {
    const broken = new Proxy(db, {
      get: (t, p) => {
        if (p === "collection") {
          return (name: string) => {
            if (name === "users") throw Object.assign(new Error("indice"), { code: 9 });
            return t.collection(name);
          };
        }
        const v = Reflect.get(t, p) as unknown;
        return typeof v === "function" ? v.bind(t) : v;
      },
    });
    const s = await purgeRecordingsHandler(deps({ db: broken, reconcile: false }), NOW);
    expect(s.failedCategories).toContain("revocations");
  });

  test("los duenos cuya purga de revocacion borro docs no se reconcilian ese dia (su trigger baja el contador)", async () => {
    const u = rnd("u");
    await pending(u, 0, { recordingCount: 1 });
    await recE(u, 0);
    const seen: string[] = [];
    await purgeRecordingsHandler(deps({ reconcileOwner: async (_d: unknown, x: string) => (seen.push(x), "ok" as const) }), NOW);
    expect(seen).not.toContain(u);
  });
});

describe("paso 4: reconciliacion del contador", () => {
  test("repara la deriva (contador de mas, de menos y ausente) y no toca lo coherente", async () => {
    const over = rnd("a");
    const missing = rnd("b");
    const fine = rnd("c");
    await seedUser(over, { recordingCount: 8 });
    await seedUser(missing);
    await seedUser(fine, { recordingCount: 1 });
    for (const u of [over, missing, fine]) await rec(u, +DAY);
    const s = await purgeRecordingsHandler(deps(), NOW);
    expect(s.reconcile).toMatchObject({ repaired: 2, failed: 0 });
    expect([await count(over), await count(missing), await count(fine)]).toEqual([1, 1, 1]);
  });

  test("un dueno al que acaba de borrar docs caducados NO se reconcilia ese dia (su trigger baja el contador, sin doble bajada)", async () => {
    const uid = rnd("s");
    const other = rnd("o");
    await seedUser(uid, { recordingCount: 2 });
    await seedUser(other, { recordingCount: 5 });
    await rec(uid, +DAY);
    await rec(uid, -MIN);
    const seen: string[] = [];
    const s = await purgeRecordingsHandler(deps({ reconcileOwner: async (_d: unknown, u: string) => (seen.push(u), "ok" as const) }), NOW);
    expect(s.expired.deleted).toBe(1);
    expect(seen).toContain(other);
    expect(seen).not.toContain(uid);
    await waitFor(async () => ((await count(uid)) === 1 ? true : undefined)); // lo baja el trigger de onRecordingDeleted
    await new Promise((r) => setTimeout(r, 1000));
    expect(await count(uid)).toBe(1);
  });

  test("un fallo de la reconciliacion se registra sin PII y no rompe la purga", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 8 });
    const s = await purgeRecordingsHandler(deps({ reconcileOwner: () => Promise.reject(Object.assign(new Error(`x ${uid}`), { code: 14 })) }), NOW);
    expect(s.reconcile.failed).toBe(1);
    expect(JSON.stringify(logs)).not.toContain(uid);
  });
});

test("no regresion paso 3 (blurRequired al cumplir 18): la purga no toca blurRequired", async () => {
  const uid = rnd("s");
  await seedUser(uid, { blurRequired: true, recordingCount: 0 });
  await purgeRecordingsHandler(deps(), NOW);
  expect((await userDoc(uid))?.blurRequired).toBe(true);
});

test("el id de la grabacion no aparece nunca en los logs", async () => {
  const uid = rnd("s");
  await seedUser(uid);
  const r = await rec(uid, -MIN);
  await purgeRecordingsHandler(deps(), NOW);
  expect(JSON.stringify(logs)).not.toContain(idOf(r.ev.name));
});

describe("W1 de la revision fresca de B5a: cursor de la reconciliacion y aviso de presupuesto", () => {
  const cursorDoc = async () => (await db.collection("maintenance").doc("recordingReconcile").get()).data();

  test("la reconciliacion guarda el cursor en maintenance y la siguiente purga sigue donde lo dejo", async () => {
    const [a, b, c] = [rnd("a"), rnd("b"), rnd("c")];
    for (const u of [a, b, c]) await seedUser(u, { recordingCount: 9 });
    const warns: string[] = [];
    // Reloj falso (+1 s por consulta): 2,5 s dan para empezar la reconciliacion de UN dueno por pasada.
    const run = () => {
      let t = 0;
      return purgeRecordingsHandler(deps({ budgetMs: 2500, nowMs: () => (t += 1000), warn: (m: string) => void warns.push(m) }), NOW);
    };
    const r1 = await run();
    expect(r1.budgetExhausted).toBe(true);
    expect([await count(a), await count(b), await count(c)]).toEqual([0, 9, 9]);
    const c1 = (await cursorDoc())?.cursor as string;
    expect(b.startsWith(c1) && c1.length > 0 && c1.length < b.length).toBe(true);
    await run();
    expect([await count(b), await count(c)]).toEqual([0, 9]);
    await run();
    expect(await count(c)).toBe(0);
    expect((await cursorDoc())?.cursor).toBe("");
    expect(warns).toContain("purgeRecordings.budgetExhausted");
  });
});
