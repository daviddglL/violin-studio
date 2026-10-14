import { assertFails, assertSucceeds, RulesTestContext, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteObject, getBytes, listAll, ref, updateMetadata, uploadBytes } from "firebase/storage";
import { Claims, createEnv, OK, recordingUserDoc, seed } from "./helpers";

let env: RulesTestEnvironment;
const MIB = 1024 * 1024;
const AUDIO = { contentType: "audio/mp4", customMetadata: { kind: "audio", durationMs: "30000" } };

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await seed(env, "users/alice", recordingUserDoc());
});

const asCtx = (uid: string, claims: Claims = OK): RulesTestContext => env.authenticatedContext(uid, claims);
const path = (uid: string, file: string) => `users/${uid}/recordings/${file}`;
const bytes = (n: number) => new Uint8Array(n);
const upload = (ctx: RulesTestContext, uid: string, file: string, n = 1024, meta: object = AUDIO) =>
  uploadBytes(ref(ctx.storage(), path(uid, file)), bytes(n), meta);
const audioMeta = (m: Record<string, string>) => ({ contentType: "audio/mp4", customMetadata: m });

describe("Storage recordings: create audio (REQ-SEC-R03, REQ-REC-01)", () => {
  test("el dueño verificado con consentimiento sube un audio/mp4 válido", async () => {
    await assertSucceeds(upload(asCtx("alice"), "alice", "0a1b-2c.m4a"));
  });
  test("25 MiB exactos se permiten; +1 byte se deniega", async () => {
    await assertSucceeds(upload(asCtx("alice"), "alice", "max.m4a", 25 * MIB));
    await assertFails(upload(asCtx("alice"), "alice", "over.m4a", 25 * MIB + 1));
  });
  test("tipo: solo audio/mp4 (no wav, no video/mp4 en 4b)", async () => {
    const ctx = asCtx("alice");
    await assertFails(upload(ctx, "alice", "a.m4a", 10, { ...AUDIO, contentType: "audio/wav" }));
    await assertFails(upload(ctx, "alice", "b.m4a", 10, { ...AUDIO, contentType: "video/mp4" }));
    await assertFails(upload(ctx, "alice", "c.m4a", 10, { ...AUDIO, contentType: "application/octet-stream" }));
  });
  test("extension: .mp4 (video) aun no se admite en 4b", async () => {
    const meta = { contentType: "video/mp4", customMetadata: { kind: "video", durationMs: "1000", blurApplied: "true" } };
    await assertFails(upload(asCtx("alice"), "alice", "v.mp4", 10, meta));
  });
  test("nombre: solo [A-Za-z0-9-]{1,64}.m4a", async () => {
    const ctx = asCtx("alice");
    await assertSucceeds(upload(ctx, "alice", `${"a".repeat(64)}.m4a`));
    await assertFails(upload(ctx, "alice", `${"a".repeat(65)}.m4a`));
    await assertFails(upload(ctx, "alice", ".m4a"));
    await assertFails(upload(ctx, "alice", "a_b.m4a"));
    await assertFails(upload(ctx, "alice", "a.b.m4a"));
    await assertFails(upload(ctx, "alice", "a.M4A"));
    await assertFails(upload(ctx, "alice", "a.txt"));
    await assertFails(upload(ctx, "alice", "a"));
  });
  test("subcarpetas y rutas hermanas de recordings denegadas", async () => {
    const st = asCtx("alice").storage();
    await assertFails(uploadBytes(ref(st, "users/alice/recordings/sub/a.m4a"), bytes(10), AUDIO));
    await assertFails(uploadBytes(ref(st, "users/alice/other/a.m4a"), bytes(10), AUDIO));
  });
  test("metadatos: claves extra, kind invalido, durationMs ausente o no numerico, sin metadatos", async () => {
    const ctx = asCtx("alice");
    await assertFails(upload(ctx, "alice", "a.m4a", 10, audioMeta({ kind: "audio", durationMs: "1", extra: "x" })));
    await assertFails(upload(ctx, "alice", "b.m4a", 10, audioMeta({ kind: "video", durationMs: "1" })));
    await assertFails(upload(ctx, "alice", "c.m4a", 10, audioMeta({ kind: "audio" })));
    await assertFails(upload(ctx, "alice", "d.m4a", 10, audioMeta({ kind: "audio", durationMs: "abc" })));
    await assertFails(upload(ctx, "alice", "e.m4a", 10, audioMeta({ kind: "audio", durationMs: "1234567" })));
    await assertFails(upload(ctx, "alice", "f.m4a", 10, audioMeta({ kind: "audio", durationMs: "-5" })));
    await assertFails(upload(ctx, "alice", "g.m4a", 10, { contentType: "audio/mp4" }));
  });
  test("metadatos opcionales validos: assignmentId, sharedWith, blurApplied, clientVersion", async () => {
    const meta = audioMeta({
      kind: "audio",
      durationMs: "1000",
      assignmentId: "abc-123",
      sharedWith: "stu1",
      blurApplied: "false",
      clientVersion: "1.0.0",
    });
    await assertSucceeds(upload(asCtx("alice"), "alice", "m.m4a", 10, meta));
  });
  test("metadatos opcionales con formato invalido se deniegan", async () => {
    const ctx = asCtx("alice");
    await assertFails(upload(ctx, "alice", "a.m4a", 10, audioMeta({ kind: "audio", durationMs: "1", assignmentId: "a/b" })));
    await assertFails(upload(ctx, "alice", "b.m4a", 10, audioMeta({ kind: "audio", durationMs: "1", sharedWith: "a b" })));
  });
  test("sobrescribir un objeto existente (update) se deniega", async () => {
    await assertSucceeds(upload(asCtx("alice"), "alice", "once.m4a"));
    await assertFails(upload(asCtx("alice"), "alice", "once.m4a"));
    const r = ref(asCtx("alice").storage(), path("alice", "once.m4a"));
    await assertFails(updateMetadata(r, { customMetadata: { kind: "audio", durationMs: "2" } }));
  });
  test("delete denegado al dueño y a terceros (borrar = borrar el doc, A12)", async () => {
    await assertSucceeds(upload(asCtx("alice"), "alice", "del.m4a"));
    await assertFails(deleteObject(ref(asCtx("alice").storage(), path("alice", "del.m4a"))));
    await assertFails(deleteObject(ref(asCtx("eve").storage(), path("alice", "del.m4a"))));
  });
  test("listar users/{uid}/recordings se deniega", async () => {
    await assertSucceeds(upload(asCtx("alice"), "alice", "l.m4a"));
    await assertFails(listAll(ref(asCtx("alice").storage(), "users/alice/recordings")));
  });
  test("rutas fuera de recordings siguen deny-all", async () => {
    const st = asCtx("alice").storage();
    await assertFails(uploadBytes(ref(st, "users/alice/avatar.m4a"), bytes(10), AUDIO));
    await assertFails(getBytes(ref(st, "users/alice/avatar.m4a")));
  });
});

