import {
  MAX_AUDIO_BYTES,
  MAX_BITRATE_BPS,
  MAX_VIDEO_BYTES,
  MIN_DURATION_SEC,
} from "../../src/config/recordings";
import {
  RecordingMeta,
  RecordingObject,
  RecordingOwner,
  ValidationContext,
  parseObjectPath,
  validateRecording,
} from "../../src/recordings/validate";

const ID = "3f2b8c1e-aaaa-4bbb-8ccc-1234567890ab";
const okContainer = (durationSec: number, brand = "isom") => ({
  ok: true as const,
  durationSec,
  brand,
});

const student: RecordingOwner = {
  ownerUid: "u1",
  role: "student",
  isMinor: false,
  deleting: false,
  consentStatus: "granted",
  recordingConsent: "granted",
  recordingConsentVersion: 1,
  recordingConsentEpoch: 3,
};
const teacher: RecordingOwner = {
  ...student,
  ownerUid: "t1",
  role: "teacher",
  recordingConsentEpoch: 0,
};
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
    expect(parseObjectPath(`users/u1/recordings/${ID}.m4a`)).toEqual({
      uid: "u1",
      dir: "recordings",
      id: ID,
      ext: "m4a",
    });
    expect(parseObjectPath(`users/t1/demos/${ID}.mp4`)).toEqual({
      uid: "t1",
      dir: "demos",
      id: ID,
      ext: "mp4",
    });
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
    // C1: el punto es literal, no "cualquier caracter"
    "users/u1/recordings/abc-m4a",
    "users/u1/recordings/abc/m4a",
    "users/u1/recordings/abcXm4a",
    "users/u1/recordings/abc.m4a.m4a",
    "users/u1/recordings/abc.mp4.m4a",
    "users/u1/recordings/abc.m4a\n",
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
      const r = validateRecording(
        {},
        own({ container: okContainer(d) }),
        student,
        ctx,
      );
      expect(r.ok).toBe(ok);
      if (!r.ok) expect(r.reason).toBe("duration");
    });

    test.each([
      [120, true],
      [120.4, true],
      [121, false],
    ])("profesor (demo) %p s -> ok=%p", (d, ok) => {
      const r = validateRecording(
        demoMeta,
        demo({ container: okContainer(d) }),
        teacher,
        ctx,
      );
      expect(r.ok).toBe(ok);
      if (!r.ok) expect(r.reason).toBe("duration");
    });

    test("un profesor grabando en su ruta propia tiene tambien 120 s", () => {
      expect(
        validateRecording(
          {},
          own({
            path: `users/t1/recordings/${ID}.m4a`,
            container: okContainer(100),
          }),
          teacher,
          ctx,
        ).ok,
      ).toBe(true);
    });

    test.each([0, 0.1, MIN_DURATION_SEC - 0.01, -5, NaN, Infinity])(
      "duracion invalida/diminuta %p: duration (W2)",
      (d) => {
        expect(
          validateRecording(
            {},
            own({ container: okContainer(d), size: 1000 }),
            student,
            ctx,
          ),
        ).toEqual({
          ok: false,
          reason: "duration",
        });
      },
    );

    test("duracion minima exacta vale", () => {
      expect(
        validateRecording(
          {},
          own({ container: okContainer(MIN_DURATION_SEC), size: 1000 }),
          student,
          ctx,
        ).ok,
      ).toBe(true);
    });

    test("MP4 fragmentado (mvhd con duracion 0): duration (W2, no soportado)", () => {
      expect(
        validateRecording(
          {},
          own({ container: okContainer(0, "iso5") }),
          student,
          { ...ctx },
        ),
      ).toMatchObject({
        ok: false,
      });
    });

    test("tasa de bits imposible (25 MB en 1 s): duration (W1)", () => {
      const r = validateRecording(
        {},
        own({ size: MAX_AUDIO_BYTES, container: okContainer(1) }),
        student,
        ctx,
      );
      expect(r).toEqual({ ok: false, reason: "duration" });
    });

    test("tasa normal (1 MB en 60 s) vale; el limite exacto vale y el siguiente byte no (W1)", () => {
      expect(
        validateRecording(
          {},
          own({ size: 1_000_000, container: okContainer(60) }),
          student,
          ctx,
        ).ok,
      ).toBe(true);
      const atLimit = (MAX_BITRATE_BPS * 10) / 8; // 10 s
      expect(
        validateRecording(
          {},
          own({ size: atLimit, container: okContainer(10) }),
          student,
          ctx,
        ).ok,
      ).toBe(true);
      expect(
        validateRecording(
          {},
          own({ size: atLimit + 1, container: okContainer(10) }),
          student,
          ctx,
        ),
      ).toEqual({
        ok: false,
        reason: "duration",
      });
    });

    test("la duracion minima de 1 s en el denominador acota los audios cortos (0.5 s)", () => {
      const max = MAX_BITRATE_BPS / 8; // bytes permitidos con denominador 1 s
      expect(
        validateRecording(
          {},
          own({ size: max, container: okContainer(0.5) }),
          student,
          ctx,
        ).ok,
      ).toBe(true);
      expect(
        validateRecording(
          {},
          own({ size: max + 1, container: okContainer(0.5) }),
          student,
          ctx,
        ).ok,
      ).toBe(false);
    });

    test("durationSec devuelto se acota al limite del rol (W4)", () => {
      expect(
        validateRecording(
          {},
          own({ container: okContainer(60.4) }),
          student,
          ctx,
        ),
      ).toMatchObject({ durationSec: 60 });
      expect(
        validateRecording(
          {},
          own({ container: okContainer(60.5) }),
          student,
          ctx,
        ),
      ).toMatchObject({ durationSec: 60 });
      expect(
        validateRecording(
          demoMeta,
          demo({ container: okContainer(120.5) }),
          teacher,
          ctx,
        ),
      ).toMatchObject({
        durationSec: 120,
      });
    });

    test("durationSec devuelto va redondeado", () => {
      const r = validateRecording(
        {},
        own({ container: okContainer(20.6) }),
        student,
        ctx,
      );
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
      expect(
        reason({}, own(), student, { ...ctx, recordingsEnabled: false }),
      ).toBe("disabled");
    });

    test("disabled: menor con MINOR_RECORDING_ENABLED apagado; adulto no afectado", () => {
      const c = { ...ctx, minorRecordingEnabled: false };
      expect(reason({}, own(), { ...student, isMinor: true }, c)).toBe(
        "disabled",
      );
      expect(reason({}, own(), student, c)).toBe("ok");
    });

    test.each<[string, Partial<RecordingOwner>]>([
      ["consentimiento base no granted", { consentStatus: "revoked" }],
      ["consentimiento base ausente", { consentStatus: undefined }],
      ["recordingConsent revoked", { recordingConsent: "revoked" }],
      [
        "recordingConsent parental_pending",
        { recordingConsent: "parental_pending" },
      ],
      ["recordingConsent ausente", { recordingConsent: undefined }],
      ["version de consentimiento antigua", { recordingConsentVersion: 0 }],
      [
        "version de consentimiento ausente",
        { recordingConsentVersion: undefined },
      ],
      ["cuenta en borrado", { deleting: true }],
    ])("consent: %s", (_n, over) => {
      expect(reason({}, own(), { ...student, ...over })).toBe("consent");
    });

    test("version de consentimiento mayor o igual a la requerida vale", () => {
      expect(
        reason({}, own(), { ...student, recordingConsentVersion: 2 }),
      ).toBe("ok");
      expect(
        reason(
          {},
          own(),
          { ...student, recordingConsentVersion: 2 },
          { ...ctx, requiredConsentVersion: 3 },
        ),
      ).toBe("consent");
    });

    test.each([
      ["audio/wav", { contentType: "audio/wav" }],
      ["octet-stream", { contentType: "application/octet-stream" }],
      ["sin contentType", { contentType: undefined }],
      ["video/mp4 (4c)", { contentType: "video/mp4" }],
      ["extension wav", { path: `users/u1/recordings/${ID}.wav` }],
      ["nombre invalido", { path: "users/u1/recordings/bad_name.m4a" }],
    ] as Array<[string, Partial<RecordingObject>]>)("type: %s", (_n, over) => {
      expect(reason({}, own(over), student)).toBe("type");
    });

    test("type: meta.kind distinto de audio", () => {
      expect(reason({ kind: "video" }, own(), student)).toBe("type");
    });

    test("size: 0, 25 MiB + 1 byte; exactamente 25 MiB no es 'size' (la tasa de bits lo acota antes en la practica)", () => {
      expect(reason({}, own({ size: 0 }), student)).toBe("size");
      expect(reason({}, own({ size: MAX_AUDIO_BYTES + 1 }), student)).toBe(
        "size",
      );
      expect(reason({}, own({ size: MAX_AUDIO_BYTES }), student)).toBe(
        "duration",
      );
    });

    test.each([
      "not_mp4",
      "truncated",
      "inconsistent",
      "no_moov",
      "no_mvhd",
      "too_many_reads",
    ] as const)("container: %s", (code) => {
      expect(reason({}, own({ container: { ok: false, code } }), student)).toBe(
        "container",
      );
    });

    test("assignment: assignmentId que no existe", () => {
      expect(
        reason({ assignmentId: "a9" }, own(), student, {
          ...ctx,
          assignmentExists: false,
        }),
      ).toBe("assignment");
      expect(
        reason({ assignmentId: "a9" }, own(), student, {
          ...ctx,
          assignmentExists: undefined,
        }),
      ).toBe("assignment");
      expect(reason({ assignmentId: "a1" }, own(), student)).toBe("ok");
    });

    test("assignment: demo sin assignmentId", () => {
      expect(reason({ sharedWith: "s1" }, demo(), teacher)).toBe("assignment");
    });

    test.each(["isom", "iso2", "mp41", "mp42", "M4A ", "M4B "])(
      "marca %p permitida (W5)",
      (brand) => {
        expect(
          reason({}, own({ container: okContainer(20, brand) }), student),
        ).toBe("ok");
      },
    );

    test.each(["qt  ", "3gp4", "avif", "heic", "", "isoM"])(
      "container: marca %p no permitida (W5)",
      (brand) => {
        expect(
          reason({}, own({ container: okContainer(20, brand) }), student),
        ).toBe("container");
      },
    );

    test("owner: el uid de la ruta no es el del propietario leido (W7)", () => {
      expect(
        reason({}, own({ path: `users/other/recordings/${ID}.m4a` }), student),
      ).toBe("owner");
      expect(
        reason({}, demo({ path: `users/other/demos/${ID}.m4a` }), teacher),
      ).toBe("owner");
      expect(
        reason({}, own(), {
          ...student,
          ownerUid: undefined as unknown as string,
        }),
      ).toBe("owner");
    });

    test("owner va despues de type y antes de size/container", () => {
      expect(
        reason({}, own({ path: `users/other/recordings/${ID}.wav` }), student),
      ).toBe("type");
      expect(
        reason(
          {},
          own({ path: `users/other/recordings/${ID}.m4a`, size: 0 }),
          student,
        ),
      ).toBe("owner");
      expect(
        reason(
          {},
          own({
            path: `users/other/recordings/${ID}.m4a`,
            container: { ok: false, code: "no_moov" },
          }),
          student,
        ),
      ).toBe("owner");
    });

    test.each([NaN, Infinity * 0])(
      "consent: version %p no finita falla (W9)",
      (v) => {
        expect(
          reason({}, own(), { ...student, recordingConsentVersion: v }),
        ).toBe("consent");
      },
    );

    test("consent: requiredConsentVersion NaN falla cerrado (W9)", () => {
      expect(
        reason({}, own(), student, { ...ctx, requiredConsentVersion: NaN }),
      ).toBe("consent");
    });

    test("prioridad: consent gana a duracion, tipo gana a tamano", () => {
      expect(
        reason({}, own({ container: okContainer(500) }), {
          ...student,
          recordingConsent: "none",
        }),
      ).toBe("consent");
      expect(
        reason({}, own({ contentType: "audio/wav", size: 0 }), student),
      ).toBe("type");
    });
  });

  describe("sharedWith solo profesor (REQ-REC-10)", () => {
    const r = (
      meta: RecordingMeta,
      object: RecordingObject,
      owner: RecordingOwner,
      c = ctx,
    ) => validateRecording(meta, object, owner, c);

    test("grabacion propia con sharedWith: rechazada (alumno y profesor)", () => {
      expect(r({ sharedWith: "s1" }, own(), student)).toEqual({
        ok: false,
        reason: "sharedWith",
      });
      expect(
        r(
          { sharedWith: "s1" },
          own({ path: `users/t1/recordings/${ID}.m4a` }),
          teacher,
        ),
      ).toEqual({ ok: false, reason: "sharedWith" });
    });

    test("demo de alumno (ruta demos sin ser profesor): sharedWith", () => {
      expect(
        r(demoMeta, demo({ path: `users/u1/demos/${ID}.m4a` }), student),
      ).toEqual({ ok: false, reason: "sharedWith" });
    });

    test("demo sin sharedWith: sharedWith", () => {
      expect(r({ assignmentId: "a1" }, demo(), teacher)).toEqual({
        ok: false,
        reason: "sharedWith",
      });
    });

    test("demo sin vinculo con el destinatario: sharedWith", () => {
      expect(
        r(demoMeta, demo(), teacher, { ...ctx, teacherLinkExists: false }),
      ).toEqual({
        ok: false,
        reason: "sharedWith",
      });
      expect(
        r(demoMeta, demo(), teacher, { ...ctx, teacherLinkExists: undefined }),
      ).toEqual({
        ok: false,
        reason: "sharedWith",
      });
    });

    test("demo valida de profesor: ok con sharedWith y assignmentId", () => {
      expect(
        r(demoMeta, demo({ container: okContainer(100) }), teacher),
      ).toEqual({
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
      expect(
        r({ sharedWith: "t1", assignmentId: "a1" }, demo(), teacher),
      ).toEqual({ ok: false, reason: "sharedWith" });
    });
  });

  test("ruta fuera de recordings/demos no se valida: type", () => {
    expect(
      validateRecording(
        {},
        own({ path: "users/u1/avatars/x.m4a" }),
        student,
        ctx,
      ),
    ).toEqual({
      ok: false,
      reason: "type",
    });
  });

  describe("video difuminado (4c)", () => {
    const vctx: ValidationContext = { ...ctx, videoEnabled: true };
    const sealed: RecordingMeta = {
      kind: "video",
      blurApplied: "true",
      blurPolicyVersion: "1",
    };
    const video = (over: Partial<RecordingObject> = {}): RecordingObject =>
      own({
        path: `users/u1/recordings/${ID}.mp4`,
        contentType: "video/mp4",
        size: 8_000_000,
        ...over,
      });
    const reason = (
      meta: RecordingMeta,
      object: RecordingObject,
      owner: RecordingOwner = student,
      c: ValidationContext = vctx,
    ): string => {
      const r = validateRecording(meta, object, owner, c);
      return r.ok ? "ok" : r.reason;
    };

    test("sellado y con el interruptor encendido: ready como video", () => {
      expect(validateRecording(sealed, video(), student, vctx)).toEqual({
        ok: true,
        kind: "video",
        dir: "recordings",
        durationSec: 20,
        sizeBytes: 8_000_000,
        consentEpoch: 3,
      });
    });

    test("demo de video del profesor", () => {
      const r = validateRecording(
        { ...sealed, ...demoMeta },
        video({ path: `users/t1/demos/${ID}.mp4` }),
        teacher,
        vctx,
      );
      expect(r).toMatchObject({ ok: true, kind: "video", dir: "demos" });
    });

    test("disabled: VIDEO_ENABLED apagado o ausente; el audio sigue igual", () => {
      expect(reason(sealed, video(), student, { ...vctx, videoEnabled: false })).toBe("disabled");
      expect(reason(sealed, video(), student, ctx)).toBe("disabled");
      expect(reason({}, own(), student, ctx)).toBe("ok");
    });

    test("blur: sin sello, sello falso o version distinta", () => {
      expect(reason({ kind: "video" }, video())).toBe("blur");
      expect(reason({ ...sealed, blurApplied: "false" }, video())).toBe("blur");
      expect(reason({ ...sealed, blurPolicyVersion: "2" }, video())).toBe("blur");
      expect(reason({ ...sealed, blurPolicyVersion: undefined }, video())).toBe("blur");
    });

    test.each([
      ["contentType de audio", {}, { contentType: "audio/mp4" }],
      ["kind ausente", { kind: undefined }, {}],
      ["kind audio", { kind: "audio" }, {}],
    ] as Array<[string, Partial<RecordingMeta>, Partial<RecordingObject>]>)(
      "type: %s en un .mp4",
      (_n, meta, over) => {
        expect(reason({ ...sealed, ...meta }, video(over))).toBe("type");
      },
    );

    test("type: un .m4a con contentType de video sigue siendo type", () => {
      expect(reason(sealed, own({ contentType: "video/mp4" }))).toBe("type");
    });

    test("size: hasta 60 MiB; un video no cabe en el tope de audio pero si en el suyo", () => {
      expect(reason(sealed, video({ size: MAX_VIDEO_BYTES + 1 }))).toBe("size");
      expect(
        reason(sealed, video({ size: MAX_AUDIO_BYTES + 1, container: okContainer(60) })),
      ).toBe("ok");
    });

    test("duration: la tasa de bits de video es 10 Mbps", () => {
      // 60 MiB en 30 s ~ 16.8 Mbps: demasiado; en 60 s ~ 8.4 Mbps: vale.
      expect(
        reason(sealed, video({ size: MAX_VIDEO_BYTES, container: okContainer(30) })),
      ).toBe("duration");
      expect(
        reason(sealed, video({ size: MAX_VIDEO_BYTES, container: okContainer(60) })),
      ).toBe("ok");
      expect(reason(sealed, video({ container: okContainer(61) }))).toBe("duration");
    });

    test("container: las marcas de audio de iTunes no valen para video", () => {
      expect(reason(sealed, video({ container: okContainer(20, "M4A ") }))).toBe("container");
      expect(reason(sealed, video({ container: okContainer(20, "mp42") }))).toBe("ok");
    });
  });
});
