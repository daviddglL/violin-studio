import { Timestamp } from "firebase-admin/firestore";
import { bucketStore, finalizeRecordingHandler } from "../../src/recordings/finalize";
import { audioMp4, box, ftyp } from "../unit/helpers/mp4-builder";
import {
  bucket, DAY, db, exists, idOf, NOW, recDoc, rnd, seedAssignment, seedLink, seedTeacher, seedUser, upload, wipeFirestore,
} from "./helpers/finalize-fixtures";

const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
const deps = (over: Record<string, unknown> = {}) => ({
  db,
  store: bucketStore(bucket),
  clock: () => NOW,
  flags,
  requiredConsentVersion: 1,
  log: () => undefined,
  ...over,
});
const run = (ev: { name: string; generation: string }, over: Record<string, unknown> = {}) =>
  finalizeRecordingHandler(deps(over), ev);

beforeEach(wipeFirestore);

describe("B4b.1 finalizeRecording: valido y rechazos", () => {
  test("grabacion valida -> doc ready con campos cerrados y el objeto se conserva", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid, { buf: audioMp4(30) });
    expect(await run(ev)).toBe("ready");
    expect(await recDoc(uid, idOf(ev.name))).toEqual({
      kind: "audio",
      status: "ready",
      durationSec: 30,
      sizeBytes: expect.any(Number),
      createdAt: Timestamp.fromDate(NOW),
      expiresAt: Timestamp.fromMillis(NOW.getTime() + 90 * DAY),
      objectPath: ev.name,
      generation: ev.generation,
      recordingConsentEpoch: 0,
    });
    expect(await exists(ev.name)).toBe(true);
  });

  test.each([
    ["sin consentimiento de grabacion", { recordingConsent: "revoked" }, undefined, "consent"],
    ["consentimiento base no vigente", { consentStatus: "revoked" }, undefined, "consent"],
    ["version de consentimiento antigua", { recordingConsentVersion: 0 }, undefined, "consent"],
    ["RECORDINGS_ENABLED=false", {}, { flags: { ...flags, recordingsEnabled: false } }, "disabled"],
  ])("%s -> objeto borrado + doc rejected (+7 d)", async (_n, userOver, depsOver, reason) => {
    const uid = rnd("s");
    await seedUser(uid, userOver);
    const ev = await upload(uid);
    expect(await run(ev, depsOver)).toBe("rejected");
    expect(await recDoc(uid, idOf(ev.name))).toEqual({
      status: "rejected",
      rejectReason: reason,
      createdAt: Timestamp.fromDate(NOW),
      expiresAt: Timestamp.fromMillis(NOW.getTime() + 7 * DAY),
      objectPath: ev.name,
      generation: ev.generation,
    });
    expect(await exists(ev.name)).toBe(false);
  });

  test.each([
    ["contentType falso", { contentType: "text/plain" }, "type"],
    ["no es un MP4 aunque diga audio/mp4", { buf: Buffer.from("esto no es un mp4, es texto plano") }, "container"],
    ["61 s (R4)", { buf: audioMp4(61) }, "duration"],
    ["121 s (R4)", { buf: audioMp4(121) }, "duration"],
    ["truncado (moov declara mas de lo que hay)", { buf: Buffer.concat([ftyp(), box("moov", Buffer.alloc(40)).subarray(0, 20)]) }, "container"],
  ])("%s -> rejected", async (_n, up, reason) => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid, up);
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe(reason);
    expect(await exists(ev.name)).toBe(false);
  });

  test("profesor: 121 s se rechaza pero 90 s se acepta (limite por rol)", async () => {
    const t = rnd("t");
    await seedTeacher(t);
    expect(await run(await upload(t, { buf: audioMp4(90) }))).toBe("ready");
    expect(await run(await upload(t, { buf: audioMp4(121) }))).toBe("rejected");
  });

  test("grabacion propia con assignmentId existente -> ready con assignmentId; inexistente -> rejected(assignment)", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    await seedAssignment(uid, "a1");
    const ok = await upload(uid, { meta: { assignmentId: "a1" } });
    expect(await run(ok)).toBe("ready");
    expect((await recDoc(uid, idOf(ok.name)))?.assignmentId).toBe("a1");
    const bad = await upload(uid, { meta: { assignmentId: "nope" } });
    expect(await run(bad)).toBe("rejected");
    expect((await recDoc(uid, idOf(bad.name)))?.rejectReason).toBe("assignment");
  });

  test("sharedWith en una grabacion de alumno (ruta recordings/) -> rejected(sharedWith)", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid, { meta: { sharedWith: rnd("x") } });
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe("sharedWith");
  });
});

