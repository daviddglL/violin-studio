import { bucketStore, finalizeRecordingHandler, ObjectStore } from "../../src/recordings/finalize";
import { onRecordingDeletedHandler } from "../../src/recordings/on-deleted";
import { bucket, db, exists, idOf, NOW, recDoc, recRef, rnd, seedRecording, seedUser, upload, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

/** B5a.5: un finalize (re)intentado tras el borrado del dueno NO resucita el doc; la ventana con el objeto aun vivo se cierra. */
const store = bucketStore(bucket);
const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
const finalize = (ev: { name: string; generation: string }, s: ObjectStore = store) =>
  finalizeRecordingHandler({ db, store: s, clock: () => NOW, flags, log: () => undefined }, ev);
const count = async (uid: string) => (await userDoc(uid))?.recordingCount;
async function waitFor<T>(fn: () => Promise<T | undefined>, ms = 15000): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v !== undefined) return v;
    if (Date.now() > end) throw new Error("timeout");
    await new Promise((r) => setTimeout(r, 200));
  }
}

beforeEach(wipeFirestore);

test("reintento tardio: el dueno borro doc y objeto -> ignored, no recrea el doc ni sube el contador", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 2 });
  const ev = await upload(uid);
  await bucket.file(ev.name).delete();
  expect(await finalize(ev)).toBe("ignored");
  expect(await recDoc(uid, idOf(ev.name))).toBeUndefined();
  expect(await count(uid)).toBe(2);
});

test("el objeto desaparece justo tras el commit (borrado concurrente): se retira el doc recien creado y la plaza vuelve", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 3 });
  const ev = await upload(uid);
  let stats = 0;
  const racing: ObjectStore = {
    ...store,
    stat: async (name) => {
      const info = await store.stat(name);
      if (++stats === 2) await bucket.file(name).delete(); // 2.o stat = la verificacion posterior al commit
      return stats === 2 ? null : info;
    },
  };
  expect(await finalize(ev, racing)).toBe("ignored");
  expect(await recDoc(uid, idOf(ev.name))).toBeUndefined();
  await waitFor(async () => ((await count(uid)) === 3 ? true : undefined)); // +1 del finalize, -1 del trigger del borrado
});

test("reintento de un finalize que cayo tras el commit: doc ready sin objeto -> se retira (no queda un doc colgando)", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 1 });
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  await bucket.file(ev.name).delete();
  expect(await finalize(ev)).toBe("ignored");
  expect(await recDoc(uid, id)).toBeUndefined();
  await waitFor(async () => ((await count(uid)) === 0 ? true : undefined));
});

test("un doc ready de OTRA generation no se toca aunque el objeto del evento haya desaparecido", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 1 });
  const old = await upload(uid, { id: "r1" });
  const id = await seedRecording(uid, old);
  await bucket.file(old.name).delete();
  expect(await finalize({ name: old.name, generation: "1" })).toBe("ignored");
  expect(await recDoc(uid, id)).toMatchObject({ status: "ready" });
});

test("ventana con el objeto aun vivo: el finalize recrea el doc y onRecordingDeleted lo vuelve a borrar; contador consistente", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 1 });
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  const data = (await recDoc(uid, id))!;
  await recRef(uid, id).delete(); // el dueno borra el doc; el trigger todavia no corrio
  expect(await finalize(ev)).toBe("ready"); // reintento tardio dentro de la ventana
  expect(await count(uid)).toBe(2);
  await onRecordingDeletedHandler({ db, store, log: () => undefined }, { uid, id, data }); // el trigger del primer borrado
  expect(await recDoc(uid, id)).toBeUndefined();
  expect(await exists(ev.name)).toBe(false);
  await waitFor(async () => ((await count(uid)) === 0 ? true : undefined)); // 1 + 1 (finalize) - 1 (primer borrado) - 1 (doc recreado)
  await new Promise((r) => setTimeout(r, 1000));
  expect(await count(uid)).toBe(0);
});
