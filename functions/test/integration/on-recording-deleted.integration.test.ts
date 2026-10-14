import { bucketStore, finalizeRecordingHandler, ObjectStore } from "../../src/recordings/finalize";
import { Timestamp } from "firebase-admin/firestore";
import { onRecordingDeletedHandler } from "../../src/recordings/on-deleted";
import { reconcileOwner } from "../../src/recordings/recount";
import { bucket, db, exists, idOf, recDoc, recRef, reviewCount, rnd, seedRecording, seedReview, seedUser, triggerBucket, upload, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

const store = bucketStore(bucket);
let logs: unknown[];
const deps = (over: Partial<{ store: ObjectStore }> = {}) => ({ db, store, log: (m: string, d: Record<string, unknown>) => void logs.push([m, d]), ...over });
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

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
});

/** Siembra un objeto y devuelve el evento de borrado SIN pasar por Firestore (no dispara el trigger emulado). */
async function seedDeleted(uid: string, over: Record<string, unknown> = {}, up: Parameters<typeof upload>[1] = {}) {
  const ev = await upload(uid, up);
  const id = idOf(ev.name);
  const data = { kind: "audio", status: "ready", durationSec: 30, sizeBytes: 100, objectPath: ev.name, generation: ev.generation, ...over };
  return { ev, id, event: { uid, id, data } };
}

test("doc ready borrado: limpia objeto y reviews y baja el contador", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 5 });
  const { ev, id, event } = await seedDeleted(uid);
  await seedReview(uid, id);
  expect(await onRecordingDeletedHandler(deps(), event)).toBe("cleaned");
  expect(await exists(ev.name)).toBe(false);
  expect(await reviewCount(uid, id)).toBe(0);
  expect(await count(uid)).toBe(4);
});

test("doc rejected: limpia pero NO baja el contador", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 5 });
  const { ev, event } = await seedDeleted(uid, { status: "rejected", rejectReason: "quota" });
  await onRecordingDeletedHandler(deps(), event);
  expect(await exists(ev.name)).toBe(false);
  expect(await count(uid)).toBe(5);
});

test("suelo 0: con contador 0 o ausente no baja ni escribe; doble disparo con 1 -> 0, nunca negativo", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 0 });
  await onRecordingDeletedHandler(deps(), (await seedDeleted(uid)).event);
  expect(await count(uid)).toBe(0);

  const u2 = rnd("s");
  await seedUser(u2);
  await onRecordingDeletedHandler(deps(), (await seedDeleted(u2)).event);
  expect(await userDoc(u2)).not.toHaveProperty("recordingCount");

  const u3 = rnd("s");
  await seedUser(u3, { recordingCount: 1 });
  const b = await seedDeleted(u3);
  await onRecordingDeletedHandler(deps(), b.event);
  await onRecordingDeletedHandler(deps(), b.event); // at-least-once: entrega doble
  expect(await count(u3)).toBe(0);
});

test("contador corrupto (cadena, negativo, no entero) no se toca ni lanza", async () => {
  for (const bad of ["7", -3, 2.5, null]) {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: bad });
    await onRecordingDeletedHandler(deps(), (await seedDeleted(uid)).event);
    expect(await count(uid)).toEqual(bad);
  }
});

test("cuenta ya borrada (sin doc de usuario): limpia objeto y reviews y NO recrea users/{uid}", async () => {
  const uid = rnd("s");
  const { ev, id, event } = await seedDeleted(uid);
  await seedReview(uid, id);
  await onRecordingDeletedHandler(deps(), event);
  expect(await exists(ev.name)).toBe(false);
  expect(await reviewCount(uid, id)).toBe(0);
  expect((await db.collection("users").doc(uid).get()).exists).toBe(false);
});

test("sin datos del doc borrado -> ignored sin efectos", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 3 });
  expect(await onRecordingDeletedHandler(deps(), { uid, id: "x", data: undefined })).toBe("ignored");
  expect(await count(uid)).toBe(3);
});

test("demo del profesor: borra el objeto de demos/ (no el del otro prefijo con el mismo id) y baja SOLO el contador del profesor", async () => {
  const t = rnd("t");
  const s = rnd("s");
  await seedUser(t, { role: "teacher", recordingCount: 3 });
  await seedUser(s, { recordingCount: 4 });
  const other = await upload(t, { dir: "recordings", id: "same" });
  const { ev, event } = await seedDeleted(t, { sharedWith: s, assignmentId: "a1" }, { dir: "demos", id: "same", meta: { sharedWith: s, assignmentId: "a1" } });
  await onRecordingDeletedHandler(deps(), event);
  expect(await exists(ev.name)).toBe(false);
  expect(await exists(other.name)).toBe(true);
  expect(await count(t)).toBe(2);
  expect(await count(s)).toBe(4);
});