describe("4c finalizeRecording: video difuminado (.mp4)", () => {
  const video = { ext: "mp4", contentType: "video/mp4", meta: { kind: "video", blurApplied: "true", blurPolicyVersion: "1" } };
  const on = { flags: { ...flags, videoEnabled: true } };

  test("video sellado con VIDEO_ENABLED -> doc ready kind video con blurPolicyVersion", async () => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid, video);
    expect(await run(ev, on)).toBe("ready");
    expect(await recDoc(uid, idOf(ev.name))).toMatchObject({ kind: "video", status: "ready", durationSec: 30, blurPolicyVersion: 1 });
    expect(await exists(ev.name)).toBe(true);
  });

  test.each([
    ["VIDEO_ENABLED apagado", video, undefined, "disabled"],
    ["sin sello de difuminado", { ...video, meta: { kind: "video" } }, on, "blur"],
    ["version de sello distinta", { ...video, meta: { ...video.meta, blurPolicyVersion: "2" } }, on, "blur"],
    ["metadatos de audio en un .mp4", { ...video, meta: { kind: "audio" } }, on, "type"],
  ])("%s -> rejected", async (_n, up, depsOver, reason) => {
    const uid = rnd("s");
    await seedUser(uid);
    const ev = await upload(uid, up);
    expect(await run(ev, depsOver)).toBe("rejected");
    expect((await recDoc(uid, idOf(ev.name)))?.rejectReason).toBe(reason);
    expect(await exists(ev.name)).toBe(false);
  });
});

describe("B4b.1 demos del profesor (prefijo demos/, W8)", () => {
  async function demo(over: { linked?: boolean; assignment?: "own" | "other-teacher" | "other-student" | "none" } = {}) {
    const t = rnd("t");
    const s = rnd("s");
    await seedTeacher(t);
    await seedUser(s);
    if (over.linked !== false) await seedLink(t, s);
    const kind = over.assignment ?? "own";
    if (kind === "own") await seedAssignment(s, "as1", { teacherUid: t });
    if (kind === "other-teacher") await seedAssignment(s, "as1", { teacherUid: rnd("t") });
    if (kind === "other-student") await seedAssignment(rnd("s"), "as1", { teacherUid: t });
    const ev = await upload(t, { dir: "demos", meta: { sharedWith: s, assignmentId: "as1" } });
    return { t, s, ev };
  }

  test("demo valida: doc ready en users/{t}/recordings/{id} con sharedWith, assignmentId y objectPath de demos/", async () => {
    const { t, s, ev } = await demo();
    expect(await run(ev)).toBe("ready");
    expect(await recDoc(t, idOf(ev.name))).toMatchObject({ status: "ready", sharedWith: s, assignmentId: "as1", objectPath: ev.name });
    expect(ev.name).toContain("/demos/");
  });

  test.each([
    ["assignment de OTRO profesor", { assignment: "other-teacher" as const }, "assignment"],
    ["assignment de OTRO alumno", { assignment: "other-student" as const }, "assignment"],
    ["assignment inexistente", { assignment: "none" as const }, "assignment"],
    ["sharedWith sin vinculo", { linked: false }, "sharedWith"],
  ])("%s -> rejected y objeto de demos/ borrado", async (_n, over, reason) => {
    const { t, ev } = await demo(over);
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(t, idOf(ev.name)))?.rejectReason).toBe(reason);
    expect(await exists(ev.name)).toBe(false);
  });

  test("demo de un alumno (no profesor) -> rejected(sharedWith)", async () => {
    const s = rnd("s");
    await seedUser(s);
    await seedUser(rnd("s"));
    const ev = await upload(s, { dir: "demos", meta: { sharedWith: rnd("s"), assignmentId: "x" } });
    expect(await run(ev)).toBe("rejected");
    expect((await recDoc(s, idOf(ev.name)))?.rejectReason).toBe("sharedWith");
  });
});
