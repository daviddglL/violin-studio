import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, collectionGroup, deleteDoc, deleteField, doc, getDoc, getDocs, query, serverTimestamp, setDoc, Timestamp, updateDoc, where } from "firebase/firestore";
import { Claims, createEnv, OK, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;
const COL = "users/alice/assignments";
const OPEN = `${COL}/open1`;
const DONE = `${COL}/done1`;
const NEW = `${COL}/new1`;
const T0 = new Date("2026-03-01T10:00:00Z");
const stored = (o: Record<string, unknown> = {}) => ({
  teacherUid: "tea",
  title: "Escalas",
  status: "open",
  createdAt: T0,
  updatedAt: T0,
  ...o,
});
/** Tarea valida para crear: createdAt y updatedAt deben ser request.time. */
const valid = (o: Record<string, unknown> = {}): Record<string, unknown> => ({
  teacherUid: "tea",
  title: "Escalas",
  description: "Do mayor, dos octavas",
  dueAt: Timestamp.fromDate(new Date("2026-12-01T00:00:00Z")),
  status: "open",
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
  await seed(env, "users/alice", userDoc({ role: "student" }));
  await seed(env, "users/tea", userDoc({ displayName: "Profe", role: "teacher" }));
  await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
  await seed(env, OPEN, stored());
  await seed(env, DONE, stored({ status: "done", doneAt: T0 }));
});

const as = (uid: string, claims: Claims = OK) => env.authenticatedContext(uid, claims).firestore();
const tea = (claims: Claims = OK) => as("tea", claims);
const alice = () => as("alice");
const anon = () => env.unauthenticatedContext().firestore();
const unlink = () => env.withSecurityRulesDisabled(async (ctx) => deleteDoc(doc(ctx.firestore(), "teacherLinks/tea_alice")));

describe("create (REQ-SEC-T05, REQ-ASG-01, REQ-ASG-02)", () => {
  test("profesor vinculado con campos exactos crea; description y dueAt son opcionales", async () => {
    await assertSucceeds(setDoc(doc(tea(), NEW), valid()));
    const minimal = valid();
    delete minimal.description;
    delete minimal.dueAt;
    await assertSucceeds(setDoc(doc(tea(), `${COL}/new2`), minimal));
  });
  test.each<[string, Record<string, unknown>]>([
    ["titulo vacio", { title: "" }],
    ["titulo 121", { title: "a".repeat(121) }],
    ["titulo con control", { title: "a\u0000b" }],
    ["descripcion 1001", { description: "a".repeat(1001) }],
    ["campo extra", { extra: 1 }],
    ["status done", { status: "done" }],
    ["doneAt presente", { doneAt: Timestamp.now() }],
    ["createdAt del cliente", { createdAt: Timestamp.fromDate(T0) }],
    ["updatedAt del cliente", { updatedAt: Timestamp.fromDate(T0) }],
    ["teacherUid ajeno", { teacherUid: "otro" }],
    ["dueAt no timestamp", { dueAt: "manana" }],
    ["description null (se omite, no se escribe null)", { description: null }],
    ["dueAt null (se omite, no se escribe null)", { dueAt: null }],
    ["titulo con espacio inicial", { title: " Escalas" }],
    ["titulo con espacio final", { title: "Escalas " }],
    ["titulo con salto de linea", { title: "Esca\nlas" }],
  ])("denegado: %s", async (_, over) => {
    await assertFails(setDoc(doc(tea(), NEW), valid(over)));
  });
  test("limites exactos 1/120 y 1000 permitidos", async () => {
    await assertSucceeds(setDoc(doc(tea(), NEW), valid({ title: "a", description: "a".repeat(1000) })));
    await assertSucceeds(setDoc(doc(tea(), `${COL}/new2`), valid({ title: "a".repeat(120) })));
  });
  test("id de mas de 64 caracteres denegado", async () => {
    await assertFails(setDoc(doc(tea(), `${COL}/${"x".repeat(65)}`), valid()));
  });
  test("sin campos obligatorios denegado", async () => {
    const sinTitulo = valid();
    delete sinTitulo.title;
    await assertFails(setDoc(doc(tea(), NEW), sinTitulo));
  });
  test("no vinculado, vinculo borrado, alumno, otro profesor y anonimo: denegado", async () => {
    await seed(env, "users/carl", userDoc({ role: "teacher" }));
    await assertFails(setDoc(doc(as("carl"), NEW), valid({ teacherUid: "carl" })));
    await assertFails(setDoc(doc(alice(), NEW), valid()));
    await assertFails(setDoc(doc(anon(), NEW), valid()));
    await unlink();
    await assertFails(setDoc(doc(tea(), NEW), valid()));
  });
  test.each<[string, Claims]>([
    ["email sin verificar", { email_verified: false, consentOk: true }],
    ["consentOk false", { email_verified: true, consentOk: false }],
  ])("claims %s denegado", async (_, claims) => {
    await assertFails(setDoc(doc(tea(claims), NEW), valid()));
  });
  test.each([
    ["alumno", "users/alice", { role: "student", consentStatus: "revoked" }],
    ["alumno", "users/alice", { role: "student", deletion: { startedAt: new Date() } }],
    ["profesor", "users/tea", { role: "teacher", consentStatus: "pending" }],
    ["profesor", "users/tea", { role: "teacher", deletion: { startedAt: new Date() } }],
  ])("doc de %s inactivo (%j): denegado (S10, token vigente 1 h)", async (_, path, over) => {
    await seed(env, path, userDoc(over));
    await assertFails(setDoc(doc(tea(), NEW), valid()));
  });
});

describe("update (REQ-ASG-02, REQ-ASG-03)", () => {
  test("profesor autor edita title/description/dueAt con updatedAt == request.time", async () => {
    await assertSucceeds(updateDoc(doc(tea(), OPEN), { title: "Arpegios", description: "x", dueAt: Timestamp.now(), updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(tea(), DONE), { title: "Arpegios", updatedAt: serverTimestamp() }));
  });
  test.each<[string, Record<string, unknown>]>([
    ["sin updatedAt", { title: "x" }],
    ["updatedAt del cliente", { title: "x", updatedAt: Timestamp.now() }],
    ["status", { status: "done", updatedAt: serverTimestamp() }],
    ["doneAt", { doneAt: Timestamp.now(), updatedAt: serverTimestamp() }],
    ["teacherUid", { teacherUid: "otro", updatedAt: serverTimestamp() }],
    ["createdAt", { createdAt: serverTimestamp(), updatedAt: serverTimestamp() }],
    ["titulo 121", { title: "a".repeat(121), updatedAt: serverTimestamp() }],
    ["descripcion 1001", { description: "a".repeat(1001), updatedAt: serverTimestamp() }],
    ["campo extra", { extra: 1, updatedAt: serverTimestamp() }],
  ])("profesor: %s denegado", async (_, patch) => {
    await assertFails(updateDoc(doc(tea(), OPEN), patch));
  });
  test("profesor no autor, desvinculado o con doc inactivo: denegado", async () => {
    await seed(env, "users/carl", userDoc({ role: "teacher" }));
    await seed(env, "teacherLinks/carl_alice", { teacherUid: "carl", studentUid: "alice" });
    await assertFails(updateDoc(doc(as("carl"), OPEN), { title: "x", updatedAt: serverTimestamp() }));
    await seed(env, "users/alice", userDoc({ role: "student", consentStatus: "revoked" }));
    await assertFails(updateDoc(doc(tea(), OPEN), { title: "x", updatedAt: serverTimestamp() }));
    await seed(env, "users/alice", userDoc({ role: "student" }));
    await unlink();
    await assertFails(updateDoc(doc(tea(), OPEN), { title: "x", updatedAt: serverTimestamp() }));
  });
  test("alumno completa con doneAt == request.time y reabre sin doneAt", async () => {
    await assertSucceeds(updateDoc(doc(alice(), OPEN), { status: "done", doneAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(doc(alice(), DONE), { status: "open", doneAt: deleteField() }));
  });
  test.each<[string, string, Record<string, unknown>]>([
    ["completar sin doneAt", OPEN, { status: "done" }],
    ["completar con doneAt del cliente", OPEN, { status: "done", doneAt: Timestamp.now() }],
    ["reabrir conservando doneAt", DONE, { status: "open" }],
    ["reabrir con doneAt", DONE, { status: "open", doneAt: serverTimestamp() }],
    ["status invalido", OPEN, { status: "x" }],
    ["solo doneAt", OPEN, { doneAt: serverTimestamp() }],
    ["title", OPEN, { status: "done", doneAt: serverTimestamp(), title: "x" }],
    ["teacherUid", OPEN, { status: "done", doneAt: serverTimestamp(), teacherUid: "alice" }],
    ["updatedAt", OPEN, { status: "done", doneAt: serverTimestamp(), updatedAt: serverTimestamp() }],
  ])("alumno: %s denegado", async (_, path, patch) => {
    await assertFails(updateDoc(doc(alice(), path), patch));
  });
  test.each<[string, Claims, Record<string, unknown>]>([
    ["email sin verificar", { email_verified: false, consentOk: true }, {}],
    ["consentOk false", { email_verified: true, consentOk: false }, {}],
    ["consentimiento revocado en el doc", OK, { consentStatus: "revoked" }],
    ["deletion en curso", OK, { deletion: { startedAt: new Date() } }],
  ])("alumno con %s: denegado", async (_, claims, over) => {
    await seed(env, "users/alice", userDoc({ role: "student", ...over }));
    await assertFails(updateDoc(doc(as("alice", claims), OPEN), { status: "done", doneAt: serverTimestamp() }));
  });
  test("otro alumno y anonimo no actualizan", async () => {
    await seed(env, "users/bob", userDoc());
    await assertFails(updateDoc(doc(as("bob"), OPEN), { status: "done", doneAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(anon(), OPEN), { status: "done", doneAt: serverTimestamp() }));
  });
});

describe("lectura y borrado (REQ-ASG-04)", () => {
  beforeEach(async () => {
    await seed(env, "users/carl", userDoc({ role: "teacher" }));
    await seed(env, "teacherLinks/carl_alice", { teacherUid: "carl", studentUid: "alice" });
    await seed(env, `${COL}/carl1`, stored({ teacherUid: "carl" }));
  });
  test("alumno lee y lista todas las suyas (de cualquier profesor)", async () => {
    await assertSucceeds(getDoc(doc(alice(), `${COL}/carl1`)));
    expect((await assertSucceeds(getDocs(collection(alice(), COL)))).size).toBe(3);
  });
  test("profesor lee y lista solo las que creo (list con filtro teacherUid == me)", async () => {
    await assertSucceeds(getDoc(doc(tea(), OPEN)));
    await assertSucceeds(getDoc(doc(tea(), DONE)));
    await assertFails(getDoc(doc(tea(), `${COL}/carl1`)));
    expect((await assertSucceeds(getDocs(query(collection(tea(), COL), where("teacherUid", "==", "tea"))))).size).toBe(2);
    await assertFails(getDocs(collection(tea(), COL)));
    await assertFails(getDocs(query(collection(tea(), COL), where("teacherUid", "==", "carl"))));
  });
  test("profesor desvinculado ya no lee ni siquiera las done; el alumno si", async () => {
    await unlink();
    await assertFails(getDoc(doc(tea(), DONE)));
    await assertFails(getDocs(query(collection(tea(), COL), where("teacherUid", "==", "tea"))));
    await assertSucceeds(getDoc(doc(alice(), DONE)));
  });
  test("otro alumno, profesor ajeno, anonimo y collectionGroup: denegado", async () => {
    await seed(env, "users/bob", userDoc());
    await seed(env, "users/dan", userDoc({ role: "teacher" }));
    for (const db of [as("bob"), as("dan"), anon()]) {
      await assertFails(getDoc(doc(db, OPEN)));
      await assertFails(getDocs(collection(db, COL)));
    }
    await assertFails(getDocs(collectionGroup(alice(), "assignments")));
    await assertFails(getDocs(query(collectionGroup(tea(), "assignments"), where("teacherUid", "==", "tea"))));
  });
  test("borra solo el profesor autor vinculado", async () => {
    await assertFails(deleteDoc(doc(alice(), OPEN)));
    await assertFails(deleteDoc(doc(as("carl"), OPEN)));
    await assertFails(deleteDoc(doc(tea(), `${COL}/carl1`)));
    await unlink();
    await assertFails(deleteDoc(doc(tea(), OPEN)));
    await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
    await assertSucceeds(deleteDoc(doc(tea(), OPEN)));
    await assertSucceeds(deleteDoc(doc(tea(), DONE)));
  });
  test("subcoleccion anidada bajo una tarea denegada", async () => {
    await assertFails(setDoc(doc(tea(), `${OPEN}/x/y`), { v: 1 }));
  });
});

// Coste: isLinkedTeacher = 3 get (vinculo + 2 usuarios) <= 10. Alumno: 0 en lectura, 1 en update (userDocActive).
describe("coste (REQ-SEC-T05)", () => {
  test("listar 60 tareas como profesor funciona dentro del limite de accesos", async () => {
    for (let i = 0; i < 60; i++) await seed(env, `${COL}/b${i}`, stored());
    const snap = await assertSucceeds(getDocs(query(collection(tea(), COL), where("teacherUid", "==", "tea"))));
    expect(snap.size).toBe(62);
  });
});

describe("profesor inactivo en update y delete (S10)", () => {
  test.each([
    ["alumno revocado", "users/alice", { role: "student", consentStatus: "revoked" }],
    ["alumno con deletion", "users/alice", { role: "student", deletion: { startedAt: new Date() } }],
    ["profesor revocado", "users/tea", { role: "teacher", consentStatus: "revoked" }],
    ["profesor con deletion", "users/tea", { role: "teacher", deletion: { startedAt: new Date() } }],
  ])("%s: el profesor no edita ni borra", async (_, path, over) => {
    await seed(env, path, userDoc(over));
    await assertFails(updateDoc(doc(tea(), OPEN), { title: "x", updatedAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(tea(), OPEN)));
  });
  test("edicion con titulo con espacios o salto de linea denegada", async () => {
    for (const title of [" x", "x ", "x\ny"]) await assertFails(updateDoc(doc(tea(), OPEN), { title, updatedAt: serverTimestamp() }));
  });
});
