import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, collectionGroup, deleteDoc, deleteField, doc, getDoc, getDocs, serverTimestamp, setDoc, Timestamp, updateDoc } from "firebase/firestore";
import { Claims, createEnv, OK, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;
const PATH = "users/alice/practiceSessions/s1";
const NEW = "users/alice/practiceSessions/s2";
const stored = {
  startedAt: new Date("2026-03-01T10:00:00Z"),
  durationSec: 600,
  instrument: "violin",
  notes: "escalas",
  createdAt: new Date("2026-03-01T10:10:00Z"),
};
/** Sesión válida para crear: createdAt debe ser request.time (serverTimestamp). */
const valid = (o: Record<string, unknown> = {}): Record<string, unknown> => ({
  startedAt: Timestamp.fromDate(new Date("2026-03-01T10:00:00Z")),
  durationSec: 600,
  instrument: "violin",
  notes: "escalas",
  createdAt: serverTimestamp(),
  ...o,
});

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env, "users/alice", userDoc());
  await seed(env, PATH, stored);
});

const as = (uid: string, claims: Claims = OK) => env.authenticatedContext(uid, claims).firestore();
const alice = () => as("alice");
const bob = () => as("bob");
const anon = () => env.unauthenticatedContext().firestore();

describe("lectura (REQ-SEC-P01)", () => {
  test("el dueño lee y lista", async () => {
    await assertSucceeds(getDoc(doc(alice(), PATH)));
    await assertSucceeds(getDocs(collection(alice(), "users/alice/practiceSessions")));
  });
  test("el dueño sin claims al día también lee", async () => {
    await assertSucceeds(getDoc(doc(as("alice", { email_verified: false, consentOk: false }), PATH)));
  });
  test("un collection group de practiceSessions se deniega incluso al dueño", async () => {
    await assertFails(getDocs(collectionGroup(alice(), "practiceSessions")));
  });
  test("otro usuario y anónimo no leen ni listan", async () => {
    for (const db of [bob(), anon()]) {
      await assertFails(getDoc(doc(db, PATH)));
      await assertFails(getDocs(collection(db, "users/alice/practiceSessions")));
    }
  });
});

