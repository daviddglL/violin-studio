import { MAX_RECORDINGS_PER_OWNER } from "../../src/config/recordings";
import { bucketStore, finalizeRecordingHandler } from "../../src/recordings/finalize";
import { audioMp4 } from "../unit/helpers/mp4-builder";
import {
  bucket, db, exists, idOf, NOW, recDoc, rnd, seedAssignment, seedLink, seedTeacher, seedUser, upload, userDoc, wipeFirestore,
} from "./helpers/finalize-fixtures";

const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
const run = (ev: { name: string; generation: string }) =>
  finalizeRecordingHandler(
    { db, store: bucketStore(bucket), clock: () => NOW, flags, requiredConsentVersion: 1, log: () => undefined },
    ev,
  );
const count = async (uid: string) => (await userDoc(uid))?.recordingCount;

beforeEach(wipeFirestore);

describe("B4b.3 cupo de grabaciones (A21)", () => {
  test("el contador (ausente = 0) sube en la misma operacion que crea el doc ready", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    for (let i = 1; i <= 3; i++) {
      expect(await run(await upload(uid))).toBe("ready");
      expect(await count(uid)).toBe(i);
    }
  });

  test(`con ${MAX_RECORDINGS_PER_OWNER} ready la siguiente se rechaza (quota), el contador no cambia y el objeto se borra`, async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: MAX_RECORDINGS_PER_OWNER });
    const ev = await upload(uid);
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("quota");
    expect(await count(uid)).toBe(MAX_RECORDINGS_PER_OWNER);
    expect(await exists(ev.name)).toBe(false);
  });

  test("con 19 entra la ultima (20) y la siguiente ya no", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 19 });
    expect(await run(await upload(uid))).toBe("ready");
    expect(await count(uid)).toBe(20);
    expect(await run(await upload(uid))).toBe("rejected");
    expect(await count(uid)).toBe(20);
  });

  test("los rechazos por otra causa y los rejected NO cuentan", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    await run(await upload(uid, { buf: audioMp4(61) }));
    await run(await upload(uid, { contentType: "text/plain" }));
    expect(await count(uid)).toBeUndefined();
  });

  test("carrera por la ultima plaza: dos finalizaciones simultaneas con 19 -> una ready y otra quota; contador 20", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 19 });
    const [a, b] = await Promise.all([upload(uid), upload(uid)]);
    const outcomes = await Promise.all([run(a), run(b)]);
    expect(outcomes).toContain("ready");
    const docs = await Promise.all([recDoc(uid, idOf(a.name)), recDoc(uid, idOf(b.name))]);
    expect(docs.map((d) => d?.status).sort()).toEqual(["ready", "rejected"]);
    expect(docs.find((d) => d?.status === "rejected")?.rejectReason).toBe("quota");
    expect(await count(uid)).toBe(20);
    const gone = await Promise.all([exists(a.name), exists(b.name)]);
    expect(gone.filter(Boolean)).toHaveLength(1);
  });

  test("cinco simultaneas con 17 plazas ocupadas -> exactamente tres ready y dos quota", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 17 });
    const evs = await Promise.all([1, 2, 3, 4, 5].map(() => upload(uid)));
    await Promise.all(evs.map(run));
    const docs = await Promise.all(evs.map((e) => recDoc(uid, idOf(e.name))));
    expect(docs.filter((d) => d?.status === "ready")).toHaveLength(3);
    expect(docs.filter((d) => d?.rejectReason === "quota")).toHaveLength(2);
    expect(await count(uid)).toBe(20);
  });

  test("el mismo evento entregado varias veces en paralelo suma UNA sola vez", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    const ev = await upload(uid);
    await Promise.all([run(ev), run(ev), run(ev), run(ev)]);
    expect(await count(uid)).toBe(6);
    await run(ev);
    expect(await count(uid)).toBe(6);
  });

  test("contador corrupto (no numerico / negativo / no entero) falla cerrado: quota", async () => {
    for (const bad of ["x", -1, 1.5, NaN]) {
      const uid = rnd("s");
      await seedUser(uid, { recordingCount: bad });
      const ev = await upload(uid);
      expect(await run(ev)).toBe("rejected");
      expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("quota");
    }
  });

  test("profesor: cupo propio independiente del alumno, y sus demos cuentan en el del profesor", async () => {
    const t = rnd("t");
    const s = rnd("s");
    await seedTeacher(t);
    await seedUser(s, { recordingCount: MAX_RECORDINGS_PER_OWNER });
    await seedLink(t, s);
    await seedAssignment(s, "a1", { teacherUid: t });
    // el alumno lleno no puede, el profesor si (aunque la demo vaya dirigida a ese alumno)
    expect(await run(await upload(s))).toBe("rejected");
    const demo = await upload(t, { dir: "demos", meta: { sharedWith: s, assignmentId: "a1" } });
    expect(await run(demo)).toBe("ready");
    expect(await count(t)).toBe(1);
    expect(await count(s)).toBe(MAX_RECORDINGS_PER_OWNER);
    // y a la inversa: profesor lleno (grabaciones propias + demos comparten contador), alumno vacio
    const full = rnd("t");
    await seedTeacher(full, { recordingCount: MAX_RECORDINGS_PER_OWNER });
    const s2 = rnd("s");
    await seedUser(s2);
    await seedLink(full, s2);
    await seedAssignment(s2, "a1", { teacherUid: full });
    const blocked = await upload(full, { dir: "demos", meta: { sharedWith: s2, assignmentId: "a1" } });
    expect(await run(blocked)).toBe("rejected");
    expect((await recDoc(full, idOf(blocked.name)))?.rejectReason).toBe("quota");
    expect(await run(await upload(s2))).toBe("ready");
    expect(await count(s2)).toBe(1);
  });

  test("un conflicto de objectPath o un evento obsoleto no tocan el contador", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const id = rnd("r");
    const a = await upload(uid, { id });
    await run(a);
    await run(await upload(uid, { id, ext: "mp4" }));
    await run({ name: a.name, generation: "1" });
    expect(await count(uid)).toBe(1);
  });
});
