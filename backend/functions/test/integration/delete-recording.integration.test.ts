import { Timestamp } from "firebase-admin/firestore";
import { bucketStore, ObjectStore } from "../../src/recordings/finalize";
import { deleteRecording } from "../../src/recordings/delete-recording";
import { audioMp4 } from "../unit/helpers/mp4-builder";
import { bucket, db, exists, idOf, recDoc, recRef, reviewCount, rnd, seedRecording, seedReview, upload, wipeFirestore } from "./helpers/finalize-fixtures";

const store = bucketStore(bucket);
const deps = (over: Partial<{ store: ObjectStore }> = {}) => ({ db, store, ...over });

beforeEach(wipeFirestore);

test("borra el objeto por su objectPath, el doc y las reviews", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  await seedReview(uid, id, "t1");
  await seedReview(uid, id, "t2");
  expect(await deleteRecording(deps(), recRef(uid, id))).toBe("deleted");
  expect(await exists(ev.name)).toBe(false);
  expect(await recDoc(uid, id)).toBeUndefined();
  expect(await reviewCount(uid, id)).toBe(0);
});

test("objeto 404 = OK: borra el doc igualmente", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  await bucket.file(ev.name).delete();
  expect(await deleteRecording(deps(), recRef(uid, id))).toBe("deleted");
  expect(await recDoc(uid, id)).toBeUndefined();
});

test("demo: borra el objeto de demos/ y no toca el del otro prefijo con el mismo id", async () => {
  const t = rnd("t");
  const demo = await upload(t, { dir: "demos", id: "same", meta: { sharedWith: "s1", assignmentId: "a1" } });
  const other = await upload(t, { dir: "recordings", id: "same" });
  const id = await seedRecording(t, demo, { sharedWith: "s1", assignmentId: "a1" });
  await deleteRecording(deps(), recRef(t, id));
  expect(await exists(demo.name)).toBe(false);
  expect(await exists(other.name)).toBe(true);
  expect(await recDoc(t, id)).toBeUndefined();
});

test("un fallo al borrar el objeto NO borra el doc ni las reviews; reintentar lo completa", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  await seedReview(uid, id);
  const failing: ObjectStore = { ...store, remove: async () => Promise.reject(Object.assign(new Error("secreto /users/x"), { code: 503 })) };
  await expect(deleteRecording(deps({ store: failing }), recRef(uid, id))).rejects.toThrow();
  expect(await recDoc(uid, id)).toBeDefined();
  expect(await reviewCount(uid, id)).toBe(1);
  expect(await exists(ev.name)).toBe(true);
  expect(await deleteRecording(deps(), recRef(uid, id))).toBe("deleted");
  expect(await exists(ev.name)).toBe(false);
  expect(await recDoc(uid, id)).toBeUndefined();
});

test("repetirlo es idempotente (doc ausente) y completa una cascada de reviews a medias", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  await deleteRecording(deps(), recRef(uid, id));
  expect(await deleteRecording(deps(), recRef(uid, id))).toBe("absent");
  await seedReview(uid, id); // reviews huerfanas de un intento anterior caido tras borrar el doc
  expect(await deleteRecording(deps(), recRef(uid, id))).toBe("absent");
  expect(await reviewCount(uid, id)).toBe(0);
});

test("solo borra la generation del doc: un objeto resubido con el mismo nombre sobrevive", async () => {
  const uid = rnd("s");
  const ev = await upload(uid, { id: "x1" });
  const id = await seedRecording(uid, ev);
  await upload(uid, { id: "x1", buf: audioMp4(20) }); // nueva generation en la misma ruta
  await deleteRecording(deps(), recRef(uid, id));
  expect(await exists(ev.name)).toBe(true);
  expect(await recDoc(uid, id)).toBeUndefined();
});

test("objectPath ajeno al dueno del doc (o fuera de los prefijos): el doc se borra y el objeto ajeno NO", async () => {
  const uid = rnd("s");
  const victim = await upload(rnd("v"));
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev, { objectPath: victim.name, generation: victim.generation });
  expect(await deleteRecording(deps(), recRef(uid, id))).toBe("deleted");
  expect(await exists(victim.name)).toBe(true);
  await bucket.file("users/" + uid + "/avatar.png").save("x", { resumable: false });
  const id2 = await seedRecording(uid, ev, { objectPath: `users/${uid}/avatar.png` });
  await deleteRecording(deps(), recRef(uid, id2));
  expect(await exists(`users/${uid}/avatar.png`)).toBe(true);
  expect(await recDoc(uid, id2)).toBeUndefined();
});

test("doc `rejected` (objectPath + generation) se borra igual", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev, { status: "rejected", rejectReason: "quota" });
  await deleteRecording(deps(), recRef(uid, id));
  expect(await exists(ev.name)).toBe(false);
  expect(await recDoc(uid, id)).toBeUndefined();
});

test("guard falso -> skipped sin tocar nada", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  expect(await deleteRecording(deps(), recRef(uid, id), { guard: () => false })).toBe("skipped");
  expect(await exists(ev.name)).toBe(true);
  expect(await recDoc(uid, id)).toBeDefined();
});

test("el doc cambia entre la lectura y el borrado -> skipped (el doc nuevo sobrevive)", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  const out = await deleteRecording(deps(), recRef(uid, id), {
    beforeDelete: async () => void (await recRef(uid, id).update({ expiresAt: Timestamp.fromMillis(Date.now() + 1e9) })),
  });
  expect(out).toBe("skipped");
  expect(await recDoc(uid, id)).toBeDefined();
});

test("no escribe PII en logs (ninguno)", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  const logs: unknown[] = [];
  await deleteRecording({ ...deps(), log: (m, d) => void logs.push([m, d]) }, recRef(uid, id));
  const text = JSON.stringify(logs);
  expect(text).not.toContain(uid);
  expect(text).not.toContain(idOf(ev.name));
});

test("un doc nuevo creado con el mismo id justo tras el borrado NO cae con la cascada (solo caen las reviews)", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  await seedReview(uid, id);
  const next = await upload(uid, { id });
  const out = await deleteRecording(deps(), recRef(uid, id), {
    afterDelete: async () => void (await seedRecording(uid, next, { generation: next.generation })),
  });
  expect(out).toBe("deleted");
  expect(await recDoc(uid, id)).toMatchObject({ generation: next.generation });
  expect(await reviewCount(uid, id)).toBe(0);
});

test("objectPath de OTRO id del mismo dueno: el doc se borra y ese objeto NO (revision fresca de B5a)", async () => {
  const uid = rnd("s");
  const ev = await upload(uid);
  const other = await upload(uid);
  const id = await seedRecording(uid, ev, { objectPath: other.name, generation: other.generation });
  expect(await deleteRecording(deps(), recRef(uid, id))).toBe("deleted");
  expect(await exists(other.name)).toBe(true);
});
