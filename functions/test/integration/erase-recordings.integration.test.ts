import { getApps } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { eraseUserData } from "../../src/erasure/erase-user-data";
import { bucketStore, ObjectStore } from "../../src/recordings/finalize";
import { bucket, db, exists, idOf, recDoc, recRef, reviewCount, rnd, seedAssignment, seedLink, seedRecording, seedReview, seedUser, upload, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

const auth = getAuth(getApps()[0]);
const store = bucketStore(bucket);
let logs: { message: string; data: Record<string, unknown> }[];
const sleep = async () => undefined;
const deps = (over: Record<string, unknown> = {}) => ({
  db, auth, bucket, store, sleep, log: (message: string, data: Record<string, unknown>) => void logs.push({ message, data }), ...over,
});
async function waitFor<T>(fn: () => Promise<T | undefined>, ms = 25000): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v !== undefined) return v;
    if (Date.now() > end) throw new Error("timeout");
    await new Promise((r) => setTimeout(r, 200));
  }
}

/** Demo de `t` para `s`: objeto en demos/ y doc en users/{t}/recordings con sharedWith. */
async function demo(t: string, s: string, id = rnd("d")) {
  const ev = await upload(t, { dir: "demos", id, meta: { sharedWith: s, assignmentId: "a1" } });
  const docId = await seedRecording(t, ev, { sharedWith: s, assignmentId: "a1" });
  return { ev, id: docId };
}
async function mk(uid: string, over: Record<string, unknown> = {}) {
  await seedUser(uid, over);
  await auth.createUser({ uid }).catch(() => undefined);
}

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
});

describe("borrar un alumno (REQ-DEL-T01)", () => {
  test("elimina las demos dirigidas a el (doc + objeto de demos/ + revisiones) y sus propias grabaciones; nada de otros", async () => {
    const [t, s, other] = [rnd("t"), rnd("s"), rnd("s")];
    await mk(t, { role: "teacher", recordingCount: 3 });
    await mk(s, { role: "student" });
    await seedLink(t, s);
    const d1 = await demo(t, s);
    const d2 = await demo(t, s);
    await seedReview(t, d1.id, "alguien");
    const forOther = await demo(t, other);
    const tOwn = await upload(t, { dir: "recordings" });
    const tOwnId = await seedRecording(t, tOwn);
    const sOwn = await upload(s);
    const sOwnId = await seedRecording(s, sOwn);
    await seedReview(s, sOwnId, t);

    const res = await eraseUserData(deps(), s, { deleteAuth: true });

    for (const d of [d1, d2]) {
      expect(await exists(d.ev.name)).toBe(false);
      expect(await recDoc(t, d.id)).toBeUndefined();
    }
    expect(await reviewCount(t, d1.id)).toBe(0);
    expect(await exists(forOther.ev.name)).toBe(true);
    expect(await recDoc(t, forOther.id)).toBeDefined();
    expect(await exists(tOwn.name)).toBe(true);
    expect(await recDoc(t, tOwnId)).toBeDefined();
    expect(await exists(sOwn.name)).toBe(false);
    expect(await userDoc(s)).toBeUndefined();
    expect((await db.collectionGroup("reviews").get()).docs.some((d) => d.ref.path.includes(`users/${s}/`))).toBe(false);
    expect(res.deleted.recordings).toBe(0); // las dos ya las borro `removeLink` (limpieza del vinculo)
    // el trigger real de borrado devuelve las plazas del cupo del profesor (2 demos `ready`)
    expect(await waitFor(async () => ((await userDoc(t))?.recordingCount === 1 ? 1 : undefined))).toBe(1);
  });

  test("demo sin vinculo vivo (red de seguridad `foreign`): la cascada la borra con su objeto de demos/ y la cuenta", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await mk(t, { role: "teacher" });
    await mk(s, { role: "student" });
    const d = await demo(t, s); // sin teacherLinks: `removeLink` no la ve
    const res = await eraseUserData(deps(), s, { deleteAuth: true });
    expect(res.deleted.recordings).toBe(1);
    expect(await exists(d.ev.name)).toBe(false);
    expect(await recDoc(t, d.id)).toBeUndefined();
  });

  test("demo con el mismo id que una grabacion propia del profesor: solo se borra el objeto de demos/ (objectPath almacenado)", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await mk(t, { role: "teacher" });
    await mk(s, { role: "student" });
    const d = await demo(t, s, "same");
    const sibling = await upload(t, { dir: "recordings", id: "same" });
    await eraseUserData(deps(), s, { deleteAuth: true });
    expect(await exists(d.ev.name)).toBe(false);
    expect(await exists(sibling.name)).toBe(true);
  });

  test("fallo transitorio del objeto: el paso se reintenta y termina; fallo persistente deja doc+objeto y la cuenta Auth, y una nueva ejecucion lo completa", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await mk(t, { role: "teacher" });
    await mk(s, { role: "student" });
    const d = await demo(t, s);
    let fails = 1;
    const flaky: ObjectStore = {
      ...store,
      remove: async (n, g) => {
        if (fails-- > 0) throw Object.assign(new Error(`boom ${n}`), { code: 503 });
        return store.remove(n, g);
      },
    };
    await eraseUserData(deps({ store: flaky }), s, { deleteAuth: true });
    expect(await recDoc(t, d.id)).toBeUndefined();

    const d2 = await demo(t, s);
    await mk(s, { role: "student" });
    const down: ObjectStore = { ...store, remove: async () => { throw Object.assign(new Error("down"), { code: 503 }); } };
    await expect(eraseUserData(deps({ store: down }), s, { deleteAuth: true })).rejects.toMatchObject({ code: 503 });
    expect(await recDoc(t, d2.id)).toBeDefined();
    expect(await exists(d2.ev.name)).toBe(true);
    expect((await auth.getUser(s)).uid).toBe(s); // Auth se borra al final: la cuenta sigue localizable para reintentar
    await eraseUserData(deps(), s, { deleteAuth: true });
    expect(await recDoc(t, d2.id)).toBeUndefined();
    expect(await exists(d2.ev.name)).toBe(false);
  });

  test("logs: solo uidHash, paso y contadores (sin uids, ids ni rutas)", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await mk(t, { role: "teacher" });
    await mk(s, { role: "student" });
    const d = await demo(t, s);
    await eraseUserData(deps(), s, { deleteAuth: true });
    const text = JSON.stringify(logs);
    for (const secret of [t, s, d.id, d.ev.name]) expect(text).not.toContain(secret);
  });
});

