import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { bucketSweepStore, SweepStore } from "../../src/recordings/orphan-sweep";
import { purgeRevokedRecordings } from "../../src/recordings/revoke-purge";
import { bucket, db, exists, recDoc, recRef, reviewCount, rnd, seedAssignment, seedLink, seedRecording, seedReview, seedUser, upload, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

const store: SweepStore = bucketSweepStore(bucket);
let logs: { message: string; data: Record<string, unknown> }[];
const deps = (over: Record<string, unknown> = {}) => ({ db, store, log: (message: string, data: Record<string, unknown>) => void logs.push({ message, data }), ...over });
const SINCE = Timestamp.fromMillis(1_700_000_000_000);
const revoked = (uid: string, epoch: number, over: Record<string, unknown> = {}) =>
  seedUser(uid, { recordingConsent: "revoked", recordingConsentEpoch: epoch + 1, recordingPurge: { pending: true, since: SINCE, epoch }, ...over });

/** Grabacion con objeto y doc `ready` de la epoca dada. */
async function rec(uid: string, epoch: number, over: Record<string, unknown> = {}, up: Parameters<typeof upload>[1] = {}) {
  const ev = await upload(uid, { meta: { recordingConsentEpoch: String(epoch) }, ...up });
  const id = await seedRecording(uid, ev, { recordingConsentEpoch: epoch, ...over });
  return { ev, id };
}

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
  await bucket.deleteFiles({ force: true });
});

