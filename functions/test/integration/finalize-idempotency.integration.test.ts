import { revokeRecordingConsentHandler } from "../../src/consent/recording-consent";
import { bucketStore, finalizeRecordingHandler, ObjectStore } from "../../src/recordings/finalize";
import { audioMp4 } from "../unit/helpers/mp4-builder";
import {
  bucket, db, exists, idOf, NOW, recDoc, recRef, rnd, seedAssignment, seedGrant, seedLink, seedTeacher, seedUser, upload, userDoc, wipeFirestore,
} from "./helpers/finalize-fixtures";

const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
const real = bucketStore(bucket);
const deps = (over: Record<string, unknown> = {}) => ({
  db,
  store: real,
  clock: () => NOW,
  flags,
  requiredConsentVersion: 1,
  log: () => undefined,
  ...over,
});
const run = (ev: { name: string; generation: string }, over: Record<string, unknown> = {}) => finalizeRecordingHandler(deps(over), ev);
const snap = async (uid: string, id: string) => {
  const s = await recRef(uid, id).get();
  return { data: s.data(), updateTime: s.updateTime?.toMillis() };
};

beforeEach(wipeFirestore);

describe("B4b.2 idempotencia y reintentos", () => {
  test("evento doble con la misma generation (secuencial) -> un solo doc y no lo toca", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    expect(await run(ev)).toBe("ready");
    const before = await snap(uid, idOf(ev.name));
    expect(await run(ev)).toBe("duplicate");
    expect(await snap(uid, idOf(ev.name))).toEqual(before);
    expect(await exists(ev.name)).toBe(true);
  });

  test("evento doble en paralelo -> un solo doc ready y una sola entrega efectiva", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    const outcomes = await Promise.all([run(ev), run(ev), run(ev)]);
    expect(outcomes.filter((o) => o === "ready")).toHaveLength(1);
    expect(outcomes.filter((o) => o === "duplicate")).toHaveLength(2);
    expect((await recDoc(uid, idOf(ev.name)))?.status).toBe("ready");
  });

  test("rutas fuera de users/*/recordings/ y users/*/demos/ se ignoran: ni doc ni borrado", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    for (const name of [`users/${uid}/other/x.m4a`, `avatars/${uid}/x.m4a`, `users/${uid}/profile.m4a`, `users/${uid}`]) {
      await bucket.file(name).save(audioMp4(10), { resumable: false, contentType: "audio/mp4" });
      expect(await run({ name, generation: "1" })).toBe("ignored");
      expect(await exists(name)).toBe(true);
    }
    expect((await db.collection("users").doc(uid).collection("recordings").get()).size).toBe(0);
  });

  test("dentro de los prefijos pero sin nombre/extension validos: objeto borrado por su ruta y sin doc (no hay id)", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    for (const name of [`users/${uid}/recordings/bad_name.m4a`, `users/${uid}/demos/x.txt`, `users/${uid}/recordings/sub/x.m4a`]) {
      await bucket.file(name).save(audioMp4(10), { resumable: false, contentType: "audio/mp4" });
      expect(await run({ name, generation: "1" })).toBe("dropped");
      expect(await exists(name)).toBe(false);
    }
    expect((await db.collection("users").doc(uid).collection("recordings").get()).size).toBe(0);
  });

  test("evento tardio de un objeto que ya no existe (borrado por el dueno) -> no resucita el doc", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    expect(await run(ev)).toBe("ready");
    await recRef(uid, idOf(ev.name)).delete(); // el dueno borra el doc y onRecordingDeleted el objeto
    await bucket.file(ev.name).delete();
    expect(await run(ev)).toBe("ignored");
    expect(await recDoc(uid, idOf(ev.name))).toBeUndefined();
    expect((await userDoc(uid))?.recordingCount).toBeUndefined();
  });

  test("evento con generation distinta de la vigente del objeto (stale) -> ignorado sin tocar nada", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    expect(await run({ name: ev.name, generation: "1" })).toBe("ignored");
    expect(await recDoc(uid, idOf(ev.name))).toBeUndefined();
    expect(await exists(ev.name)).toBe(true);
  });

  test("un fallo de lectura de Storage NO es un rechazo: el handler lanza (reintento) y no escribe ni borra", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    const flaky: ObjectStore = { ...real, readRange: async () => { throw new Error("storage unavailable"); } };
    await expect(run(ev, { store: flaky })).rejects.toThrow("storage unavailable");
    expect(await recDoc(uid, idOf(ev.name))).toBeUndefined();
    expect(await exists(ev.name)).toBe(true);
    expect(await run(ev)).toBe("ready"); // el reintento funciona
  });

  test("un fallo de stat() tambien reintenta", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    const flaky: ObjectStore = { ...real, stat: async () => { throw new Error("boom"); } };
    await expect(run(ev, { store: flaky })).rejects.toThrow("boom");
    expect(await run(ev)).toBe("ready");
  });

  test("si el borrado del objeto rechazado falla tras escribir el doc, el reintento (misma generation) lo borra", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingConsent: "revoked" });
    const ev = await upload(uid);
    const noDelete: ObjectStore = { ...real, remove: async () => { throw new Error("delete failed"); } };
    await expect(run(ev, { store: noDelete })).rejects.toThrow("delete failed");
    expect((await recDoc(uid, idOf(ev.name)))?.status).toBe("rejected");
    expect(await exists(ev.name)).toBe(true);
    expect(await run(ev)).toBe("duplicate"); // el doc rejected ya existia; solo se completa el borrado
    expect(await exists(ev.name)).toBe(false);
  });

  test("tras un rechazo, resubir el mismo id (nueva generation) se reevalua y reemplaza el doc rejected", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const id = rnd("r");
    const bad = await upload(uid, { id, buf: audioMp4(61) });
    expect(await run(bad)).toBe("rejected");
    const good = await upload(uid, { id, buf: audioMp4(30) });
    expect(good.generation).not.toBe(bad.generation);
    expect(await run(good)).toBe("ready");
    expect(await recDoc(uid, id)).toMatchObject({ status: "ready", generation: good.generation });
    // y el evento viejo (retrasado) ya no puede degradarlo
    expect(await run(bad)).toBe("ignored");
    expect((await recDoc(uid, id))?.status).toBe("ready");
  });

  test("un doc ready existente con otra generation en la misma ruta no se sobrescribe", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const id = rnd("r");
    const first = await upload(uid, { id });
    expect(await run(first)).toBe("ready");
    const before = await snap(uid, id);
    const second = await upload(uid, { id, buf: audioMp4(20) });
    expect(await run(second)).toBe("duplicate");
    expect(await snap(uid, id)).toEqual(before);
  });
});

