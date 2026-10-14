import { getStorage } from "firebase-admin/storage";
import { bucketSweepStore, SweepStore, sweepOrphansHandler } from "../../src/recordings/orphan-sweep";
import { finalizeRecordingHandler, FinalizeOutcome } from "../../src/recordings/finalize";
import { audioMp4 } from "../unit/helpers/mp4-builder";
import { db, exists as existsIn, idOf, project, recDoc, rnd, seedRecording, seedUser, upload as uploadTo, wipeFirestore } from "./helpers/finalize-fixtures";
import { getApps } from "firebase-admin/app";

/** Bucket propio: el barrido lista TODO `users/`, asi que no puede compartir bucket con otros tests (objetos ajenos). */
const sweepBucket = getStorage(getApps()[0]).bucket(`${project}-sweep`);
const store = bucketSweepStore(sweepBucket);
const upload = (uid: string, o: Parameters<typeof uploadTo>[1] = {}) => uploadTo(uid, { ...o, bucket: sweepBucket });
const exists = (name: string) => existsIn(name, sweepBucket);
let logs: { message: string; data: Record<string, unknown> }[];
const deps = (over: Record<string, unknown> = {}) => ({ db, store, log: (message: string, data: Record<string, unknown>) => void logs.push({ message, data }), ...over });
const HOUR = 60 * 60 * 1000;

/** Instante "ahora" tal que el objeto tenga `ageMs` de antiguedad (su `timeCreated` real + ageMs). */
const nowFor = async (name: string, ageMs: number) => new Date(new Date(String((await sweepBucket.file(name).getMetadata())[0].timeCreated)).getTime() + ageMs);
/** Hace que TODOS los objetos recien subidos cuenten como viejos de 25 h. */
const later = () => new Date(Date.now() + 25 * HOUR);

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
  await sweepBucket.deleteFiles({ force: true });
});

test("objeto sin doc y > 24 h se borra; con doc ready (objectPath + generation) se conserva", async () => {
  const uid = rnd("s");
  await seedUser(uid);
  const orphan = await upload(uid);
  const kept = await upload(uid);
  await seedRecording(uid, kept);
  const s = await sweepOrphansHandler(deps(), later());
  expect(await exists(orphan.name)).toBe(false);
  expect(await exists(kept.name)).toBe(true);
  expect(s).toMatchObject({ scanned: 2, deleted: 1, kept: 1, failed: 0 });
});

test("bordes de antiguedad: 24 h - 1 min se conserva; 24 h y 24 h + 1 min se borran", async () => {
  const uid = rnd("s");
  const [young, exact, old] = [await upload(uid), await upload(uid), await upload(uid)];
  expect((await sweepOrphansHandler(deps(), await nowFor(young.name, 24 * HOUR - 60_000))).deleted).toBe(0);
  expect(await exists(young.name)).toBe(true);
  expect((await sweepOrphansHandler(deps(), await nowFor(exact.name, 24 * HOUR))).deleted).toBeGreaterThanOrEqual(1);
  expect(await exists(exact.name)).toBe(false);
  await sweepOrphansHandler(deps(), await nowFor(old.name, 24 * HOUR + 60_000));
  expect(await exists(old.name)).toBe(false);
});

test("demos/: sin doc se borra; con doc (objectPath en demos/) se conserva; mismo id en el otro prefijo se juzga por separado", async () => {
  const t = rnd("t");
  const demo = await upload(t, { dir: "demos", id: "same", meta: { sharedWith: "s1", assignmentId: "a1" } });
  const rec = await upload(t, { dir: "recordings", id: "same" });
  await seedRecording(t, demo, { sharedWith: "s1" }); // el doc (users/{t}/recordings/same) apunta a la demo
  await sweepOrphansHandler(deps(), later());
  expect(await exists(demo.name)).toBe(true);
  expect(await exists(rec.name)).toBe(false); // mismo id pero el doc apunta a otra ruta
});

test("doc de otra generation (objeto sobrescrito) o doc rejected: el objeto vivo se borra y el doc no se toca", async () => {
  const uid = rnd("s");
  const over = await upload(uid, { id: "o1" });
  const docOnOld = { name: over.name, generation: "1" };
  const idOver = await seedRecording(uid, docOnOld);
  const rej = await upload(uid, { id: "j1" });
  const idRej = await seedRecording(uid, rej, { status: "rejected", rejectReason: "quota" });
  await sweepOrphansHandler(deps(), later());
  expect(await exists(over.name)).toBe(false);
  expect(await exists(rej.name)).toBe(false);
  expect(await recDoc(uid, idOver)).toMatchObject({ generation: "1" });
  expect(await recDoc(uid, idRej)).toMatchObject({ status: "rejected" });
});