describe("purgeRevokedRecordings: revocacion completa (REQ-REC-09)", () => {
  test("borra objeto, doc y revisiones de todas las grabaciones (incluida una demo en demos/ y un rejected sin epoca), baja pending y conserva since/epoch", async () => {
    const [u, other] = [rnd("u"), rnd("o")];
    await revoked(u, 0);
    await seedUser(other);
    const a = await rec(u, 0);
    const b = await rec(u, 0);
    const demo = await rec(u, 0, { sharedWith: "sx" }, { dir: "demos", meta: { sharedWith: "sx", assignmentId: "a" } });
    const rej = await upload(u);
    const rejId = await seedRecording(u, rej, { status: "rejected", rejectReason: "quota" });
    await db.collection("users").doc(u).collection("recordings").doc(rejId).update({ recordingConsentEpoch: FieldValue.delete() });
    await seedReview(u, a.id);
    const keep = await rec(other, 0);

    const r = await purgeRevokedRecordings(deps(), u);

    expect(r).toMatchObject({ outcome: "completed", failed: 0 });
    for (const x of [a, b, demo]) {
      expect(await exists(x.ev.name)).toBe(false);
      expect(await recDoc(u, x.id)).toBeUndefined();
    }
    expect(await exists(rej.name)).toBe(false);
    expect(await recDoc(u, rejId)).toBeUndefined();
    expect(await reviewCount(u, a.id)).toBe(0);
    expect(await exists(keep.ev.name)).toBe(true);
    expect(await recDoc(other, keep.id)).toBeDefined();
    const d = await userDoc(u);
    expect(d?.recordingPurge).toMatchObject({ pending: false, epoch: 0 });
    expect((d?.recordingPurge.since as Timestamp).toMillis()).toBe(SINCE.toMillis());
    expect(d?.recordingPurge.completedAt).toBeDefined();
    expect(d).toMatchObject({ recordingConsent: "revoked", recordingConsentEpoch: 1 });
  });

  test("acotada por epoca: borra epoca <= purge.epoch y conserva (doc y objeto) la de una epoca posterior", async () => {
    const u = rnd("u");
    await revoked(u, 1, { recordingConsentEpoch: 3 });
    const old0 = await rec(u, 0);
    const old1 = await rec(u, 1);
    const newer = await rec(u, 2);
    await purgeRevokedRecordings(deps(), u);
    for (const x of [old0, old1]) {
      expect(await exists(x.ev.name)).toBe(false);
      expect(await recDoc(u, x.id)).toBeUndefined();
    }
    expect(await exists(newer.ev.name)).toBe(true);
    expect(await recDoc(u, newer.id)).toBeDefined();
    expect((await userDoc(u))?.recordingPurge.pending).toBe(false);
  });

  test("barre los objetos huerfanos (sin doc) bajo recordings/ y demos/, aunque sean recientes; conserva el de un doc vigente y los de otros", async () => {
    const [u, other] = [rnd("u"), rnd("o")];
    await revoked(u, 0, { recordingConsentEpoch: 2 });
    const orphanR = await upload(u, { dir: "recordings" });
    const orphanD = await upload(u, { dir: "demos", meta: { sharedWith: "s", assignmentId: "a" } });
    const newer = await rec(u, 1);
    const foreign = await upload(other);
    await purgeRevokedRecordings(deps(), u);
    expect(await exists(orphanR.name)).toBe(false);
    expect(await exists(orphanD.name)).toBe(false);
    expect(await exists(newer.ev.name)).toBe(true);
    expect(await exists(foreign.name)).toBe(true);
  });

  test("sin purga pendiente es un no-op (no toca grabaciones)", async () => {
    const u = rnd("u");
    await seedUser(u);
    const a = await rec(u, 0);
    expect(await purgeRevokedRecordings(deps(), u)).toMatchObject({ outcome: "none" });
    expect(await recDoc(u, a.id)).toBeDefined();
    await seedUser(u, { recordingPurge: { pending: false, since: SINCE, epoch: 0 } });
    expect(await purgeRevokedRecordings(deps(), u)).toMatchObject({ outcome: "none" });
    expect(await exists(a.ev.name)).toBe(true);
  });

  test("fallo parcial: el lote continua, pending sigue; el reintento completa y el resultado es identico al de un borrado sin fallos", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    const bad = await rec(u, 0);
    const goods = [await rec(u, 0), await rec(u, 0)];
    const flaky: SweepStore = {
      ...store,
      remove: async (name, gen) => {
        if (name === bad.ev.name) throw Object.assign(new Error(`boom ${name}`), { code: 503 });
        return store.remove(name, gen);
      },
    };
    const first = await purgeRevokedRecordings(deps({ store: flaky }), u);
    expect(first).toMatchObject({ outcome: "incomplete", failed: 1 });
    expect((await userDoc(u))?.recordingPurge.pending).toBe(true);
    expect(await recDoc(u, bad.id)).toBeDefined();
    for (const g of goods) expect(await recDoc(u, g.id)).toBeUndefined();
    const text = JSON.stringify(logs);
    for (const secret of [u, bad.id, bad.ev.name]) expect(text).not.toContain(secret);

    expect(await purgeRevokedRecordings(deps(), u)).toMatchObject({ outcome: "completed", failed: 0 });
    expect(await exists(bad.ev.name)).toBe(false);
    expect(await recDoc(u, bad.id)).toBeUndefined();
    expect((await userDoc(u))?.recordingPurge.pending).toBe(false);
    expect(await purgeRevokedRecordings(deps(), u)).toMatchObject({ outcome: "none" }); // idempotente
  });

  test("presupuesto de tiempo: shouldStop corta y deja pending (reanudable)", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    const all = [await rec(u, 0), await rec(u, 0), await rec(u, 0)];
    let n = 0;
    const r = await purgeRevokedRecordings(deps({ shouldStop: () => n++ >= 1 }), u);
    expect(r.outcome).toBe("incomplete");
    expect((await userDoc(u))?.recordingPurge.pending).toBe(true);
    const left = (await Promise.all(all.map((a) => recDoc(u, a.id)))).filter(Boolean).length;
    expect(left).toBeGreaterThan(0);
    expect(left).toBeLessThan(3);
    expect(await purgeRevokedRecordings(deps(), u)).toMatchObject({ outcome: "completed" });
    expect(await Promise.all(all.map((a) => exists(a.ev.name)))).toEqual([false, false, false]);
  });
});

