import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, collectionGroup, deleteDoc, doc, getDoc, getDocs, orderBy, query, setDoc, updateDoc, where } from "firebase/firestore";
import { Claims, createEnv, OK, recordingUserDoc, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;
const T0 = new Date("2026-03-01T10:00:00Z");
/** Doc de grabacion tal y como lo deja `finalizeRecording` (solo Admin escribe). */
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
const R = (id: string) => `users/alice/recordings/${id}`;

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env, "users/alice", recordingUserDoc({ role: "student" }));
  await seed(env, "users/tea", userDoc({ displayName: "Profe", role: "teacher" }));
  await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
  await seed(env, R("ready1"), rec());
  await seed(env, R("pending1"), rec({ status: "pending" }));
  await seed(env, R("rejected1"), rec({ status: "rejected", rejectReason: "size" }));
});

const as = (uid: string, claims: Claims = OK) => env.authenticatedContext(uid, claims).firestore();
const alice = () => as("alice");
const tea = (claims: Claims = OK) => as("tea", claims);
const anon = () => env.unauthenticatedContext().firestore();
const unlink = () => env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), "teacherLinks/tea_alice")));
/** Reemplaza el perfil de alice; un valor `undefined` elimina el campo. */
const patchAlice = (o: Record<string, unknown>) =>
  seed(env, "users/alice", Object.fromEntries(Object.entries(recordingUserDoc({ role: "student", ...o })).filter(([, v]) => v !== undefined)));
const readyList = (db: ReturnType<typeof alice>) =>
  getDocs(query(collection(db, "users/alice/recordings"), where("status", "==", "ready"), orderBy("createdAt", "desc")));

describe("dueño (REQ-SEC-R01, REQ-REC-07)", () => {
  test("get de cualquier estado y list sin filtro", async () => {
    for (const id of ["ready1", "pending1", "rejected1"]) await assertSucceeds(getDoc(doc(alice(), R(id))));
    await assertSucceeds(getDocs(collection(alice(), "users/alice/recordings")));
  });
  test("puede leer lo suyo aunque haya revocado el consentimiento de grabacion o este borrandose", async () => {
    await patchAlice({ recordingConsent: "revoked" });
    await assertSucceeds(getDoc(doc(alice(), R("ready1"))));
    await patchAlice({ deletion: { state: "pending" } });
    await assertSucceeds(getDoc(doc(alice(), R("ready1"))));
  });
  test("borra sin exigir consentimiento ni claims (supresion, A12)", async () => {
    await patchAlice({ recordingConsent: "revoked", consentStatus: "revoked" });
    await assertSucceeds(deleteDoc(doc(as("alice", {}), R("ready1"))));
    await assertSucceeds(deleteDoc(doc(alice(), R("rejected1"))));
  });
  test("no crea ni actualiza (solo Admin escribe, REQ-REC-06)", async () => {
    await assertFails(setDoc(doc(alice(), R("nueva")), rec()));
    await assertFails(updateDoc(doc(alice(), R("ready1")), { durationSec: 1 }));
    await assertFails(updateDoc(doc(alice(), R("pending1")), { status: "ready" }));
  });
  test("collectionGroup('recordings') denegado", async () => {
    await assertFails(getDocs(collectionGroup(alice(), "recordings")));
    await assertFails(getDocs(query(collectionGroup(alice(), "recordings"), where("status", "==", "ready"))));
  });
});

describe("profesor vinculado", () => {
  test("lee solo las ready", async () => {
    await assertSucceeds(getDoc(doc(tea(), R("ready1"))));
    await assertFails(getDoc(doc(tea(), R("pending1"))));
    await assertFails(getDoc(doc(tea(), R("rejected1"))));
  });
  test("lista con filtro status == ready; sin filtro o pidiendo pending, denegado", async () => {
    const snap = await assertSucceeds(readyList(tea()));
    expect(snap.docs.map((d) => d.id)).toEqual(["ready1"]);
    await assertFails(getDocs(collection(tea(), "users/alice/recordings")));
    await assertFails(getDocs(query(collection(tea(), "users/alice/recordings"), where("status", "==", "pending"))));
  });
  test("no crea, actualiza ni borra", async () => {
    await assertFails(setDoc(doc(tea(), R("nueva")), rec()));
    await assertFails(updateDoc(doc(tea(), R("ready1")), { durationSec: 1 }));
    await assertFails(deleteDoc(doc(tea(), R("ready1"))));
  });
  test.each<[string, () => Promise<void>]>([
    ["sin vinculo", () => unlink()],
    ["alumno sin recordingConsent", () => patchAlice({ recordingConsent: undefined })],
    ["alumno con recordingConsent revocado", () => patchAlice({ recordingConsent: "revoked" })],
    ["alumno con recordingConsent parental_pending", () => patchAlice({ recordingConsent: "parental_pending" })],
    ["alumno con version de consentimiento de grabacion 0 (paridad con storage.rules)", () => patchAlice({ recordingConsentVersion: 0 })],
    ["alumno con version de consentimiento de grabacion ausente", () => patchAlice({ recordingConsentVersion: undefined })],
    ["alumno con consentimiento base revocado", () => patchAlice({ consentStatus: "revoked" })],
    ["alumno en borrado", () => patchAlice({ deletion: { state: "pending" } })],
    ["profesor con consentimiento base revocado", () => seed(env, "users/tea", userDoc({ role: "teacher", consentStatus: "revoked" }))],
  ])("denegado: %s", async (_, setup) => {
    await setup();
    await assertFails(getDoc(doc(tea(), R("ready1"))));
    await assertFails(readyList(tea()));
  });
  test("denegado con claims desfasados (sin consentOk o sin email verificado)", async () => {
    await assertFails(getDoc(doc(tea({ email_verified: true }), R("ready1"))));
    await assertFails(getDoc(doc(tea({ consentOk: true }), R("ready1"))));
  });
  test("el rol del claim no cuenta: sin vinculo, role=teacher en el claim no abre nada", async () => {
    await unlink();
    await assertFails(getDoc(doc(tea({ ...OK, role: "teacher" }), R("ready1"))));
  });
});

