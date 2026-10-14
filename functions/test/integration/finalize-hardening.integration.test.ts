import { Firestore, Transaction } from "firebase-admin/firestore";
import {
  bucketStore, FinalizeEvent, finalizeRecordingHandler, ObjectStore, POISON_AFTER_MS,
} from "../../src/recordings/finalize";
import { audioMp4 } from "../unit/helpers/mp4-builder";
import {
  bucket, db, exists, idOf, NOW, recDoc, rnd, seedGrant, seedLink, seedTeacher, seedUser, upload, userDoc, wipeFirestore,
} from "./helpers/finalize-fixtures";

/** Correcciones de la revision fresca de B4b (W1-W4, S2, S3). */
const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
const real = bucketStore(bucket);
type Log = Record<string, unknown>;
const run = (ev: FinalizeEvent, over: Record<string, unknown> = {}, logs: Log[] = []) =>
  finalizeRecordingHandler(
    { db, store: real, clock: () => NOW, flags, requiredConsentVersion: 1, log: (_m: string, d: Log) => void logs.push(d), ...over },
    ev,
  );
const generationOf = async (name: string) => String((await bucket.file(name).getMetadata())[0].generation);

/** Simula la carrera: justo antes de que finalize borre, el cliente ya ha vuelto a subir el mismo nombre (otra generation). */
function racingStore(reupload: () => Promise<unknown>): ObjectStore {
  let done = false;
  return {
    ...real,
    remove: async (...args: Parameters<ObjectStore["remove"]>) => {
      if (!done) {
        done = true;
        await reupload();
      }
      return real.remove(...args);
    },
  };
}

beforeEach(wipeFirestore);

describe("W1: el borrado de finalize solo afecta a la generation del evento", () => {
  test("bucketStore.remove(name, generationVieja) no toca una generation nueva; con la vigente borra; 404 = OK", async () => {
    const name = `users/${rnd("s")}/recordings/${rnd("r")}.m4a`;
    await bucket.file(name).save(audioMp4(5), { resumable: false, contentType: "audio/mp4" });
    const old = await generationOf(name);
    await bucket.file(name).save(audioMp4(6), { resumable: false, contentType: "audio/mp4" });
    const live = await generationOf(name);
    expect(live).not.toBe(old);
    await expect(real.remove(name, old)).resolves.toBeUndefined();
    expect(await exists(name)).toBe(true);
    await real.remove(name, live);
    expect(await exists(name)).toBe(false);
    await expect(real.remove(name, live)).resolves.toBeUndefined();
  });

  test("rejected: gen1 rechazada y el cliente resube el mismo id antes del borrado -> gen2 sobrevive y luego queda ready", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const id = rnd("r");
    const gen1 = await upload(uid, { id, buf: audioMp4(61) });
    let gen2: FinalizeEvent | undefined;
    const store = racingStore(async () => (gen2 = await upload(uid, { id, buf: audioMp4(30) })));
    expect(await run(gen1, { store })).toBe("rejected");
    expect(gen2!.generation).not.toBe(gen1.generation);
    expect(await exists(gen1.name)).toBe(true); // la gen2 sigue ahi
    expect(await run(gen2!)).toBe("ready");
    expect(await recDoc(uid, id)).toMatchObject({ status: "ready", generation: gen2!.generation });
  });

  test("conflict, dropped (sin doc de usuario) y dropped (ruta sin id valido): tampoco borran una generation nueva", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const id = rnd("r");
    expect(await run(await upload(uid, { id }))).toBe("ready");
    const other = await upload(uid, { id, ext: "mp4" });
    expect(await run(other, { store: racingStore(() => upload(uid, { id, ext: "mp4" })) })).toBe("conflict");
    expect(await exists(other.name)).toBe(true);

    const ghost = rnd("g"); // sin doc de usuario
    const ev = await upload(ghost);
    expect(await run(ev, { store: racingStore(() => upload(ghost, { id: idOf(ev.name) })) })).toBe("dropped");
    expect(await exists(ev.name)).toBe(true);

    const bad = `users/${uid}/recordings/bad_name.m4a`;
    const save = () => bucket.file(bad).save(audioMp4(5), { resumable: false, contentType: "audio/mp4" });
    await save();
    const badEv = { name: bad, generation: await generationOf(bad) };
    expect(await run(badEv, { store: racingStore(save) })).toBe("dropped");
    expect(await exists(bad)).toBe(true);
  });
});