test("un fallo al borrar el objeto se relanza SIN bajar el contador; el reintento lo completa (una sola bajada)", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 5 });
  const { ev, event } = await seedDeleted(uid);
  const failing: ObjectStore = { ...store, remove: () => Promise.reject(Object.assign(new Error("boom"), { code: 503 })) };
  await expect(onRecordingDeletedHandler(deps({ store: failing }), event)).rejects.toThrow();
  expect(await count(uid)).toBe(5);
  expect(await exists(ev.name)).toBe(true);
  await onRecordingDeletedHandler(deps(), event);
  expect(await count(uid)).toBe(4);
  expect(await exists(ev.name)).toBe(false);
});

test("objectPath ajeno al dueno: no borra el objeto ajeno, sigue con reviews y contador", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 2 });
  const victim = await upload(rnd("v"));
  const { event } = await seedDeleted(uid, { objectPath: victim.name, generation: victim.generation });
  await onRecordingDeletedHandler(deps(), event);
  expect(await exists(victim.name)).toBe(true);
  expect(await count(uid)).toBe(1);
});

test("solo borra la generation del doc: un objeto resubido con el mismo nombre sobrevive", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 1 });
  const { ev, event } = await seedDeleted(uid, {}, { id: "x1" });
  await upload(uid, { id: "x1" });
  await onRecordingDeletedHandler(deps(), event);
  expect(await exists(ev.name)).toBe(true);
});

test("sin PII en logs: una linea con outcome y uidHash, nunca uid, id ni ruta", async () => {
  const uid = rnd("s");
  await seedUser(uid, { recordingCount: 1 });
  const { ev, id, event } = await seedDeleted(uid);
  await onRecordingDeletedHandler(deps(), event);
  expect(logs).toHaveLength(1);
  const text = JSON.stringify(logs);
  expect(text).toContain("uidHash");
  for (const s of [uid, id, ev.name]) expect(text).not.toContain(s);
});

describe("ventana de resurreccion (W5): un finalize reintentado recrea el doc entre su borrado y el del objeto", () => {
  /** `remove` real y, UNA vez, lo que haria `finalizeRecording` con el objeto aun vivo: recrear el doc y (si `ready`) subir el contador. */
  const resurrecting = (uid: string, ev: { name: string; generation: string }, over: Record<string, unknown> = {}): ObjectStore => {
    let done = false;
    return {
      ...store,
      remove: async (name, gen) => {
        await store.remove(name, gen);
        if (done) return;
        done = true;
        await seedRecording(uid, ev, over);
        if (over.status !== "rejected") await db.collection("users").doc(uid).update({ recordingCount: (await count(uid)) + 1 });
      },
    };
  };

  test("el doc recreado con el mismo objectPath+generation se borra otra vez y el contador queda -1 respecto al inicial", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    const { ev, id, event } = await seedDeleted(uid);
    expect(await onRecordingDeletedHandler(deps({ store: resurrecting(uid, ev) }), event)).toBe("cleaned");
    expect(await recDoc(uid, id)).toBeUndefined();
    expect(await exists(ev.name)).toBe(false);
    // El borrado del doc recreado dispara el trigger (emulado) y baja la plaza que subio el finalize: 5 + 1 - 1 - 1 = 4.
    await waitFor(async () => ((await count(uid)) === 4 ? true : undefined));
    await new Promise((r) => setTimeout(r, 1000));
    expect(await count(uid)).toBe(4);
  });

  test("el doc recreado como rejected con la misma generation tambien cae, sin tocar el contador por el", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    const { ev, id, event } = await seedDeleted(uid);
    await onRecordingDeletedHandler(deps({ store: resurrecting(uid, ev, { status: "rejected", rejectReason: "quota" }) }), event);
    expect(await recDoc(uid, id)).toBeUndefined();
    await new Promise((r) => setTimeout(r, 1000));
    expect(await count(uid)).toBe(4);
  });

  test("un doc nuevo con OTRA generation (subida legitima del dueno) NO se borra", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    const { ev, id, event } = await seedDeleted(uid, {}, { id: "r1" });
    const next = await upload(uid, { id: "r1" });
    const recreate: ObjectStore = {
      ...store,
      remove: async (n, g) => {
        await store.remove(n, g);
        await seedRecording(uid, next);
      },
    };
    await onRecordingDeletedHandler(deps({ store: recreate }), event);
    expect(await recDoc(uid, id)).toMatchObject({ generation: next.generation });
    expect(await exists(ev.name)).toBe(true); // el objeto vivo es el de la generation nueva
  });
});

