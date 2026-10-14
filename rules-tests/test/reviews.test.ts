import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, collectionGroup, deleteDoc, doc, documentId, getDoc, getDocs, query, serverTimestamp, setDoc, Timestamp, updateDoc, where } from "firebase/firestore";
import { Claims, createEnv, OK, recordingUserDoc, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;
const T0 = new Date("2026-03-01T10:00:00Z");
const REC = "users/alice/recordings";
const REV = `${REC}/ready1/reviews`;
const MINE = `${REV}/tea`;
const rec = (o: Record<string, unknown> = {}) => ({
  kind: "audio",
  status: "ready",
  durationSec: 30,
  sizeBytes: 1000,
  contentType: "audio/mp4",
  objectPath: "users/alice/recordings/r1.m4a",
  generation: "1",
  createdAt: T0,
  expiresAt: new Date("2026-06-01T10:00:00Z"),
  ...o,
});
const stored = (o: Record<string, unknown> = {}) => ({
  reviewerUid: "tea",
  verdict: "needs_work",
  text: "Mas arco",
  createdAt: T0,
  updatedAt: T0,
  ...o,
});
/** Revision valida para crear: createdAt y updatedAt deben ser request.time. */
const valid = (o: Record<string, unknown> = {}): Record<string, unknown> => ({
  reviewerUid: "tea",
  verdict: "approved",
  text: "Muy bien",
  createdAt: serverTimestamp(),
  updatedAt: serverTimestamp(),
  ...o,
});

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env, "users/alice", recordingUserDoc({ role: "student" }));
  await seed(env, "users/tea", userDoc({ displayName: "Profe", role: "teacher" }));
  await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
  await seed(env, `${REC}/ready1`, rec());
  await seed(env, `${REC}/pending1`, rec({ status: "pending" }));
  await seed(env, `${REC}/rejected1`, rec({ status: "rejected", rejectReason: "size" }));
});

const as = (uid: string, claims: Claims = OK) => env.authenticatedContext(uid, claims).firestore();
const alice = () => as("alice");
const tea = (claims: Claims = OK) => as("tea", claims);
const anon = () => env.unauthenticatedContext().firestore();
const unlink = () => env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), "teacherLinks/tea_alice")));