describe("create (REQ-SEC-P02)", () => {
  test("el dueño verificado, con consentimiento y doc granted crea", async () => {
    await assertSucceeds(setDoc(doc(alice(), NEW), valid()));
  });
  test("notes es opcional", async () => {
    const sinNotes = valid();
    delete sinNotes.notes;
    await assertSucceeds(setDoc(doc(alice(), NEW), sinNotes));
  });
  test.each<[string, Claims]>([
    ["consentOk false", { email_verified: true, consentOk: false }],
    ["email sin verificar", { email_verified: false, consentOk: true }],
  ])("deniega con %s", async (_, claims) => {
    await assertFails(setDoc(doc(as("alice", claims), NEW), valid()));
  });
  test("deniega si el doc no está granted", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "revoked" }));
    await assertFails(setDoc(doc(alice(), NEW), valid()));
  });
  test("deniega si el doc tiene deletion", async () => {
    await seed(env, "users/alice", userDoc({ deletion: { requestedAt: new Date() } }));
    await assertFails(setDoc(doc(alice(), NEW), valid()));
  });
  test("deniega a otro uid y a un anónimo", async () => {
    await assertFails(setDoc(doc(bob(), NEW), valid()));
    await assertFails(setDoc(doc(anon(), NEW), valid()));
  });

  test("tolera un reloj adelantado hasta 10 minutos", async () => {
    const ahead = Timestamp.fromDate(new Date(Date.now() + 5 * 60_000));
    await assertSucceeds(setDoc(doc(alice(), NEW), valid({ startedAt: ahead, durationSec: 60 })));
  });
  test("notes se mide en unidades UTF-16: 250 emoji sí, 251 no", async () => {
    await assertSucceeds(setDoc(doc(alice(), NEW), valid({ notes: "😀".repeat(250) })));
    await assertFails(setDoc(doc(alice(), "users/alice/practiceSessions/s3"), valid({ notes: "😀".repeat(251) })));
  });
  test("el id de la sesión admite 64 caracteres, no 65", async () => {
    await assertSucceeds(setDoc(doc(alice(), `users/alice/practiceSessions/${"a".repeat(64)}`), valid()));
    await assertFails(setDoc(doc(alice(), `users/alice/practiceSessions/${"a".repeat(65)}`), valid()));
  });
  test("deniega si falta el doc de usuario", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await deleteDoc(doc(ctx.firestore(), "users/alice"));
    });
    await assertFails(setDoc(doc(alice(), NEW), valid()));
  });
  test("deniega sobrescribir una sesión existente con un doc completo válido", async () => {
    await assertFails(setDoc(doc(alice(), PATH), valid()));
  });

  test.each<[string, Record<string, unknown>]>([
    ["notes null", { notes: null }],
    ["startedAt 11 min en el futuro", { startedAt: Timestamp.fromDate(new Date(Date.now() + 11 * 60_000)), durationSec: 60 }],
    ["startedAt ahora con duración de 12 h", { startedAt: Timestamp.fromDate(new Date()), durationSec: 43200 }],
    ["campo extra audioUrl", { audioUrl: "x" }],
    ["durationSec 0", { durationSec: 0 }],
    ["durationSec 43201", { durationSec: 43201 }],
    ["durationSec string", { durationSec: "600" }],
    ["durationSec float", { durationSec: 1.5 }],
    ["instrument fuera del enum", { instrument: "guitar" }],
    ["instrument no string", { instrument: 1 }],
    ["notes 501", { notes: "a".repeat(501) }],
    ["notes no string", { notes: 5 }],
    ["startedAt futuro", { startedAt: Timestamp.fromDate(new Date(Date.now() + 86_400_000)) }],
    ["startedAt <= 2020-01-01", { startedAt: Timestamp.fromDate(new Date("2020-01-01T00:00:00Z")) }],
    ["startedAt no timestamp", { startedAt: "2026-03-01" }],
    ["createdAt != request.time", { createdAt: Timestamp.fromDate(new Date("2026-03-01T10:10:00Z")) }],
  ])("deniega: %s", async (_, over) => {
    await assertFails(setDoc(doc(alice(), NEW), valid(over)));
  });

  test.each(["startedAt", "durationSec", "instrument", "createdAt"])("deniega si falta %s", async (k) => {
    const d = valid();
    delete d[k];
    await assertFails(setDoc(doc(alice(), NEW), d));
  });

  test.each<[string, Record<string, unknown>]>([
    ["durationSec 1", { durationSec: 1 }],
    ["durationSec 43200", { durationSec: 43200 }],
    ["notes 500", { notes: "a".repeat(500) }],
    ["notes vacío", { notes: "" }],
  ])("permite el límite: %s", async (_, over) => {
    await assertSucceeds(setDoc(doc(alice(), NEW), valid(over)));
  });

  test.each(["violin", "viola", "cello", "double_bass", "other"])("permite instrument %s", async (i) => {
    await assertSucceeds(setDoc(doc(alice(), NEW), valid({ instrument: i })));
  });
});

describe("update (REQ-SEC-P03)", () => {
  test("el dueño puede quitar notes con deleteField", async () => {
    await assertSucceeds(updateDoc(doc(alice(), PATH), { notes: deleteField() }));
  });
  test("el dueño actualiza solo notes (<= 500)", async () => {
    await assertSucceeds(updateDoc(doc(alice(), PATH), { notes: "nuevas" }));
    await assertSucceeds(updateDoc(doc(alice(), PATH), { notes: "a".repeat(500) }));
  });
  test.each<[string, Record<string, unknown>]>([
    ["notes 501", { notes: "a".repeat(501) }],
    ["notes no string", { notes: 3 }],
    ["durationSec", { durationSec: 700 }],
    ["startedAt", { startedAt: Timestamp.fromDate(new Date("2026-03-02T10:00:00Z")) }],
    ["instrument", { instrument: "cello" }],
    ["createdAt", { createdAt: serverTimestamp() }],
    ["campo extra", { audioUrl: "x" }],
    ["notes + durationSec", { notes: "x", durationSec: 700 }],
  ])("deniega: %s", async (_, over) => {
    await assertFails(updateDoc(doc(alice(), PATH), over));
  });
  test("deniega sin consentimiento, sin email, con doc revocado o con deletion", async () => {
    await assertFails(updateDoc(doc(as("alice", { email_verified: true, consentOk: false }), PATH), { notes: "x" }));
    await assertFails(updateDoc(doc(as("alice", { email_verified: false, consentOk: true }), PATH), { notes: "x" }));
    await seed(env, "users/alice", userDoc({ consentStatus: "revoked" }));
    await assertFails(updateDoc(doc(alice(), PATH), { notes: "x" }));
    await seed(env, "users/alice", userDoc({ deletion: { requestedAt: new Date() } }));
    await assertFails(updateDoc(doc(alice(), PATH), { notes: "x" }));
  });
  test("otro usuario y anónimo no actualizan", async () => {
    await assertFails(updateDoc(doc(bob(), PATH), { notes: "x" }));
    await assertFails(updateDoc(doc(anon(), PATH), { notes: "x" }));
  });
});

