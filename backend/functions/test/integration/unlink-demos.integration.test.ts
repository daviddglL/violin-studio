import { getAuth } from "firebase-admin/auth";
import { getApps } from "firebase-admin/app";
import { bucketStore, ObjectStore } from "../../src/recordings/finalize";
import { removeLink } from "../../src/teacher/remove-link";
import { bucket, db, exists, recDoc, rnd, seedAssignment, seedLink, seedRecording, seedReview, seedUser, upload, wipeFirestore } from "./helpers/finalize-fixtures";

const auth = getAuth(getApps()[0]);
const store = bucketStore(bucket);
let logs: { message: string; data: Record<string, unknown> }[];
const deps = (over: Record<string, unknown> = {}) => ({ db, auth, store, log: (message: string, data: Record<string, unknown>) => void logs.push({ message, data }), ...over });

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
});

/** Demo de `t` para `s`: objeto en demos/ y doc en users/{t}/recordings con sharedWith. */
async function demo(t: string, s: string, id = rnd("d")) {
  const ev = await upload(t, { dir: "demos", id, meta: { sharedWith: s, assignmentId: "a1" } });
  await seedRecording(t, ev, { sharedWith: s, assignmentId: "a1" });
  return ev;
}

describe("removeLink borra las demostraciones del profesor para ese alumno (B5b.4, REQ-REC-10)", () => {
  test("borra objeto (demos/) y doc de las demos dirigidas al alumno; no toca otras demos, grabaciones propias ni nada del alumno", async () => {
    const [t, s, other] = [rnd("t"), rnd("s"), rnd("s")];
    await seedUser(t, { role: "teacher" });
    await seedUser(s, { role: "student", teacherCount: 1 });
    await seedLink(t, s);
    const d1 = await demo(t, s);
    const d2 = await demo(t, s);
    const forOther = await demo(t, other);
    const own = await upload(t, { dir: "recordings" });
    await seedRecording(t, own);
    // el alumno tiene una grabacion con una revision de ese profesor: no se toca
    const mine = await upload(s, { dir: "recordings" });
    const mineId = await seedRecording(s, mine);
    await seedReview(s, mineId, t);
    await seedAssignment(s, "a1", { teacherUid: t, status: "done" });

    await removeLink(deps(), t, s);

    for (const d of [d1, d2]) {
      expect(await exists(d.name)).toBe(false);
      expect(await recDoc(t, d.name.split("/").pop()!.replace(".m4a", ""))).toBeUndefined();
    }
    expect(await exists(forOther.name)).toBe(true);
    expect(await exists(own.name)).toBe(true);
    expect(await exists(mine.name)).toBe(true);
    expect((await db.collection("users").doc(s).collection("recordings").doc(mineId).collection("reviews").get()).size).toBe(1);
    expect((await db.collection("users").doc(s).collection("assignments").doc("a1").get()).exists).toBe(true); // `done` se conserva (A6)
  });

  test("demo con el MISMO id que una grabacion propia: se borra el objeto de demos/, no el de recordings/ (objectPath almacenado)", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await seedUser(t, { role: "teacher" });
    await seedLink(t, s);
    const d = await demo(t, s, "same");
    const sibling = await upload(t, { dir: "recordings", id: "same" });
    await removeLink(deps(), t, s);
    expect(await exists(d.name)).toBe(false);
    expect(await exists(sibling.name)).toBe(true);
  });

  test("las tareas abiertas siguen borrandose (los pasos se componen con el defecto)", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await seedUser(t, { role: "teacher" });
    await seedLink(t, s);
    await seedAssignment(s, "open1", { teacherUid: t, status: "open" });
    await demo(t, s);
    await removeLink(deps(), t, s);
    expect((await db.collection("users").doc(s).collection("assignments").doc("open1").get()).exists).toBe(false);
  });

  test("fallo del objeto: el doc sigue y el reintento completa (reanudable); sin PII en logs", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await seedUser(t, { role: "teacher" });
    await seedUser(s, { role: "student", teacherCount: 1 });
    await seedLink(t, s);
    const d = await demo(t, s);
    const id = d.name.split("/").pop()!.replace(".m4a", "");
    const flaky: ObjectStore = { ...store, remove: async () => { throw Object.assign(new Error(`boom ${d.name}`), { code: 503 }); } };
    await expect(removeLink(deps({ store: flaky }), t, s)).rejects.toMatchObject({ code: 503 });
    expect(await recDoc(t, id)).toBeDefined();
    expect(await exists(d.name)).toBe(true);
    await removeLink(deps(), t, s); // el vinculo ya no existe pero la marca `pendingUnlinks` reanuda la limpieza
    expect(await recDoc(t, id)).toBeUndefined();
    expect(await exists(d.name)).toBe(false);
    const text = JSON.stringify(logs);
    expect(text).not.toContain(t);
    expect(text).not.toContain(s);
    expect(text).not.toContain(id);
  });

  test("sin demos para ese alumno es un no-op (idempotente)", async () => {
    const [t, s] = [rnd("t"), rnd("s")];
    await seedUser(t, { role: "teacher" });
    await seedLink(t, s);
    await removeLink(deps(), t, s);
    await removeLink(deps(), t, s);
  });
});
