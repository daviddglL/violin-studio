import { assertFails, assertSucceeds, RulesTestContext, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteDoc, doc } from "firebase/firestore";
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
const demoPath = (uid: string, file: string) => `users/${uid}/demos/${file}`;
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
  test("extension: .mp4 aun no se admite en 4b (aunque contentType y metadatos sean de audio validos)", async () => {
    await assertFails(upload(asCtx("alice"), "alice", "v.mp4", 10, AUDIO));
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
  test("metadatos opcionales validos: assignmentId, blurApplied, blurPolicyVersion, clientVersion", async () => {
    const meta = audioMeta({
      kind: "audio",
      durationMs: "1000",
      assignmentId: "abc-123",
      blurApplied: "false",
      blurPolicyVersion: "1",
      clientVersion: "1.0.0",
    });
    await assertSucceeds(upload(asCtx("alice"), "alice", "m.m4a", 10, meta));
  });
  test("metadatos opcionales con formato invalido se deniegan", async () => {
    const ctx = asCtx("alice");
    await assertFails(upload(ctx, "alice", "a.m4a", 10, audioMeta({ kind: "audio", durationMs: "1", assignmentId: "a/b" })));
  });
  test("sharedWith no se admite en recordings: las demostraciones van a users/{uid}/demos (C1)", async () => {
    const meta = audioMeta({ kind: "audio", durationMs: "1", sharedWith: "stu1" });
    await assertFails(upload(asCtx("alice"), "alice", "s.m4a", 10, meta));
  });
  test("sobrescribir un objeto existente se deniega (el emulador lo evalua como create: lo cierra resource == null)", async () => {
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

describe("Storage recordings: lectura (REQ-SEC-R03)", () => {
  const OBJ = path("alice", "rec1.m4a");
  const DEMO = demoPath("tea", "demo1.m4a");
  const link = (t: string, s: string, over: Record<string, unknown> = {}) => ({
    teacherUid: t,
    studentUid: s,
    teacherDisplayName: "Profe",
    studentDisplayName: "Alumno",
    studentInstrument: "violin",
    ackVersion: 1,
    createdAt: new Date("2026-03-01T10:00:00Z"),
    ...over,
  });
  const read = (uid: string, p: string, claims: Claims = OK) => getBytes(ref(asCtx(uid, claims).storage(), p));

  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(ctx.storage(), OBJ), bytes(16), AUDIO);
      await uploadBytes(ref(ctx.storage(), DEMO), bytes(16), AUDIO);
    });
    await seed(env, "users/tea", recordingUserDoc({ role: "teacher" }));
    await seed(env, "users/stu", recordingUserDoc());
    await seed(env, "teacherLinks/tea_alice", link("tea", "alice"));
    await seed(env, "teacherLinks/tea_stu", link("tea", "stu"));
    await seed(env, "users/tea/recordings/demo1", { status: "ready", sharedWith: "stu" });
  });

  test("el dueño lee su grabación", async () => {
    await assertSucceeds(read("alice", OBJ));
  });
  test("el dueño verificado lee aunque su consentimiento de grabación esté revocado (exportar/ver lo suyo)", async () => {
    await seed(env, "users/alice", recordingUserDoc({ recordingConsent: "revoked" }));
    await assertSucceeds(read("alice", OBJ));
  });
  test("el profesor vinculado lee con recordingConsent granted", async () => {
    await assertSucceeds(read("tea", OBJ));
  });
  test("profesor no vinculado, otro alumno y anónimo se deniegan", async () => {
    await assertFails(read("eve", OBJ));
    await assertFails(read("stu", OBJ));
    await assertFails(getBytes(ref(env.unauthenticatedContext().storage(), OBJ)));
  });
  test("vínculo borrado: pierde el acceso con el mismo token", async () => {
    const ctx = asCtx("tea");
    await assertSucceeds(getBytes(ref(ctx.storage(), OBJ)));
    await env.withSecurityRulesDisabled(async (c) => {
      await deleteDoc(doc(c.firestore(), "teacherLinks/tea_alice"));
    });
    await assertFails(getBytes(ref(ctx.storage(), OBJ)));
  });
  test("vínculo con campos que no casan (id ambiguo) se deniega", async () => {
    await seed(env, "teacherLinks/tea_alice", link("tea_x", "alice"));
    await assertFails(read("tea", OBJ));
  });
  test.each(["revoked", "parental_pending", "none"])("alumno con recordingConsent = %s: el profesor no lee", async (s) => {
    await seed(env, "users/alice", recordingUserDoc({ recordingConsent: s }));
    await assertFails(read("tea", OBJ));
  });
  test("version de consentimiento antigua, base revocado o deletion: el profesor no lee", async () => {
    await seed(env, "users/alice", recordingUserDoc({ recordingConsentVersion: 0 }));
    await assertFails(read("tea", OBJ));
    await seed(env, "users/alice", recordingUserDoc({ consentStatus: "revoked" }));
    await assertFails(read("tea", OBJ));
    await seed(env, "users/alice", recordingUserDoc({ deletion: { startedAt: new Date("2026-02-01T00:00:00Z") } }));
    await assertFails(read("tea", OBJ));
  });
  test("riesgo aceptado W2: claim consentOk obsoleto (hasta 1 h) del profesor con su doc ya revocado sigue leyendo", async () => {
    // Las reglas de Storage no releen el doc del profesor (tope de 2 accesos, A20): manda el claim hasta que caduque el
    // ID token. La revocacion dispara syncClaims; la ventana es la del refresco del token.
    await seed(env, "users/tea", recordingUserDoc({ role: "teacher", consentStatus: "revoked" }));
    await assertSucceeds(read("tea", OBJ));
  });
  test("profesor con email sin verificar o consentOk:false no lee", async () => {
    await assertFails(read("tea", OBJ, { email_verified: false, consentOk: true }));
    await assertFails(read("tea", OBJ, { email_verified: true, consentOk: false }));
  });
  test("el alumno destinatario de una demostración la lee con vínculo", async () => {
    await assertSucceeds(read("stu", DEMO));
  });
  test("demostración: otro alumno, alumno sin vínculo, vínculo borrado y anónimo se deniegan", async () => {
    await seed(env, "users/other", recordingUserDoc());
    await seed(env, "teacherLinks/tea_other", link("tea", "other"));
    await assertFails(read("other", DEMO));
    await assertFails(read("eve", DEMO));
    await assertFails(getBytes(ref(env.unauthenticatedContext().storage(), DEMO)));
    const ctx = asCtx("stu");
    await assertSucceeds(getBytes(ref(ctx.storage(), DEMO)));
    await env.withSecurityRulesDisabled(async (c) => {
      await deleteDoc(doc(c.firestore(), "teacherLinks/tea_stu"));
    });
    await assertFails(getBytes(ref(ctx.storage(), DEMO)));
  });
  test("C1: un objeto en recordings con doc sharedWith NO se lee como demostración (rutas disjuntas)", async () => {
    await env.withSecurityRulesDisabled(async (c) => {
      await uploadBytes(ref(c.storage(), path("tea", "demo1.m4a")), bytes(16), AUDIO);
    });
    await assertFails(read("stu", path("tea", "demo1.m4a")));
  });
  test("C1: en demos solo leen el dueño y el destinatario, no el profesor vinculado al dueño", async () => {
    await env.withSecurityRulesDisabled(async (c) => {
      await uploadBytes(ref(c.storage(), demoPath("alice", "d.m4a")), bytes(16), AUDIO);
    });
    await seed(env, "users/alice/recordings/d", { status: "ready", sharedWith: "zzz" });
    await assertFails(read("tea", demoPath("alice", "d.m4a")));
    await assertSucceeds(read("tea", DEMO));
  });
  test("riesgo aceptado W3: la lectura de demo no comprueba el consentimiento de grabacion de profesor ni alumno", async () => {
    // demoReads ya gasta los 2 accesos (doc de la grabacion + vinculo). Revocar el consentimiento de grabacion del
    // profesor borra sus demos (B5b); la demo es contenido del profesor, no del alumno.
    await seed(env, "users/stu", recordingUserDoc({ recordingConsent: "none" }));
    await seed(env, "users/tea", recordingUserDoc({ role: "teacher", recordingConsent: "revoked" }));
    await assertSucceeds(read("stu", DEMO));
  });
  test("demostración sin sharedWith no la lee un alumno vinculado", async () => {
    await seed(env, "users/tea/recordings/demo1", { status: "ready" });
    await assertFails(read("stu", DEMO));
  });
  test("get de objetos inexistentes o con doc de grabación ausente se deniega", async () => {
    await assertFails(read("stu", demoPath("tea", "ghost.m4a")));
  });
  test("update, delete y listado de terceros siguen denegados", async () => {
    await assertFails(deleteObject(ref(asCtx("tea").storage(), OBJ)));
    await assertFails(listAll(ref(asCtx("tea").storage(), "users/alice/recordings")));
  });
});