describe("W3: epoca de captura sellada por el cliente (metadato recordingConsentEpoch)", () => {
  const regranted = async (uid: string) => {
    await seedUser(uid, { recordingConsentEpoch: 1 }, { consentDoc: false });
    await seedGrant(uid, 1, 1, new Date(Date.now() - 60_000)); // reconcedida hace 1 min: el objeto se crea DESPUES
  };

  test("capturada en la epoca 0 y subida tras la reconcesion (cola offline / otro dispositivo) -> rejected(consent)", async () => {
    const uid = rnd("s");
    await regranted(uid);
    const ev = await upload(uid, { meta: { recordingConsentEpoch: "0" } });
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("consent");
    expect(await exists(ev.name)).toBe(false);
  });

  test("sin sello o con sello no numerico -> rejected(consent) (falla cerrado)", async () => {
    const uid = rnd("s");
    await regranted(uid);
    for (const stamp of [undefined, "uno", "1.0", ""]) {
      const ev = await upload(uid, { meta: { recordingConsentEpoch: stamp } });
      expect(await run(ev)).toBe("rejected");
      expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("consent");
    }
  });

  test("RESIDUAL documentado: creado tras la reconcesion con el sello de la epoca vigente -> ready (el servidor no ve el instante de captura; confia en el sello, que las reglas atan a la epoca al subir)", async () => {
    const uid = rnd("s");
    await regranted(uid);
    const ev = await upload(uid, { meta: { recordingConsentEpoch: "1" } });
    expect(await run(ev)).toBe("ready");
    expect((await recDoc(uid, idOf(ev.name)))?.recordingConsentEpoch).toBe(1);
  });

  test("demo del profesor: el sello es la epoca del PROFESOR (dueno del objeto)", async () => {
    const t = rnd("t");
    const s = rnd("s");
    await seedTeacher(t, { recordingConsentEpoch: 3 });
    await seedGrant(t, 1, 3, new Date(Date.now() - 60_000));
    await seedUser(s);
    await seedLink(t, s);
    await db.collection("users").doc(s).collection("assignments").doc("a1").set({ status: "open", teacherUid: t });
    const ok = await upload(t, { dir: "demos", meta: { sharedWith: s, assignmentId: "a1", recordingConsentEpoch: "3" } });
    expect(await run(ok)).toBe("ready");
    const stale = await upload(t, { dir: "demos", meta: { sharedWith: s, assignmentId: "a1", recordingConsentEpoch: "0" } });
    expect(await run(stale)).toBe("rejected");
  });
});