describe("camino completo", () => {
  test("el dueno borra el doc desde el cliente -> el trigger emulado limpia objeto y reviews y baja el contador", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 3 });
    const ev = await upload(uid, { bucket: triggerBucket });
    const id = await seedRecording(uid, ev);
    await seedReview(uid, id);
    await recRef(uid, id).delete();
    await waitFor(async () => ((await triggerBucket.file(ev.name).exists())[0] ? undefined : true));
    await waitFor(async () => ((await count(uid)) === 2 ? true : undefined));
    expect(await reviewCount(uid, id)).toBe(0);
  }, 40000);

  test("borrar el doc y reintentar el finalize del mismo evento no lo resucita (objeto ya borrado -> ignored)", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid);
    const id = await seedRecording(uid, ev);
    const data = (await recDoc(uid, id))!;
    await recRef(uid, id).delete();
    await onRecordingDeletedHandler(deps(), { uid, id, data });
    const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
    const out = await finalizeRecordingHandler({ db, store, clock: () => new Date(), flags, log: () => undefined }, ev);
    expect(out).toBe("ignored");
    expect(await recDoc(uid, id)).toBeUndefined();
  });
});

const ledger = (uid: string) => db.collection("users").doc(uid).collection("recordingCountLedger").get();
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe("idempotencia del decremento (W2 de la revision fresca de B5a)", () => {
  test("doble entrega del MISMO evento: baja una sola vez y deja una marca con TTL", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    const { event } = await seedDeleted(uid);
    const ev = { ...event, instance: "1760436000.123000000" };
    await onRecordingDeletedHandler(deps(), ev);
    await onRecordingDeletedHandler(deps(), ev);
    expect(await count(uid)).toBe(4);
    const marks = await ledger(uid);
    expect(marks.size).toBe(1);
    expect(marks.docs[0].data().expireAt).toBeInstanceOf(Timestamp);
  });

  test("dos entregas simultaneas del mismo evento: una sola bajada", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    const { event } = await seedDeleted(uid);
    await Promise.all([onRecordingDeletedHandler(deps(), event), onRecordingDeletedHandler(deps(), event)]);
    expect(await count(uid)).toBe(4);
  });

  test("otra INSTANCIA del doc (mismo id y generation, recreado por un finalize tardio, W5) si baja otra vez", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    const { event } = await seedDeleted(uid);
    await onRecordingDeletedHandler(deps(), { ...event, instance: "100.1" });
    await onRecordingDeletedHandler(deps(), { ...event, instance: "200.2" });
    expect(await count(uid)).toBe(3);
  });

  test("cuenta en borrado: ni baja ni deja marca bajo users/{uid}", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5, deletion: { state: "in_progress" } });
    await onRecordingDeletedHandler(deps(), (await seedDeleted(uid)).event);
    expect(await count(uid)).toBe(5);
    expect((await ledger(uid)).size).toBe(0);
  });
});

describe("reconciliacion frente a un trigger de borrado en vuelo (W2)", () => {
  /** Doc `ready` real (sin objeto): lo que la reconciliacion cuenta. */
  const seedReady = (uid: string) => recRef(uid, rnd("r")).set({ status: "ready", objectPath: "x", generation: "1" });

  test("la reconciliacion repara DESPUES del borrado (ya no lo cuenta): el trigger posterior NO vuelve a bajar", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 2 });
    await seedReady(uid);
    const { event } = await seedDeleted(uid); // el doc ya no existe: su trigger aun no ha corrido
    const deletedAt = new Date();
    await sleep(20);
    expect(await reconcileOwner(db, uid)).toBe("repaired");
    expect(await count(uid)).toBe(1);
    await onRecordingDeletedHandler(deps(), { ...event, deletedAt });
    expect(await count(uid)).toBe(1);
  });

  test("una reconciliacion ANTERIOR al borrado no impide la bajada", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 7 });
    await seedReady(uid);
    await seedReady(uid);
    expect(await reconcileOwner(db, uid)).toBe("repaired");
    await sleep(20);
    const { event } = await seedDeleted(uid);
    await onRecordingDeletedHandler(deps(), { ...event, deletedAt: new Date() });
    expect(await count(uid)).toBe(1);
  });
});

describe("cascada de reviews frente a un doc NUEVO con el mismo id (W4)", () => {
  test("un trigger retrasado no borra las reviews de un doc de otra generation creado despues", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 2 });
    const { event, id } = await seedDeleted(uid, {}, { id: rnd("w") });
    const next = await upload(uid, { id });
    await seedRecording(uid, next);
    await seedReview(uid, id);
    expect(await onRecordingDeletedHandler(deps(), event)).toBe("cleaned");
    expect(await recDoc(uid, id)).toMatchObject({ generation: next.generation });
    expect(await reviewCount(uid, id)).toBe(1);
  });

  test("sin doc nuevo, las reviews del doc borrado si caen", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 2 });
    const { event, id } = await seedDeleted(uid);
    await seedReview(uid, id);
    await onRecordingDeletedHandler(deps(), event);
    expect(await reviewCount(uid, id)).toBe(0);
  });
});