describe("create (REQ-SEC-R02, REQ-RVW-02)", () => {
  test("profesor vinculado, grabacion ready, esquema exacto: permitido (texto vacio y de 1000 tambien)", async () => {
    await assertSucceeds(setDoc(doc(tea(), MINE), valid()));
    await env.clearFirestore();
    await seed(env, "users/alice", recordingUserDoc({ role: "student" }));
    await seed(env, "users/tea", userDoc({ role: "teacher" }));
    await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
    await seed(env, `${REC}/ready1`, rec());
    await assertSucceeds(setDoc(doc(tea(), MINE), valid({ text: "" })));
    await env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), MINE)));
    await assertSucceeds(setDoc(doc(tea(), MINE), valid({ verdict: "needs_work", text: "a".repeat(1000) })));
  });
  test.each<[string, Record<string, unknown>]>([
    ["texto de 1001", { text: "a".repeat(1001) }],
    ["veredicto invalido", { verdict: "bueno" }],
    ["veredicto no string", { verdict: 1 }],
    ["texto no string", { text: 5 }],
    ["texto ausente", { text: undefined }],
    ["veredicto ausente", { verdict: undefined }],
    ["campo extra", { extra: 1 }],
    ["createdAt del cliente", { createdAt: Timestamp.fromDate(T0) }],
    ["updatedAt del cliente", { updatedAt: Timestamp.fromDate(T0) }],
    ["createdAt != updatedAt", { updatedAt: Timestamp.fromDate(new Date()) }],
    ["reviewerUid de otro", { reviewerUid: "otro" }],
    ["reviewerUid null", { reviewerUid: null }],
  ])("denegado: %s", async (_, over) => {
    const data = Object.fromEntries(Object.entries(valid(over)).filter(([, v]) => v !== undefined));
    await assertFails(setDoc(doc(tea(), MINE), data));
  });
  test("denegado: id distinto de auth.uid (suplantacion o id aleatorio)", async () => {
    await assertFails(setDoc(doc(tea(), `${REV}/otro`), valid()));
    await assertFails(setDoc(doc(tea(), `${REV}/aleatorio123`), valid({ reviewerUid: null })));
  });
  test.each(["pending1", "rejected1", "inexistente"])("denegado: grabacion %s (solo ready)", async (id) => {
    await assertFails(setDoc(doc(tea(), `${REC}/${id}/reviews/tea`), valid()));
  });
  test("denegado: sin vinculo o con vinculo borrado", async () => {
    await unlink();
    await assertFails(setDoc(doc(tea(), MINE), valid()));
  });
  test("denegado: profesor no vinculado a ese alumno", async () => {
    await seed(env, "users/tb", userDoc({ role: "teacher" }));
    await assertFails(setDoc(doc(as("tb"), `${REV}/tb`), valid({ reviewerUid: "tb" })));
  });
  test("denegado: claims desfasados o consentimiento base del alumno/profesor revocado, alumno en borrado", async () => {
    await assertFails(setDoc(doc(tea({ email_verified: true }), MINE), valid()));
    await assertFails(setDoc(doc(tea({ consentOk: true }), MINE), valid()));
    await seed(env, "users/alice", recordingUserDoc({ role: "student", deletion: { state: "pending" } }));
    await assertFails(setDoc(doc(tea(), MINE), valid()));
    await seed(env, "users/alice", recordingUserDoc({ role: "student" }));
    await seed(env, "users/tea", userDoc({ role: "teacher", consentStatus: "revoked" }));
    await assertFails(setDoc(doc(tea(), MINE), valid()));
  });
  test.each([["revocado", "revoked"], ["ausente", undefined]])("denegado: el alumno tiene el consentimiento de grabacion %s (el profesor no podria leer esa grabacion)", async (_, v) => {
    await seed(env, "users/alice", Object.fromEntries(Object.entries(recordingUserDoc({ role: "student", recordingConsent: v })).filter(([, x]) => x !== undefined)));
    await assertFails(setDoc(doc(tea(), MINE), valid()));
  });
  test.each([["cero", 0], ["ausente", undefined]])("denegado: recordingConsentVersion %s aunque el consentimiento sea granted", async (_, v) => {
    await seed(env, "users/alice", Object.fromEntries(Object.entries(recordingUserDoc({ role: "student", recordingConsentVersion: v })).filter(([, x]) => x !== undefined)));
    await assertFails(setDoc(doc(tea(), MINE), valid()));
  });
  test("denegado: alumno dueño, otro alumno y anonimo", async () => {
    await seed(env, "users/bob", recordingUserDoc({ role: "student" }));
    await assertFails(setDoc(doc(alice(), `${REV}/alice`), valid({ reviewerUid: "alice" })));
    await assertFails(setDoc(doc(alice(), MINE), valid()));
    await assertFails(setDoc(doc(as("bob"), `${REV}/bob`), valid({ reviewerUid: "bob" })));
    await assertFails(setDoc(doc(anon(), MINE), valid()));
  });
});