describe("carreras (REQ-REC-09, B5b.3)", () => {
  test("segunda revocacion durante la purga (epoca mayor): NO baja pending con la lectura vieja; repite y borra tambien la epoca nueva", async () => {
    const u = rnd("u");
    await revoked(u, 0, { recordingConsentEpoch: 1 });
    const a = await rec(u, 0);
    let bumped = false;
    const late = { current: undefined as undefined | Awaited<ReturnType<typeof rec>> };
    const r = await purgeRevokedRecordings(
      deps({
        beforeFinish: async () => {
          if (bumped) return;
          bumped = true;
          // re-consentimiento (epoca 1) + grabacion + segunda revocacion: pending conserva since y sube la epoca
          late.current = await rec(u, 1);
          await db.collection("users").doc(u).update({ recordingConsentEpoch: 2, "recordingPurge.epoch": 1 });
        },
      }),
      u,
    );
    expect(r.outcome).toBe("completed");
    expect(await recDoc(u, a.id)).toBeUndefined();
    expect(await recDoc(u, late.current!.id)).toBeUndefined();
    expect(await exists(late.current!.ev.name)).toBe(false);
    const d = await userDoc(u);
    expect(d?.recordingPurge).toMatchObject({ pending: false, epoch: 1 });
  });

  test("since distinto (revocacion posterior tras cerrar y reabrir): pending no se baja con el since leido", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    const a = await rec(u, 0);
    const NEW_SINCE = Timestamp.fromMillis(1_800_000_000_000);
    let done = false;
    const r = await purgeRevokedRecordings(
      deps({
        beforeFinish: async () => {
          if (done) return;
          done = true;
          await db.collection("users").doc(u).update({ "recordingPurge.since": NEW_SINCE });
        },
      }),
      u,
    );
    expect(r.outcome).toBe("completed");
    expect(await recDoc(u, a.id)).toBeUndefined();
    const d = await userDoc(u);
    expect(d?.recordingPurge.pending).toBe(false);
    expect((d?.recordingPurge.since as Timestamp).toMillis()).toBe(NEW_SINCE.toMillis()); // el since vigente al terminar
  });

  test("si la purga cambia sin parar no gira sin fin: agota las rondas y deja pending", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    let n = 0;
    const r = await purgeRevokedRecordings(
      deps({ beforeFinish: async () => void (await db.collection("users").doc(u).update({ "recordingPurge.since": Timestamp.fromMillis(2_000_000_000_000 + n++) })) }),
      u,
    );
    expect(r.outcome).toBe("incomplete");
    expect((await userDoc(u))?.recordingPurge.pending).toBe(true);
  });

  test("usuario borrado a mitad (doc inexistente o con deletion): no recrea users/{uid} ni escribe y devuelve gone", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    await rec(u, 0);
    const r1 = await purgeRevokedRecordings(deps({ beforeFinish: async () => void (await db.collection("users").doc(u).delete()) }), u);
    expect(r1.outcome).toBe("gone");
    expect((await db.collection("users").doc(u).get()).exists).toBe(false);

    const v = rnd("u");
    await revoked(v, 0, { deletion: { state: "in_progress", startedAt: Timestamp.now() } });
    expect(await purgeRevokedRecordings(deps(), v)).toMatchObject({ outcome: "gone" });
    expect((await userDoc(v))?.recordingPurge.pending).toBe(true);
  });
});

describe("profesor que revoca (REQ-REC-09/10)", () => {
  test("se borran sus demostraciones (objeto en demos/ + doc) y sus grabaciones; lo del alumno queda", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await revoked(t, 0, { role: "teacher" });
    await seedUser(s, { role: "student" });
    await seedLink(t, s);
    await seedAssignment(s, "a1", { teacherUid: t, status: "open" });
    const demo = await rec(t, 0, { sharedWith: s, assignmentId: "a1" }, { dir: "demos", meta: { sharedWith: s, assignmentId: "a1" } });
    const own = await rec(t, 0);
    const mine = await rec(s, 0);
    await seedReview(s, mine.id, t);
    await purgeRevokedRecordings(deps(), t);
    expect(await exists(demo.ev.name)).toBe(false);
    expect(await recDoc(t, demo.id)).toBeUndefined();
    expect(await exists(own.ev.name)).toBe(false);
    expect(await exists(mine.ev.name)).toBe(true);
    expect((await recRef(s, mine.id).collection("reviews").get()).size).toBe(1);
  });
});