describe("delete (REQ-SEC-P04)", () => {
  test("el dueño borra con consentimiento", async () => {
    await assertSucceeds(deleteDoc(doc(alice(), PATH)));
  });
  test("el dueño borra sin consentimiento, con doc revocado y con deletion", async () => {
    await assertSucceeds(deleteDoc(doc(as("alice", { email_verified: false, consentOk: false }), PATH)));
    await seed(env, PATH, stored);
    await seed(env, "users/alice", userDoc({ consentStatus: "revoked", deletion: { requestedAt: new Date() } }));
    await assertSucceeds(deleteDoc(doc(alice(), PATH)));
  });
  test("otro usuario y anónimo no borran", async () => {
    await assertFails(deleteDoc(doc(bob(), PATH)));
    await assertFails(deleteDoc(doc(anon(), PATH)));
  });
});

describe("colecciones cerradas (REQ-SEC-P05)", () => {
  test("una subcolección no declarada bajo users/{uid} sigue denegada", async () => {
    await seed(env, "users/alice/otraSub/x", { v: 1 });
    await assertFails(getDoc(doc(alice(), "users/alice/otraSub/x")));
    await assertFails(setDoc(doc(alice(), "users/alice/otraSub/y"), { v: 1 }));
  });
  test("no se puede anidar bajo una sesión", async () => {
    await assertFails(setDoc(doc(alice(), `${PATH}/audio/a1`), { v: 1 }));
  });
});

// Rama profesor (REQ-SEC-T01, T04, TVW-02, TVW-03): lectura cruzada por vínculo, nunca por claim.
describe("profesor vinculado", () => {
  const COL = "users/alice/practiceSessions";
  const tea = (claims: Claims = OK) => as("tea", claims);
  beforeEach(async () => {
    await seed(env, "users/tea", userDoc({ displayName: "Profe", role: "teacher" }));
    await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
  });

  test("lee y lista; no escribe", async () => {
    await assertSucceeds(getDoc(doc(tea(), PATH)));
    await assertSucceeds(getDocs(collection(tea(), COL)));
    await assertFails(setDoc(doc(tea(), NEW), valid()));
    await assertFails(updateDoc(doc(tea(), PATH), { notes: "x" }));
    await assertFails(deleteDoc(doc(tea(), PATH)));
  });
  test("profesor no vinculado, otro alumno, anónimo y collection group: denegado", async () => {
    await seed(env, "users/carl", userDoc({ role: "teacher" }));
    for (const db of [as("carl"), bob(), anon()]) {
      await assertFails(getDoc(doc(db, PATH)));
      await assertFails(getDocs(collection(db, COL)));
    }
    await assertFails(getDocs(collectionGroup(tea(), "practiceSessions")));
  });
  test("el vínculo es direccional: el alumno no lee al profesor", async () => {
    await seed(env, "users/tea/practiceSessions/t1", stored);
    await assertFails(getDoc(doc(alice(), "users/tea/practiceSessions/t1")));
  });
  test("email sin verificar o consentOk:false: denegado", async () => {
    for (const c of [{ email_verified: false, consentOk: true }, { email_verified: true, consentOk: false }, {}]) {
      await assertFails(getDoc(doc(tea(c), PATH)));
      await assertFails(getDocs(collection(tea(c), COL)));
    }
  });
  test.each([
    ["alumno", "users/alice", { consentStatus: "revoked" }],
    ["alumno", "users/alice", { deletion: { startedAt: new Date() } }],
    ["profesor", "users/tea", { consentStatus: "pending" }],
    ["profesor", "users/tea", { deletion: { startedAt: new Date() } }],
  ])("doc de %s sin consentimiento o con deletion (%j): denegado", async (_, path, over) => {
    await seed(env, path, userDoc({ role: path === "users/tea" ? "teacher" : "student", ...over }));
    await assertFails(getDoc(doc(tea(), PATH)));
    await assertFails(getDocs(collection(tea(), COL)));
  });
  test("vínculo borrado a mitad: el acceso cae", async () => {
    await assertSucceeds(getDoc(doc(tea(), PATH)));
    await env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), "teacherLinks/tea_alice")));
    await assertFails(getDoc(doc(tea(), PATH)));
    await assertFails(getDocs(collection(tea(), COL)));
  });
  test("las reglas no dependen del claim role (REQ-SEC-T01)", async () => {
    // Claim desfasado (sin role o 'student') pero vínculo válido: permitido.
    await assertSucceeds(getDoc(doc(as("tea", { ...OK, role: "student" } as Claims), PATH)));
    await assertSucceeds(getDoc(doc(as("tea", { ...OK, role: "independent" } as Claims), PATH)));
    // Claim 'teacher' sin vínculo: denegado.
    await seed(env, "users/carl", userDoc({ role: "teacher" }));
    await assertFails(getDoc(doc(as("carl", { ...OK, role: "teacher" } as Claims), PATH)));
    await assertFails(getDocs(collection(as("carl", { ...OK, role: "teacher" } as Claims), COL)));
  });
});