describe("Storage demos del profesor: create (REQ-REC-10, C1)", () => {
  const DEMO_META = audioMeta({ kind: "audio", durationMs: "90000", assignmentId: "as-1", sharedWith: "stu" });
  const uploadDemo = (uid: string, owner: string, file: string, meta: object = DEMO_META, claims: Claims = OK, n = 1024) =>
    uploadBytes(ref(asCtx(uid, claims).storage(), demoPath(owner, file)), bytes(n), meta);

  beforeEach(async () => {
    await seed(env, "users/tea", recordingUserDoc({ role: "teacher" }));
  });

  test("el profesor (role del doc = teacher) con consentimiento sube su demo con sharedWith y assignmentId", async () => {
    await assertSucceeds(uploadDemo("tea", "tea", "d-1.m4a"));
  });
  test("un no profesor (role del doc) no sube demos aunque el claim diga teacher", async () => {
    await assertFails(uploadDemo("alice", "alice", "d.m4a", DEMO_META, { ...OK, role: "teacher" }));
  });
  test("sharedWith y assignmentId son obligatorios y con formato", async () => {
    await assertFails(uploadDemo("tea", "tea", "a.m4a", audioMeta({ kind: "audio", durationMs: "1", assignmentId: "as-1" })));
    await assertFails(uploadDemo("tea", "tea", "b.m4a", audioMeta({ kind: "audio", durationMs: "1", sharedWith: "stu" })));
    const badShare = audioMeta({ kind: "audio", durationMs: "1", assignmentId: "as-1", sharedWith: "a b" });
    await assertFails(uploadDemo("tea", "tea", "c.m4a", badShare));
  });
  test("otro uid, consentOk:false, cupo agotado o sin consentimiento de grabacion se deniegan", async () => {
    await assertFails(uploadDemo("alice", "tea", "x.m4a"));
    await assertFails(uploadDemo("tea", "tea", "y.m4a", DEMO_META, { email_verified: true, consentOk: false }));
    await seed(env, "users/tea", recordingUserDoc({ role: "teacher", recordingCount: 20 }));
    await assertFails(uploadDemo("tea", "tea", "z.m4a"));
    await seed(env, "users/tea", recordingUserDoc({ role: "teacher", recordingConsent: "revoked" }));
    await assertFails(uploadDemo("tea", "tea", "w.m4a"));
  });
  test("tipo, tamaño, nombre y sobrescritura igual que en recordings", async () => {
    await assertFails(uploadDemo("tea", "tea", "t.m4a", { ...DEMO_META, contentType: "audio/wav" }));
    await assertFails(uploadDemo("tea", "tea", "big.m4a", DEMO_META, OK, 25 * MIB + 1));
    await assertFails(uploadDemo("tea", "tea", "v.mp4"));
    await assertFails(uploadDemo("tea", "tea", "a_b.m4a"));
    await assertSucceeds(uploadDemo("tea", "tea", "once.m4a"));
    await assertFails(uploadDemo("tea", "tea", "once.m4a"));
  });
  test("delete, update y listado de demos denegados", async () => {
    await assertSucceeds(uploadDemo("tea", "tea", "k.m4a"));
    const r = ref(asCtx("tea").storage(), demoPath("tea", "k.m4a"));
    await assertFails(deleteObject(r));
    await assertFails(updateMetadata(r, { customMetadata: { kind: "audio", durationMs: "2" } }));
    await assertFails(listAll(ref(asCtx("tea").storage(), "users/tea/demos")));
  });
});
