import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { arrayRemove, arrayUnion, collection, collectionGroup, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, Timestamp } from "firebase/firestore";
import { Claims, createEnv, OK, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;
const PATH = "users/alice/progress/curriculum";
const stored = { completed: ["b1_posture"], updatedAt: new Date("2026-03-01T10:00:00Z") };
/** Progreso valido para escribir: updatedAt debe ser request.time (serverTimestamp). */
const valid = (o: Record<string, unknown> = {}): Record<string, unknown> => ({
  completed: ["b1_posture", "b2_bow_hold"],
  updatedAt: serverTimestamp(),
  ...o,
});

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env, "users/alice", userDoc());
});

const as = (uid: string, claims: Claims = OK) => env.authenticatedContext(uid, claims).firestore();
const alice = () => as("alice");
const anon = () => env.unauthenticatedContext().firestore();

describe("lectura", () => {
  beforeEach(() => seed(env, PATH, stored));
  test("el dueno lee, tambien sin claims al dia", async () => {
    await assertSucceeds(getDoc(doc(alice(), PATH)));
    await assertSucceeds(getDoc(doc(as("alice", { email_verified: false, consentOk: false }), PATH)));
  });
  test("sin list ni collection group, ni siquiera el dueno", async () => {
    await assertFails(getDocs(collection(alice(), "users/alice/progress")));
    await assertFails(getDocs(collectionGroup(alice(), "progress")));
  });
  test("otro usuario y anonimo no leen", async () => {
    await assertFails(getDoc(doc(as("bob"), PATH)));
    await assertFails(getDoc(doc(anon(), PATH)));
  });
});

describe("escritura del dueno", () => {
  test("crea y actualiza con una lista valida", async () => {
    await assertSucceeds(setDoc(doc(alice(), PATH), valid()));
    await assertSucceeds(setDoc(doc(alice(), PATH), valid({ completed: [] })));
  });
  test("la escritura del cliente (merge con arrayUnion/arrayRemove) pasa, tambien sin doc previo", async () => {
    const write = (completed: unknown) => setDoc(doc(alice(), PATH), { completed, updatedAt: serverTimestamp() }, { merge: true });
    await assertSucceeds(write(arrayRemove("b1_posture")));
    await assertSucceeds(write(arrayUnion("b1_posture", "b2_bow_hold")));
    await assertSucceeds(write(arrayUnion("b1_posture")));
    await assertSucceeds(write(arrayRemove("b1_posture")));
    await assertFails(write(arrayUnion("x1_unknown")));
    const snap = await getDoc(doc(alice(), PATH));
    expect(snap.get("completed")).toEqual(["b2_bow_hold"]);
  });
  test("admite las 12 lecciones", async () => {
    const all = [
      "b1_posture", "b2_bow_hold", "b3_open_strings", "b4_tuning",
      "e1_finger_pattern", "e2_d_major_scale", "e3_bow_distribution", "e4_string_crossing",
      "i1_g_major_two_octaves", "i2_dynamics", "i3_third_position", "i4_vibrato",
    ];
    await assertSucceeds(setDoc(doc(alice(), PATH), valid({ completed: all })));
  });
  test("solo el doc `curriculum`", async () => {
    await assertFails(setDoc(doc(alice(), "users/alice/progress/otro"), valid()));
  });
  test.each<[string, Record<string, unknown>]>([
    ["leccion desconocida", { completed: ["x1_unknown"] }],
    ["repetida", { completed: ["b1_posture", "b1_posture"] }],
    ["no lista", { completed: "b1_posture" }],
    ["elemento no string", { completed: [1] }],
    ["updatedAt del cliente", { updatedAt: Timestamp.fromDate(new Date()) }],
    ["campo extra", { extra: true }],
  ])("deniega %s", async (_, over) => {
    await assertFails(setDoc(doc(alice(), PATH), valid(over)));
  });
  test("deniega sin campos obligatorios", async () => {
    await assertFails(setDoc(doc(alice(), PATH), { completed: [] }));
    await assertFails(setDoc(doc(alice(), PATH), { updatedAt: serverTimestamp() }));
  });
  test.each<[string, Claims]>([
    ["consentOk false", { email_verified: true, consentOk: false }],
    ["email sin verificar", { email_verified: false, consentOk: true }],
  ])("deniega con %s", async (_, claims) => {
    await assertFails(setDoc(doc(as("alice", claims), PATH), valid()));
  });
  test("deniega con el doc revocado o con borrado en curso", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "revoked" }));
    await assertFails(setDoc(doc(alice(), PATH), valid()));
    await seed(env, "users/alice", userDoc({ deletion: { requestedAt: new Date() } }));
    await assertFails(setDoc(doc(alice(), PATH), valid()));
  });
  test("otro usuario y anonimo no escriben", async () => {
    await assertFails(setDoc(doc(as("bob"), PATH), valid()));
    await assertFails(setDoc(doc(anon(), PATH), valid()));
  });
  test("el dueno borra siempre, incluso con el consentimiento revocado", async () => {
    await seed(env, PATH, stored);
    await seed(env, "users/alice", userDoc({ consentStatus: "revoked" }));
    await assertSucceeds(deleteDoc(doc(as("alice", { email_verified: false, consentOk: false }), PATH)));
  });
});

describe("profesor vinculado", () => {
  const tea = (claims: Claims = OK) => as("tea", claims);
  beforeEach(async () => {
    await seed(env, PATH, stored);
    await seed(env, "users/tea", userDoc({ displayName: "Profe", role: "teacher" }));
    await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
  });

  test("lee; no escribe ni borra", async () => {
    await assertSucceeds(getDoc(doc(tea(), PATH)));
    await assertFails(setDoc(doc(tea(), PATH), valid()));
    await assertFails(deleteDoc(doc(tea(), PATH)));
  });
  test("sin vinculo, sin consentimiento o con el alumno revocado: denegado", async () => {
    await seed(env, "users/carl", userDoc({ role: "teacher" }));
    await assertFails(getDoc(doc(as("carl"), PATH)));
    await assertFails(getDoc(doc(tea({ email_verified: true, consentOk: false }), PATH)));
    await seed(env, "users/alice", userDoc({ consentStatus: "revoked" }));
    await assertFails(getDoc(doc(tea(), PATH)));
  });
  test("vinculo borrado: el acceso cae", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), "teacherLinks/tea_alice")));
    await assertFails(getDoc(doc(tea(), PATH)));
  });
});
