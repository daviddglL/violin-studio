import { MAX_AUDIO_BYTES } from "../../src/config/recordings";
import {
  RecordingMeta,
  RecordingObject,
  RecordingOwner,
  ValidationContext,
  parseObjectPath,
  validateRecording,
} from "../../src/recordings/validate";

const ID = "3f2b8c1e-aaaa-4bbb-8ccc-1234567890ab";
const okContainer = (durationSec: number) => ({ ok: true as const, durationSec });

const student: RecordingOwner = {
  role: "student",
  isMinor: false,
  deleting: false,
  consentStatus: "granted",
  recordingConsent: "granted",
  recordingConsentVersion: 1,
  recordingConsentEpoch: 3,
};
const teacher: RecordingOwner = { ...student, role: "teacher", recordingConsentEpoch: 0 };
const ctx: ValidationContext = {
  recordingsEnabled: true,
  minorRecordingEnabled: true,
  requiredConsentVersion: 1,
  assignmentExists: true,
  teacherLinkExists: true,
};
const own = (over: Partial<RecordingObject> = {}): RecordingObject => ({
  path: `users/u1/recordings/${ID}.m4a`,
  contentType: "audio/mp4",
  size: 400_000,
  container: okContainer(20),
  ...over,
});
const demo = (over: Partial<RecordingObject> = {}): RecordingObject =>
  own({ path: `users/t1/demos/${ID}.m4a`, ...over });
const demoMeta: RecordingMeta = { sharedWith: "s1", assignmentId: "a1" };

describe("parseObjectPath", () => {
  test("grabacion propia y demo", () => {
    expect(parseObjectPath(`users/u1/recordings/${ID}.m4a`)).toEqual({ uid: "u1", dir: "recordings", id: ID, ext: "m4a" });
    expect(parseObjectPath(`users/t1/demos/${ID}.mp4`)).toEqual({ uid: "t1", dir: "demos", id: ID, ext: "mp4" });
  });

  test.each([
    "users/u1/other/x.m4a",
    "users/u1/recordings/sub/x.m4a",
    "users/u1/recordings/x.wav",
    "users/u1/recordings/.m4a",
    `users/u1/recordings/${"a".repeat(65)}.m4a`,
    "users/u1/recordings/bad_id.m4a",
    "x/u1/recordings/x.m4a",
    "users//recordings/x.m4a",
  ])("ruta ignorada/invalida: %s", (p) => {
    expect(parseObjectPath(p)).toBeNull();
  });
});