test("rutas invalidas dentro de los prefijos se borran; objetos de fuera de ellos no se tocan", async () => {
  const uid = rnd("s");
  const save = (name: string) => sweepBucket.file(name).save(audioMp4(5), { resumable: false });
  const bad = [`users/${uid}/recordings/.m4a`, `users/${uid}/recordings/a/b.m4a`, `users/${uid}/demos/x.txt`, `users/${uid}/recordings/ok.m4a.m4a`];
  const outside = [`users/${uid}/avatar.png`, `users/${uid}/other/x.m4a`, `public/x.m4a`];
  for (const n of [...bad, ...outside]) await save(n);
  await sweepOrphansHandler(deps(), later());
  for (const n of bad) expect(await exists(n)).toBe(false);
  for (const n of outside) expect(await exists(n)).toBe(true);
});

test("idempotente y paginado: muchos duenos/objetos con paginas de 2; la segunda pasada no borra nada", async () => {
  const names: string[] = [];
  for (let i = 0; i < 4; i++) {
    const uid = rnd(`u${i}`);
    names.push((await upload(uid)).name, (await upload(uid, { dir: "demos", meta: { sharedWith: "x", assignmentId: "a" } })).name);
  }
  const first = await sweepOrphansHandler(deps({ pageSize: 2 }), later());
  expect(first.deleted).toBe(8);
  for (const n of names) expect(await exists(n)).toBe(false);
  expect(await sweepOrphansHandler(deps({ pageSize: 2 }), later())).toMatchObject({ scanned: 0, deleted: 0, failed: 0 });
});

test("fallo parcial: continua con el resto, cuenta el fallo y no filtra uid/ruta en logs; la siguiente pasada lo completa", async () => {
  const uid = rnd("s");
  const [a, b, c] = [await upload(uid), await upload(uid), await upload(uid)];
  const flaky: SweepStore = { ...store, remove: async (n, g) => (n === b.name ? Promise.reject(Object.assign(new Error(`x ${n}`), { code: 503 })) : store.remove(n, g)) };
  const s = await sweepOrphansHandler(deps({ store: flaky }), later());
  expect(s).toMatchObject({ deleted: 2, failed: 1 });
  expect(await exists(b.name)).toBe(true);
  expect(await exists(a.name)).toBe(false);
  expect(await exists(c.name)).toBe(false);
  const text = JSON.stringify(logs);
  expect(text).toContain("uidHash");
  for (const secret of [uid, idOf(b.name), b.name]) expect(text).not.toContain(secret);
  expect((await sweepOrphansHandler(deps(), later())).deleted).toBe(1);
});

test("carrera: el cliente resube el mismo nombre entre el listado y el borrado -> la generation nueva sobrevive", async () => {
  const uid = rnd("s");
  const ev = await upload(uid, { id: "x1" });
  const s = await sweepOrphansHandler(deps({ beforeDelete: async () => void (await upload(uid, { id: "x1", buf: audioMp4(20) })) }), later());
  expect(s.deleted).toBe(1); // se intento borrar la generation listada: no-op sobre la viva
  expect(await exists(ev.name)).toBe(true);
});

test("carrera: el doc aparece entre el listado y el borrado (finalize lo creo) -> no se borra", async () => {
  const uid = rnd("s");
  await seedUser(uid);
  const ev = await upload(uid);
  const s = await sweepOrphansHandler(deps({ beforeDelete: async () => void (await seedRecording(uid, ev)) }), later());
  expect(s).toMatchObject({ deleted: 0, kept: 1 });
  expect(await exists(ev.name)).toBe(true);
});

test("presupuesto de tiempo: deja de EMPEZAR borrados; lo pendiente sigue en la siguiente pasada", async () => {
  const uid = rnd("s");
  for (let i = 0; i < 4; i++) await upload(uid);
  let t = 0;
  const s = await sweepOrphansHandler(deps({ budgetMs: 1500, nowMs: () => (t += 1000) }), later());
  expect(s.budgetExhausted).toBe(true);
  expect(s.deleted).toBeLessThan(4);
  const rest = await sweepOrphansHandler(deps(), later());
  expect(s.deleted + rest.deleted).toBe(4);
});