describe("otros usuarios", () => {
  test("otro alumno, profesor no vinculado y anonimo no leen ni escriben", async () => {
    await seed(env, "users/bob", recordingUserDoc({ role: "student" }));
    await seed(env, "users/other", userDoc({ role: "teacher" }));
    for (const db of [as("bob"), as("other"), anon()]) {
      await assertFails(getDoc(doc(db, R("ready1"))));
      await assertFails(readyList(db));
      await assertFails(deleteDoc(doc(db, R("ready1"))));
      await assertFails(setDoc(doc(db, R("nueva")), rec()));
    }
  });
});

describe("demostracion del profesor (sharedWith, REQ-REC-07)", () => {
  const D = "users/tea/recordings/demo1";
  const demo = (o: Record<string, unknown> = {}) =>
    rec({ sharedWith: "alice", assignmentId: "as1", objectPath: "users/tea/demos/demo1.m4a", ...o });
  beforeEach(async () => {
    await seed(env, D, demo());
    await seed(env, "users/tea/recordings/demoPending", demo({ status: "pending" }));
    await seed(env, "users/tea/recordings/privada", rec({ objectPath: "users/tea/recordings/privada.m4a" }));
  });
  const demosOf = (db: ReturnType<typeof alice>) =>
    getDocs(
      query(collection(db, "users/tea/recordings"), where("sharedWith", "==", "alice"), where("status", "==", "ready"), orderBy("createdAt", "desc")),
    );

  test("el alumno destinatario con vinculo lee la demo ready y la lista filtrada", async () => {
    await assertSucceeds(getDoc(doc(alice(), D)));
    const snap = await assertSucceeds(demosOf(alice()));
    expect(snap.docs.map((d) => d.id)).toEqual(["demo1"]);
  });
  test("no lee demos pending, grabaciones privadas del profesor ni lista sin filtro", async () => {
    await assertFails(getDoc(doc(alice(), "users/tea/recordings/demoPending")));
    await assertFails(getDoc(doc(alice(), "users/tea/recordings/privada")));
    await assertFails(getDocs(collection(alice(), "users/tea/recordings")));
  });
  test("lista del destinatario solo con sharedWith (sin status) o solo con status ready (sin sharedWith), denegada", async () => {
    await assertFails(getDocs(query(collection(alice(), "users/tea/recordings"), where("sharedWith", "==", "alice"))));
    await assertFails(getDocs(query(collection(alice(), "users/tea/recordings"), where("status", "==", "ready"))));
  });
  test("el profesor (dueño) la lee y la borra; el alumno no la borra ni escribe", async () => {
    await assertSucceeds(getDoc(doc(tea(), D)));
    await assertFails(deleteDoc(doc(alice(), D)));
    await assertFails(updateDoc(doc(alice(), D), { sharedWith: "bob" }));
    await assertSucceeds(deleteDoc(doc(tea(), D)));
  });
  test("otro alumno vinculado (no destinatario) y alumno sin vinculo, denegado", async () => {
    await seed(env, "users/carol", recordingUserDoc({ role: "student" }));
    await seed(env, "teacherLinks/tea_carol", { teacherUid: "tea", studentUid: "carol" });
    await assertFails(getDoc(doc(as("carol"), D)));
    await unlink();
    await assertFails(getDoc(doc(alice(), D)));
    await assertFails(demosOf(alice()));
  });
  test("claims desfasados del alumno (sin consentOk / email) denegados, como en storage.rules", async () => {
    await assertFails(getDoc(doc(as("alice", { email_verified: true }), D)));
    await assertFails(getDoc(doc(as("alice", { consentOk: true }), D)));
  });
  test("anonimo y collectionGroup denegados", async () => {
    await assertFails(getDoc(doc(anon(), D)));
    await assertFails(getDocs(query(collectionGroup(alice(), "recordings"), where("sharedWith", "==", "alice"))));
  });
});