describe("update y delete (REQ-RVW-02)", () => {
  beforeEach(async () => {
    await seed(env, MINE, stored());
  });
  test("el revisor vinculado cambia verdict, text y updatedAt", async () => {
    await assertSucceeds(updateDoc(doc(tea(), MINE), { verdict: "approved", text: "Mejor", updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(tea(), MINE), { text: "a".repeat(1000), updatedAt: serverTimestamp() }));
  });
  test.each<[string, Record<string, unknown>]>([
    ["reviewerUid", { reviewerUid: "otro", updatedAt: serverTimestamp() }],
    ["createdAt", { createdAt: serverTimestamp(), updatedAt: serverTimestamp() }],
    ["campo extra", { extra: 1, updatedAt: serverTimestamp() }],
    ["texto de 1001", { text: "a".repeat(1001), updatedAt: serverTimestamp() }],
    ["veredicto invalido", { verdict: "bueno", updatedAt: serverTimestamp() }],
    ["updatedAt del cliente", { text: "x", updatedAt: Timestamp.fromDate(new Date()) }],
    ["sin tocar updatedAt", { text: "x" }],
  ])("denegado: %s", async (_, patch) => {
    await assertFails(updateDoc(doc(tea(), MINE), patch));
  });
  test("denegado sin vinculo, al dueño, a otro alumno y al anonimo", async () => {
    const patch = { text: "x", updatedAt: serverTimestamp() };
    await seed(env, "users/bob", recordingUserDoc({ role: "student" }));
    await assertFails(updateDoc(doc(alice(), MINE), patch));
    await assertFails(updateDoc(doc(as("bob"), MINE), patch));
    await assertFails(updateDoc(doc(anon(), MINE), patch));
    await unlink();
    await assertFails(updateDoc(doc(tea(), MINE), patch));
  });
  test("delete: solo el revisor vinculado", async () => {
    await assertFails(deleteDoc(doc(alice(), MINE)));
    await assertFails(deleteDoc(doc(anon(), MINE)));
    await seed(env, "users/tb", userDoc({ role: "teacher" }));
    await seed(env, "teacherLinks/tb_alice", { teacherUid: "tb", studentUid: "alice" });
    await assertFails(deleteDoc(doc(as("tb"), MINE)));
    await unlink();
    await assertFails(deleteDoc(doc(tea(), MINE)));
    await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
    await assertSucceeds(deleteDoc(doc(tea(), MINE)));
  });
});

describe("lectura (REQ-SEC-R02, REQ-RVW-03)", () => {
  beforeEach(async () => {
    await seed(env, MINE, stored());
    await seed(env, `${REV}/tb`, stored({ reviewerUid: "tb" }));
    await seed(env, `${REV}/anonimo1`, stored({ reviewerUid: null }));
  });
  test("el alumno dueño lee todas las de su grabacion (tambien las anonimizadas) y las lista", async () => {
    await assertSucceeds(getDoc(doc(alice(), MINE)));
    await assertSucceeds(getDoc(doc(alice(), `${REV}/anonimo1`)));
    const snap = await assertSucceeds(getDocs(collection(alice(), REV)));
    expect(snap.size).toBe(3);
  });
  test("el alumno conserva el acceso tras desvincular; el profesor lo pierde", async () => {
    await unlink();
    await assertSucceeds(getDoc(doc(alice(), MINE)));
    await assertFails(getDoc(doc(tea(), MINE)));
  });
  test("el profesor autor lee la suya mientras exista el vinculo y no la de otro profesor", async () => {
    await seed(env, "users/tb", userDoc({ role: "teacher" }));
    await seed(env, "teacherLinks/tb_alice", { teacherUid: "tb", studentUid: "alice" });
    await assertSucceeds(getDoc(doc(tea(), MINE)));
    await assertFails(getDoc(doc(tea(), `${REV}/tb`)));
    await assertFails(getDoc(doc(tea(), `${REV}/anonimo1`)));
    await assertSucceeds(getDoc(doc(as("tb"), `${REV}/tb`)));
  });
  test("el profesor no lista las revisiones de la grabacion, ni siquiera acotando por su id", async () => {
    await assertFails(getDocs(collection(tea(), REV)));
    await assertFails(getDocs(query(collection(tea(), REV), where(documentId(), "==", "tea"))));
  });
  test("otro alumno, profesor no vinculado y anonimo denegados", async () => {
    await seed(env, "users/bob", recordingUserDoc({ role: "student" }));
    await seed(env, "users/other", userDoc({ role: "teacher" }));
    for (const db of [as("bob"), as("other"), anon()]) {
      await assertFails(getDoc(doc(db, MINE)));
      await assertFails(getDocs(collection(db, REV)));
    }
  });
  test("claims desfasados del profesor denegados", async () => {
    await assertFails(getDoc(doc(tea({ email_verified: true }), MINE)));
  });
  test("collectionGroup('reviews') denegado para dueño, profesor y anonimo", async () => {
    await assertFails(getDocs(collectionGroup(alice(), "reviews")));
    await assertFails(getDocs(query(collectionGroup(tea(), "reviews"), where("reviewerUid", "==", "tea"))));
    await assertFails(getDocs(collectionGroup(anon(), "reviews")));
  });
});