describe("Storage recordings: quien puede crear (REQ-REC-02, REQ-SEC-R03)", () => {
  const denied = (claims: Claims = OK) => assertFails(upload(asCtx("alice", claims), "alice", "x.m4a"));

  test.each(["revoked", "parental_pending", "none"])("recordingConsent = %s se deniega", async (state) => {
    await seed(env, "users/alice", recordingUserDoc({ recordingConsent: state }));
    await denied();
  });
  test("sin campo recordingConsent se deniega", async () => {
    const { recordingConsent: _drop, ...rest } = recordingUserDoc();
    await seed(env, "users/alice", rest);
    await denied();
  });
  test("consentimiento base revocado o pendiente se deniega aunque recordingConsent = granted", async () => {
    for (const consentStatus of ["revoked", "pending", "parental_pending"]) {
      await seed(env, "users/alice", recordingUserDoc({ consentStatus }));
      await denied();
    }
  });
  test("deletion en curso se deniega", async () => {
    await seed(env, "users/alice", recordingUserDoc({ deletion: { startedAt: new Date("2026-02-01T00:00:00Z") } }));
    await denied();
  });
  test("version de consentimiento de grabacion antigua o ausente se deniega (nota B1)", async () => {
    await seed(env, "users/alice", recordingUserDoc({ recordingConsentVersion: 0 }));
    await denied();
    const { recordingConsentVersion: _drop, ...rest } = recordingUserDoc();
    await seed(env, "users/alice", rest);
    await denied();
  });
  test("consentOk:false, email sin verificar y anonimo se deniegan", async () => {
    await denied({ email_verified: true, consentOk: false });
    await denied({ email_verified: false, consentOk: true });
    await denied({ email_verified: true });
    const anon = ref(env.unauthenticatedContext().storage(), path("alice", "x.m4a"));
    await assertFails(uploadBytes(anon, bytes(10), AUDIO));
  });
  test("otro uid no sube en el espacio de alice (aunque tenga su propio consentimiento)", async () => {
    await seed(env, "users/bob", recordingUserDoc());
    await assertFails(upload(asCtx("bob"), "alice", "x.m4a"));
  });
  test("sin doc de usuario se deniega", async () => {
    await assertFails(upload(asCtx("zed"), "zed", "x.m4a"));
  });
});

describe("Storage recordings: cupo de 20 vivas, corte rapido (REQ-REC-14, A21)", () => {
  const withCount = (n: unknown) => seed(env, "users/alice", recordingUserDoc({ recordingCount: n }));

  test("recordingCount = 19 crea", async () => {
    await withCount(19);
    await assertSucceeds(upload(asCtx("alice"), "alice", "q19.m4a"));
  });
  test("recordingCount = 20 deniega (y mas)", async () => {
    await withCount(20);
    await assertFails(upload(asCtx("alice"), "alice", "q20.m4a"));
    await withCount(25);
    await assertFails(upload(asCtx("alice"), "alice", "q25.m4a"));
  });
  test("recordingCount ausente cuenta como 0", async () => {
    await assertSucceeds(upload(asCtx("alice"), "alice", "q0.m4a"));
  });
  test("el cupo es por dueño: el de alice no afecta a bob", async () => {
    await withCount(20);
    await seed(env, "users/bob", recordingUserDoc({ recordingCount: 3 }));
    await assertSucceeds(upload(asCtx("bob"), "bob", "b.m4a"));
  });
});