describe("W4: eventos envenenados (fallo no-Mp4Error persistente)", () => {
  const broken: ObjectStore = { ...real, readRange: async () => { throw Object.assign(new Error("read failed"), { code: 500 }); } };
  const at = (msAgo: number) => new Date(NOW.getTime() - msAgo).toISOString();

  test("el umbral es una constante con nombre (6 h)", () => {
    expect(POISON_AFTER_MS).toBe(6 * 60 * 60 * 1000);
  });

  test("antes del umbral: lanza (reintento) sin escribir doc ni borrar", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    await expect(run({ ...ev, time: at(POISON_AFTER_MS - 60_000) }, { store: broken })).rejects.toThrow(/^finalize_transient:/);
    await expect(run(ev, { store: broken })).rejects.toThrow(/^finalize_transient:/); // sin time: nunca es permanente
    expect(await recDoc(uid, idOf(ev.name))).toBeUndefined();
    expect(await exists(ev.name)).toBe(true);
  });

  test("pasado el umbral: doc rejected(internal), objeto borrado, contador intacto y log con reason internal", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    const logs: Log[] = [];
    expect(await run({ ...ev, time: at(POISON_AFTER_MS + 60_000) }, { store: broken }, logs)).toBe("rejected");
    expect(await recDoc(uid, idOf(ev.name))).toMatchObject({
      status: "rejected", rejectReason: "internal", objectPath: ev.name, generation: ev.generation,
    });
    expect(await exists(ev.name)).toBe(false);
    expect((await userDoc(uid))?.recordingCount).toBeUndefined();
    expect(logs).toEqual([expect.objectContaining({ outcome: "rejected", reason: "internal" })]);
  });

  test("pasado el umbral con un doc ready ya existente (misma generation) no lo degrada", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    expect(await run(ev)).toBe("ready");
    const failingTx = Object.assign(Object.create(db), {
      runTransaction: () => Promise.reject(Object.assign(new Error("aborted"), { code: 10 })),
    }) as Firestore;
    const old = { ...ev, time: at(POISON_AFTER_MS + 60_000) };
    // el pre-check ve el doc ready -> duplicate sin llegar a fallar
    expect(await run(old, { db: failingTx })).toBe("duplicate");
    expect((await recDoc(uid, idOf(ev.name)))?.status).toBe("ready");
    expect(await exists(ev.name)).toBe(true);
  });

  test("ruta sin id valido cuyo borrado falla persistentemente: se abandona (ignored, reason internal) sin lanzar", async () => {
    const uid = rnd("s");
    const bad = `users/${uid}/recordings/bad_name.m4a`;
    await bucket.file(bad).save(audioMp4(5), { resumable: false, contentType: "audio/mp4" });
    const noDelete: ObjectStore = { ...real, remove: async () => { throw new Error("delete failed"); } };
    const logs: Log[] = [];
    const ev = { name: bad, generation: await generationOf(bad) };
    await expect(run({ ...ev, time: at(60_000) }, { store: noDelete })).rejects.toThrow(/^finalize_transient:/);
    expect(await run({ ...ev, time: at(POISON_AFTER_MS + 60_000) }, { store: noDelete }, logs)).toBe("ignored");
    expect(logs.at(-1)).toMatchObject({ outcome: "ignored", reason: "internal" });
  });
});

describe("S2: los errores se relanzan saneados (sin rutas, uids ni mensajes del SDK)", () => {
  test("finalize_transient:<code> con el codigo del error, o unknown; sin cause", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    const leaky = (code?: unknown): ObjectStore => ({
      ...real,
      stat: async () => { throw Object.assign(new Error(`No such object: ${bucket.name}/${ev.name}`), code === undefined ? {} : { code }); },
    });
    for (const [code, expected] of [[503, "503"], ["ECONNRESET", "ECONNRESET"], [undefined, "unknown"], [`x/${uid}`, "unknown"]] as const) {
      const err = await run(ev, { store: leaky(code) }).catch((e: unknown) => e as Error);
      expect(err).toBeInstanceOf(Error);
      expect((err as Error).message).toBe(`finalize_transient:${expected}`);
      expect((err as Error & { cause?: unknown }).cause).toBeUndefined();
      expect((err as Error).stack ?? "").not.toContain(uid);
    }
  });
});

describe("S3: reason se reinicia en cada intento de la transaccion", () => {
  test("primer intento rechaza (consent) y aborta; el reintento acepta -> log ready SIN reason", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    let attempt = 0;
    const retrying = Object.assign(Object.create(db), {
      runTransaction: async <T>(fn: (tx: Transaction) => Promise<T>): Promise<T> => {
        if (attempt++ === 0) {
          await db.collection("users").doc(uid).update({ recordingConsent: "revoked" });
          await db.runTransaction(async (tx) => {
            await fn(tx);
            throw new Error("abort-first-attempt");
          }).catch(() => undefined);
          await db.collection("users").doc(uid).update({ recordingConsent: "granted" });
        }
        return db.runTransaction(fn);
      },
    }) as Firestore;
    const logs: Log[] = [];
    expect(await run(ev, { db: retrying }, logs)).toBe("ready");
    expect(attempt).toBe(1);
    expect(logs).toEqual([{ outcome: "ready", uidHash: expect.any(String), dir: "recordings" }]);
  });
});