describe("B4b.2 objectPath: mismo id en el otro prefijo (B3)", () => {
  test("doc ready en recordings/x.m4a y llega demos/x.m4a -> doc intacto, solo se borra el objeto del evento", async () => {
    const t = rnd("t");
    const s = rnd("s");
    await seedTeacher(t);
    await seedUser(s);
    await seedLink(t, s);
    await seedAssignment(s, "a1", { teacherUid: t });
    const id = rnd("r");
    const own = await upload(t, { id, dir: "recordings" });
    expect(await run(own)).toBe("ready");
    const before = await snap(t, id);
    const demo = await upload(t, { id, dir: "demos", meta: { sharedWith: s, assignmentId: "a1" } });
    expect(await run(demo)).toBe("conflict");
    expect(await snap(t, id)).toEqual(before); // byte a byte (datos + updateTime)
    expect((await recDoc(t, id))?.objectPath).toBe(own.name);
    expect(await exists(demo.name)).toBe(false);
    expect(await exists(own.name)).toBe(true);
    expect((await userDoc(t))?.recordingCount).toBeUndefined(); // sin contador: se comprueba en B4b.3
  });

  test("a la inversa (doc de demo, llega el mismo id en recordings/) y con doc rejected tambien", async () => {
    const t = rnd("t");
    const s = rnd("s");
    await seedTeacher(t);
    await seedUser(s);
    await seedLink(t, s);
    await seedAssignment(s, "a1", { teacherUid: t });
    const id = rnd("r");
    const demo = await upload(t, { id, dir: "demos", meta: { sharedWith: s, assignmentId: "a1" } });
    expect(await run(demo)).toBe("ready");
    const before = await snap(t, id);
    const own = await upload(t, { id, dir: "recordings" });
    expect(await run(own)).toBe("conflict");
    expect(await snap(t, id)).toEqual(before);
    expect(await exists(demo.name)).toBe(true);
    expect(await exists(own.name)).toBe(false);

    const id2 = rnd("r");
    const bad = await upload(t, { id: id2, dir: "recordings", buf: audioMp4(500) });
    expect(await run(bad)).toBe("rejected");
    const before2 = await snap(t, id2);
    const other = await upload(t, { id: id2, dir: "demos", meta: { sharedWith: s, assignmentId: "a1" } });
    expect(await run(other)).toBe("conflict");
    expect(await snap(t, id2)).toEqual(before2);
  });

  test("la extension distinta (.mp4 vs .m4a) con el mismo id tambien es un objectPath distinto", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const id = rnd("r");
    const a = await upload(uid, { id });
    expect(await run(a)).toBe("ready");
    const before = await snap(uid, id);
    const b = await upload(uid, { id, ext: "mp4" });
    expect(await run(b)).toBe("conflict");
    expect(await snap(uid, id)).toEqual(before);
    expect(await exists(b.name)).toBe(false);
    expect(await exists(a.name)).toBe(true);
  });
});