describe("validateRecording (REQ-REC-01, REQ-REC-05, REQ-REC-10)", () => {
  test("grabacion valida de alumno: ok con campos derivados", () => {
    expect(validateRecording({ kind: "audio" }, own(), student, ctx)).toEqual({
      ok: true,
      kind: "audio",
      dir: "recordings",
      durationSec: 20,
      sizeBytes: 400_000,
      consentEpoch: 3,
    });
  });

  test("meta sin kind tambien vale (la verdad es el contenedor)", () => {
    expect(validateRecording({}, own(), student, ctx).ok).toBe(true);
  });

  describe("duracion: 60 s alumno / 120 s profesor", () => {
    test.each([
      [60, true],
      [60.4, true], // tolerancia de contenedor
      [61, false],
      [90, false],
    ])("alumno %p s -> ok=%p", (d, ok) => {
      const r = validateRecording({}, own({ container: okContainer(d) }), student, ctx);
      expect(r.ok).toBe(ok);
      if (!r.ok) expect(r.reason).toBe("duration");
    });

    test.each([
      [120, true],
      [120.4, true],
      [121, false],
    ])("profesor (demo) %p s -> ok=%p", (d, ok) => {
      const r = validateRecording(demoMeta, demo({ container: okContainer(d) }), teacher, ctx);
      expect(r.ok).toBe(ok);
      if (!r.ok) expect(r.reason).toBe("duration");
    });

    test("un profesor grabando en su ruta propia tiene tambien 120 s", () => {
      expect(validateRecording({}, own({ container: okContainer(100) }), teacher, ctx).ok).toBe(true);
    });

    test("durationSec devuelto va redondeado", () => {
      const r = validateRecording({}, own({ container: okContainer(20.6) }), student, ctx);
      expect(r).toMatchObject({ ok: true, durationSec: 21 });
    });
  });

  describe("rejectReason por causa", () => {
    const reason = (
      meta: RecordingMeta,
      object: RecordingObject,
      owner: RecordingOwner,
      c: ValidationContext = ctx,
    ): string | "ok" => {
      const r = validateRecording(meta, object, owner, c);
      return r.ok ? "ok" : r.reason;
    };

    test("disabled: flag global apagado", () => {
      expect(reason({}, own(), student, { ...ctx, recordingsEnabled: false })).toBe("disabled");
    });

    test("disabled: menor con MINOR_RECORDING_ENABLED apagado; adulto no afectado", () => {
      const c = { ...ctx, minorRecordingEnabled: false };
      expect(reason({}, own(), { ...student, isMinor: true }, c)).toBe("disabled");
      expect(reason({}, own(), student, c)).toBe("ok");
    });

    test.each<[string, Partial<RecordingOwner>]>([
      ["consentimiento base no granted", { consentStatus: "revoked" }],
      ["consentimiento base ausente", { consentStatus: undefined }],
      ["recordingConsent revoked", { recordingConsent: "revoked" }],
      ["recordingConsent parental_pending", { recordingConsent: "parental_pending" }],
      ["recordingConsent ausente", { recordingConsent: undefined }],
      ["version de consentimiento antigua", { recordingConsentVersion: 0 }],
      ["version de consentimiento ausente", { recordingConsentVersion: undefined }],
      ["cuenta en borrado", { deleting: true }],
    ])("consent: %s", (_n, over) => {
      expect(reason({}, own(), { ...student, ...over })).toBe("consent");
    });

    test("version de consentimiento mayor o igual a la requerida vale", () => {
      expect(reason({}, own(), { ...student, recordingConsentVersion: 2 })).toBe("ok");
      expect(reason({}, own(), { ...student, recordingConsentVersion: 2 }, { ...ctx, requiredConsentVersion: 3 })).toBe(
        "consent",
      );
    });

    test.each([
      ["audio/wav", { contentType: "audio/wav" }],
      ["octet-stream", { contentType: "application/octet-stream" }],
      ["sin contentType", { contentType: undefined }],
      ["video/mp4 (4c)", { contentType: "video/mp4" }],
      ["extension mp4 en audio", { path: `users/u1/recordings/${ID}.mp4` }],
      ["extension wav", { path: `users/u1/recordings/${ID}.wav` }],
      ["nombre invalido", { path: "users/u1/recordings/bad_name.m4a" }],
    ] as Array<[string, Partial<RecordingObject>]>)("type: %s", (_n, over) => {
      expect(reason({}, own(over), student)).toBe("type");
    });

    test("type: meta.kind distinto de audio", () => {
      expect(reason({ kind: "video" }, own(), student)).toBe("type");
    });

    test("size: 0, 25 MiB + 1 byte; exactamente 25 MiB vale", () => {
      expect(reason({}, own({ size: 0 }), student)).toBe("size");
      expect(reason({}, own({ size: MAX_AUDIO_BYTES + 1 }), student)).toBe("size");
      expect(reason({}, own({ size: MAX_AUDIO_BYTES }), student)).toBe("ok");
    });

    test.each(["not_mp4", "truncated", "inconsistent", "no_moov", "no_mvhd", "too_many_reads"] as const)(
      "container: %s",
      (code) => {
        expect(reason({}, own({ container: { ok: false, code } }), student)).toBe("container");
      },
    );

    test("assignment: assignmentId que no existe", () => {
      expect(reason({ assignmentId: "a9" }, own(), student, { ...ctx, assignmentExists: false })).toBe("assignment");
      expect(reason({ assignmentId: "a9" }, own(), student, { ...ctx, assignmentExists: undefined })).toBe("assignment");
      expect(reason({ assignmentId: "a1" }, own(), student)).toBe("ok");
    });

    test("assignment: demo sin assignmentId", () => {
      expect(reason({ sharedWith: "s1" }, demo(), teacher)).toBe("assignment");
    });

    test("prioridad: consent gana a duracion, tipo gana a tamano", () => {
      expect(reason({}, own({ container: okContainer(500) }), { ...student, recordingConsent: "none" })).toBe("consent");
      expect(reason({}, own({ contentType: "audio/wav", size: 0 }), student)).toBe("type");
    });
  });

  describe("sharedWith solo profesor (REQ-REC-10)", () => {
    const r = (meta: RecordingMeta, object: RecordingObject, owner: RecordingOwner, c = ctx) =>
      validateRecording(meta, object, owner, c);

    test("grabacion propia con sharedWith: rechazada (alumno y profesor)", () => {
      expect(r({ sharedWith: "s1" }, own(), student)).toEqual({ ok: false, reason: "sharedWith" });
      expect(r({ sharedWith: "s1" }, own(), teacher)).toEqual({ ok: false, reason: "sharedWith" });
    });

    test("demo de alumno (ruta demos sin ser profesor): sharedWith", () => {
      expect(r(demoMeta, demo(), student)).toEqual({ ok: false, reason: "sharedWith" });
    });

    test("demo sin sharedWith: sharedWith", () => {
      expect(r({ assignmentId: "a1" }, demo(), teacher)).toEqual({ ok: false, reason: "sharedWith" });
    });

    test("demo sin vinculo con el destinatario: sharedWith", () => {
      expect(r(demoMeta, demo(), teacher, { ...ctx, teacherLinkExists: false })).toEqual({
        ok: false,
        reason: "sharedWith",
      });
      expect(r(demoMeta, demo(), teacher, { ...ctx, teacherLinkExists: undefined })).toEqual({
        ok: false,
        reason: "sharedWith",
      });
    });

    test("demo valida de profesor: ok con sharedWith y assignmentId", () => {
      expect(r(demoMeta, demo({ container: okContainer(100) }), teacher)).toEqual({
        ok: true,
        kind: "audio",
        dir: "demos",
        durationSec: 100,
        sizeBytes: 400_000,
        consentEpoch: 0,
        sharedWith: "s1",
        assignmentId: "a1",
      });
    });

    test("destinatario igual al autor: sharedWith", () => {
      expect(r({ sharedWith: "t1", assignmentId: "a1" }, demo(), teacher)).toEqual({ ok: false, reason: "sharedWith" });
    });
  });

  test("ruta fuera de recordings/demos no se valida: type", () => {
    expect(validateRecording({}, own({ path: "users/u1/avatars/x.m4a" }), student, ctx)).toEqual({
      ok: false,
      reason: "type",
    });
  });
});