// Coste (REQ-SEC-T01): isLinkedTeacher = 1 exists + 2 get (<= 10 por peticion); el list se evalua una vez, no por doc.
describe("coste de las reglas del profesor", () => {
  test("listar 60 sesiones y leer una de ellas funciona dentro del limite de accesos", async () => {
    await seed(env, "users/tea", userDoc({ role: "teacher" }));
    await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
    for (let i = 0; i < 60; i++) await seed(env, `users/alice/practiceSessions/b${i}`, stored);
    const snap = await assertSucceeds(getDocs(collection(as("tea"), "users/alice/practiceSessions")));
    expect(snap.size).toBe(61);
  });
});

describe("ambiguedad del id del vinculo y alcance del profesor", () => {
  test("uid con '_' no permite suplantar un vinculo ajeno (a + b_c vs a_b + c)", async () => {
    await seed(env, "users/b_c", userDoc());
    await seed(env, "users/b_c/practiceSessions/s1", stored);
    await seed(env, "users/a", userDoc({ role: "teacher" }));
    await seed(env, "users/a_b", userDoc({ role: "teacher" }));
    await seed(env, "users/c", userDoc());
    await seed(env, "users/c/practiceSessions/s1", stored);
    await seed(env, "teacherLinks/a_b_c", { teacherUid: "a_b", studentUid: "c" });
    await assertFails(getDoc(doc(as("a"), "users/b_c/practiceSessions/s1")));
    await assertFails(getDocs(collection(as("a"), "users/b_c/practiceSessions")));
    await assertSucceeds(getDoc(doc(as("a_b"), "users/c/practiceSessions/s1")));
  });
  test("el profesor vinculado no lee a otro alumno ni otras subcolecciones del suyo", async () => {
    await seed(env, "users/tea", userDoc({ role: "teacher" }));
    await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
    await seed(env, "users/bob", userDoc());
    await seed(env, "users/bob/practiceSessions/s1", stored);
    await seed(env, "users/alice/consents/c1", { type: "policy" });
    await seed(env, "users/alice/otra/x", { a: 1 });
    await assertFails(getDoc(doc(as("tea"), "users/bob/practiceSessions/s1")));
    await assertFails(getDoc(doc(as("tea"), "users/alice/consents/c1")));
    await assertFails(getDocs(collection(as("tea"), "users/alice/consents")));
    await assertFails(getDoc(doc(as("tea"), "users/alice/otra/x")));
    await assertFails(getDoc(doc(as("tea"), "users/alice")));
  });
});