describe("B4b.2 consentimiento y cuenta durante la subida", () => {
  test("consentimiento revocado durante la subida (callable real) -> rejected(consent) y objeto borrado", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    await revokeRecordingConsentHandler({ db }, uid);
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("consent");
    expect(await exists(ev.name)).toBe(false);
  });

  test("subida de una epoca anterior (objeto creado antes de la concesion vigente) -> rejected(consent)", async () => {
    const uid = rnd("s");
    // epoca 1 concedida dentro de 1 h: el objeto (creado ahora) es de la epoca 0
    await seedUser(uid, { recordingConsentEpoch: 1 }, { consentDoc: false });
    await seedGrant(uid, 1, 1, new Date(Date.now() + 3600_000));
    const ev = await upload(uid);
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("consent");
    expect(await exists(ev.name)).toBe(false);
  });

  test("subida posterior a la concesion de la epoca vigente -> ready estampando recordingConsentEpoch", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingConsentEpoch: 2 }, { consentDoc: false });
    await seedGrant(uid, 1, 2, new Date(Date.now() - 3600_000), "guardian");
    const ev = await upload(uid);
    expect(await run(ev)).toBe("ready");
    expect((await recDoc(uid, idOf(ev.name)))?.recordingConsentEpoch).toBe(2);
  });

  test("sin registro de concesion de la epoca (no se puede fechar) -> rejected(consent): falla cerrado", async () => {
    const uid = rnd("s");
    await seedUser(uid, {}, { consentDoc: false });
    const ev = await upload(uid);
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("consent");
  });

  test("cuenta en proceso de borrado: objeto borrado y NINGUN doc (no se escribe en una cuenta que se esta borrando)", async () => {
    const uid = rnd("s");
    await seedUser(uid, { deletion: { startedAt: new Date() } });
    const ev = await upload(uid);
    expect(await run(ev)).toBe("dropped");
    expect(await exists(ev.name)).toBe(false);
    expect(await recDoc(uid, idOf(ev.name))).toBeUndefined();
  });

  test("dueno sin doc de usuario (cuenta ya borrada): objeto borrado y ningun doc", async () => {
    const uid = rnd("s");
    const ev = await upload(uid);
    expect(await run(ev)).toBe("dropped");
    expect(await exists(ev.name)).toBe(false);
    expect((await db.collection("users").doc(uid).collection("recordings").get()).size).toBe(0);
  });
});

describe("B4b.2 metadatos del cliente no confiables", () => {
  test("sharedWith/assignmentId con '/' o '..' no se interpolan en rutas: rechazo limpio, sin excepcion", async () => {
    const t = rnd("t");
    await seedTeacher(t);
    const demo = await upload(t, { dir: "demos", meta: { sharedWith: "a/b", assignmentId: "x" } });
    expect(await run(demo)).toBe("rejected");
    expect((await recDoc(t, idOf(demo.name)))?.rejectReason).toBe("sharedWith");
    const own = await upload(t, { meta: { assignmentId: "../x" } });
    expect(await run(own)).toBe("rejected");
    expect((await recDoc(t, idOf(own.name)))?.rejectReason).toBe("assignment");
  });
});