describe("borrar un profesor (REQ-DEL-T02, REQ-RVW-04)", () => {
  test("anonimiza sus revisiones (texto conservado, sin uid en ruta/campos), borra sus demos/grabaciones/tareas y deja intacto lo de los alumnos", async () => {
    const [t, s1, s2] = [rnd("t"), rnd("s"), rnd("s")];
    await mk(t, { role: "teacher" });
    await mk(s1, { role: "student", teacherCount: 1 });
    await mk(s2, { role: "student", teacherCount: 1 });
    await seedLink(t, s1);
    await seedLink(t, s2);
    await seedAssignment(s1, "a1", { teacherUid: t, status: "done" });
    const dm = await demo(t, s1);
    const own = await upload(t);
    const ownId = await seedRecording(t, own);
    const r1 = await upload(s1);
    const r1Id = await seedRecording(s1, r1);
    const r2 = await upload(s2);
    const r2Id = await seedRecording(s2, r2);
    for (const [s, id] of [[s1, r1Id], [s2, r2Id]] as const) await seedReview(s, id, t);
    await recRef(s1, r1Id).collection("reviews").doc(t).update({ text: "TextoDelProfe", verdict: "approved" });

    await eraseUserData(deps(), t, { deleteAuth: true });

    const reviews = (await db.collectionGroup("reviews").get()).docs;
    expect(reviews).toHaveLength(2);
    for (const d of reviews) {
      expect(d.ref.path).not.toContain(t);
      expect(JSON.stringify(d.data())).not.toContain(t);
      expect(d.data().reviewerUid).toBeNull();
    }
    expect((await recRef(s1, r1Id).collection("reviews").get()).docs[0].data()).toMatchObject({ text: "TextoDelProfe", verdict: "approved" });
    expect(await exists(r1.name)).toBe(true);
    expect(await exists(r2.name)).toBe(true);
    expect(await recDoc(s1, r1Id)).toBeDefined();
    expect(await exists(dm.ev.name)).toBe(false);
    expect(await exists(own.name)).toBe(false);
    expect(await recDoc(t, ownId)).toBeUndefined();
    expect((await db.collection("users").doc(s1).collection("assignments").doc("a1").get()).exists).toBe(false);
    expect((await userDoc(s1))?.role).toBe("independent");
    // sin rastro del uid en ninguna coleccion compartida
    for (const group of ["recordings", "reviews", "assignments", "teacherLinks"]) {
      for (const d of (await db.collectionGroup(group).get()).docs) {
        expect(d.ref.path).not.toContain(t);
        expect(JSON.stringify(d.data())).not.toContain(t);
      }
    }
  });

  test("repetir la cascada es un no-op y no duplica ni pierde revisiones", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await mk(t, { role: "teacher" });
    await mk(s, { role: "student", teacherCount: 1 });
    await seedLink(t, s);
    const r = await upload(s);
    const rid = await seedRecording(s, r);
    await seedReview(s, rid, t);
    await eraseUserData(deps(), t, { deleteAuth: true });
    await eraseUserData(deps(), t, { deleteAuth: true });
    expect(await reviewCount(s, rid)).toBe(1);
    expect(idOf(r.name)).toBe(rid);
  });
});