test("un listado que falla se registra sin PII y no lanza", async () => {
  const broken: SweepStore = { ...store, listPrefixes: () => Promise.reject(Object.assign(new Error("secreto"), { code: 503 })) };
  const s = await sweepOrphansHandler(deps({ store: broken }), later());
  expect(s.failedCategories).toEqual(["list"]);
  expect(JSON.stringify(logs)).not.toContain("secreto");
});

describe("W1 de la revision fresca de B5a: cursor, lecturas por lotes y aviso de presupuesto", () => {
  const cursorDoc = async () => (await db.collection("maintenance").doc("sweepRecordingOrphans").get()).data();

  test("la cola no se queda sin barrer: la cabeza que agota el presupuesto no se repite, la siguiente pasada sigue en el cursor", async () => {
    const a = rnd("a");
    const b = rnd("b");
    for (let i = 0; i < 2; i++) await seedRecording(a, await upload(a)); // cabeza: dos objetos que se conservan
    const orphan = await upload(b);
    // Reloj falso: cada consulta al presupuesto avanza 1 s; 4,5 s dan para la cabeza entera (2 dirs + 2 objetos) y nada mas.
    const run = () => {
      let t = 0;
      return sweepOrphansHandler(deps({ budgetMs: 4500, nowMs: () => (t += 1000) }), later());
    };
    const first = await run();
    expect(first.budgetExhausted).toBe(true);
    expect(await exists(orphan.name)).toBe(true);
    const c = (await cursorDoc())?.cursor as string;
    expect(c.length).toBeGreaterThan(0);
    expect(b.startsWith(c)).toBe(true);
    expect(c.length).toBeLessThan(b.length); // prefijo truncado, nunca el uid completo
    await run();
    expect(await exists(orphan.name)).toBe(false);
  });

  test("al completar la vuelta el cursor vuelve a la cabeza", async () => {
    const uid = rnd("s");
    await upload(uid);
    await db.collection("maintenance").doc("sweepRecordingOrphans").set({ cursor: "zzz" });
    await sweepOrphansHandler(deps(), later()); // cursor por detras de todo: no hay nada que barrer y la vuelta se completa
    expect((await cursorDoc())?.cursor).toBe("");
    const s = await sweepOrphansHandler(deps(), later());
    expect(s.deleted).toBe(1);
    expect(s.budgetExhausted).toBe(false);
    expect((await cursorDoc())?.cursor).toBe("");
  });

  test("los docs de una pagina se leen en UN getAll (no una lectura por objeto)", async () => {
    const uid = rnd("s");
    for (let i = 0; i < 3; i++) await upload(uid);
    const spy = jest.spyOn(db, "getAll");
    try {
      const s = await sweepOrphansHandler(deps(), later());
      expect(s.deleted).toBe(3);
      // `DocumentReference.get()` (cursor de maintenance) tambien pasa por `getAll`: se cuentan solo las lecturas de grabaciones.
      const recordingReads = spy.mock.calls.filter((args) => args.some((r) => "path" in r && r.path.includes("/recordings/")));
      expect(recordingReads).toHaveLength(1);
      expect(recordingReads[0]).toHaveLength(3);
    } finally {
      spy.mockRestore();
    }
  });

  test("presupuesto agotado -> aviso a nivel warn (sin PII)", async () => {
    const uid = rnd("s");
    for (let i = 0; i < 3; i++) await upload(uid);
    const warns: { message: string; data: Record<string, unknown> }[] = [];
    let t = 0;
    const warn = (message: string, data: Record<string, unknown>) => void warns.push({ message, data });
    await sweepOrphansHandler(deps({ budgetMs: 1500, nowMs: () => (t += 1000), warn }), later());
    expect(warns).toEqual([expect.objectContaining({ message: "sweepOrphans.budgetExhausted" })]);
    expect(JSON.stringify(warns)).not.toContain(uid);
  });
});

describe("W3: finalize tardio frente al barrido", () => {
  const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };

  test("un finalize que termina entre la comprobacion del doc y el borrado no deja un doc ready sin objeto", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    const when = await nowFor(ev.name, 25 * HOUR);
    let outcome: FinalizeOutcome | undefined;
    const racing: SweepStore = {
      ...store,
      remove: async (n, g) => {
        outcome = await finalizeRecordingHandler({ db, store, clock: () => when, flags, log: () => undefined }, ev);
        return store.remove(n, g);
      },
    };
    await sweepOrphansHandler(deps({ store: racing }), when);
    expect(outcome).toBeDefined();
    expect(outcome).not.toBe("ready");
    expect(await exists(ev.name)).toBe(false);
    expect((await recDoc(uid, idOf(ev.name)))?.status).not.toBe("ready");
  });
});