test("el store de objetos no se usa para listar fuera de users/{uid}/{recordings,demos}/", async () => {
  const u = rnd("u");
  await revoked(u, 0);
  const prefixes: string[] = [];
  const spy: SweepStore = { ...store, listObjects: async (p, t, n) => (prefixes.push(p), store.listObjects(p, t, n)) };
  await purgeRevokedRecordings(deps({ store: spy }), u);
  expect(prefixes.sort()).toEqual([`users/${u}/demos/`, `users/${u}/recordings/`]);
});

describe("revision fresca B5b: S1/S2", () => {
  test("S1: un doc `skipped` (cambio entre la lectura y el borrado) deja la purga incompleta y pending; el reintento la completa", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    const r = await rec(u, 0);
    let touched = false;
    const touching: SweepStore = {
      ...store,
      remove: async (n, g) => {
        if (!touched && n === r.ev.name) {
          touched = true;
          await recRef(u, r.id).update({ touchedAt: Timestamp.now() }); // el doc cambia: ref.delete({lastUpdateTime}) falla
        }
        return store.remove(n, g);
      },
    };
    const first = await purgeRevokedRecordings(deps({ store: touching }), u);
    expect(first.outcome).toBe("incomplete");
    expect(first.skipped).toBe(1);
    expect((await userDoc(u))?.recordingPurge.pending).toBe(true);
    expect(await recDoc(u, r.id)).toBeDefined();
    const again = await purgeRevokedRecordings(deps(), u);
    expect(again.outcome).toBe("completed");
    expect(await recDoc(u, r.id)).toBeUndefined();
  });

  test("S1: el barrido solo respeta objetos de un doc `ready` con la misma objectPath+generation (el de un `rejected` cae)", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    // doc de epoca posterior (fuera de la cota del paso 1) pero `rejected`: su objeto no lo protege
    const rej = await rec(u, 1, { status: "rejected", rejectReason: "quota" });
    const ok = await rec(u, 1); // `ready` de epoca posterior: sobrevive con su objeto
    const r = await purgeRevokedRecordings(deps(), u);
    expect(r.outcome).toBe("completed");
    expect(await exists(rej.ev.name)).toBe(false);
    expect(await recDoc(u, rej.id)).toBeDefined(); // el doc fuera de la cota no se toca
    expect(await exists(ok.ev.name)).toBe(true);
  });

  test("S2: docs por encima de la cota de epoca se conservan y se avisa (warn) de la anomalia sin uid ni ids", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    const above = await rec(u, 1);
    const warns: { message: string; data: Record<string, unknown> }[] = [];
    const r = await purgeRevokedRecordings(deps({ warn: (message: string, data: Record<string, unknown>) => void warns.push({ message, data }) }), u);
    expect(r.outcome).toBe("completed");
    expect(await recDoc(u, above.id)).toBeDefined();
    expect(warns).toEqual([{ message: "revokePurge.anomaly", data: expect.objectContaining({ anomaly: "epoch_above_bound", count: 1, uidHash: expect.any(String) }) }]);
    const text = JSON.stringify(warns);
    for (const secret of [u, above.id, above.ev.name]) expect(text).not.toContain(secret);
  });

  test("S2: sin docs por encima de la cota no hay aviso", async () => {
    const u = rnd("u");
    await revoked(u, 0);
    await rec(u, 0);
    const warns: string[] = [];
    await purgeRevokedRecordings(deps({ warn: (m: string) => void warns.push(m) }), u);
    expect(warns).toEqual([]);
  });
});
