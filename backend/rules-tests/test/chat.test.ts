import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, orderBy, query, serverTimestamp, setDoc, Timestamp, updateDoc, where } from "firebase/firestore";
import { Claims, createEnv, OK, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;
const COL = "chatMessages";
const OLD = `${COL}/m1`;
const NEW = `${COL}/new1`;
const T0 = new Date("2026-03-01T10:00:00Z");
const DAYS_180_MS = 180 * 24 * 60 * 60 * 1000;

const stored = (o: Record<string, unknown> = {}) => ({
  teacherUid: "tea",
  studentUid: "alice",
  senderUid: "alice",
  text: "Hola, profe",
  createdAt: T0,
  expireAt: new Date(T0.getTime() + DAYS_180_MS),
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
  await seed(env, "users/eve", userDoc({ displayName: "Eve", role: "student" }));
  await seed(env, "teacherLinks/tea_alice", { teacherUid: "tea", studentUid: "alice" });
  await seed(env, OLD, stored());
});

const as = (uid: string, claims: Claims = OK) => env.authenticatedContext(uid, claims).firestore();
const unlink = () => seed(env, "teacherLinks/tea_alice", { teacherUid: "otro", studentUid: "alice" });

/** Mensaje como lo escribe la app: `createdAt` del servidor y `expireAt` a 180 dias con el reloj del dispositivo. */
const message = (o: Record<string, unknown> = {}): Record<string, unknown> => ({
  teacherUid: "tea",
  studentUid: "alice",
  senderUid: "alice",
  text: "Hola",
  createdAt: serverTimestamp(),
  expireAt: Timestamp.fromDate(new Date(Date.now() + DAYS_180_MS)),
  ...o,
});

describe("lectura (las dos partes de un vinculo vivo)", () => {
  test("alumno y profesor leen el mensaje y listan filtrando por el par", async () => {
    for (const uid of ["alice", "tea"]) {
      await assertSucceeds(getDoc(doc(as(uid), OLD)));
      const q = query(collection(as(uid), COL), where("teacherUid", "==", "tea"), where("studentUid", "==", "alice"), orderBy("createdAt"));
      await assertSucceeds(getDocs(q));
    }
  });
  test("un tercero no lee ni lista el chat ajeno", async () => {
    await assertFails(getDoc(doc(as("eve"), OLD)));
    await assertFails(getDocs(query(collection(as("eve"), COL), where("teacherUid", "==", "tea"), where("studentUid", "==", "alice"))));
  });
  test("una consulta sin filtrar por el par se deniega", async () => {
    await assertFails(getDocs(query(collection(as("alice"), COL), where("studentUid", "==", "alice"))));
    await assertFails(getDocs(collection(as("alice"), COL)));
  });
  test.each<[string, Claims]>([
    ["sin email verificado", { email_verified: false, consentOk: true }],
    ["sin consentimiento", { email_verified: true, consentOk: false }],
  ])("%s no lee", async (_, claims) => {
    await assertFails(getDoc(doc(as("alice", claims), OLD)));
  });
  test("sin vinculo vivo nadie lee", async () => {
    await unlink();
    await assertFails(getDoc(doc(as("alice"), OLD)));
    await assertFails(getDoc(doc(as("tea"), OLD)));
  });
  test("con el doc de la otra parte inactivo (borrado en curso o consentimiento retirado) no se lee", async () => {
    await seed(env, "users/tea", userDoc({ role: "teacher", deletion: { state: "pending" } }));
    await assertFails(getDoc(doc(as("alice"), OLD)));
    await seed(env, "users/tea", userDoc({ role: "teacher", consentStatus: "revoked" }));
    await assertFails(getDoc(doc(as("alice"), OLD)));
  });
  test("sin sesion no se lee", async () => {
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), OLD)));
  });
});

const inDays = (d: number) => Timestamp.fromDate(new Date(Date.now() + d * 24 * 60 * 60 * 1000));

describe("crear", () => {
  test("las dos partes escriben con su propio uid como autor", async () => {
    await assertSucceeds(setDoc(doc(as("alice"), NEW), message()));
    await assertSucceeds(setDoc(doc(as("tea"), `${COL}/new2`), message({ senderUid: "tea" })));
  });
  test("expireAt entre 170 y 181 dias se admite (reloj desviado o mensaje que espero sin conexion)", async () => {
    await assertSucceeds(setDoc(doc(as("alice"), NEW), message({ expireAt: inDays(171) })));
    await assertSucceeds(setDoc(doc(as("alice"), `${COL}/new2`), message({ expireAt: inDays(180.9) })));
  });
  test.each<[string, number]>([
    ["demasiado pronto", 100],
    ["mas alla de 181 dias", 200],
  ])("expireAt %s se deniega (el cliente no elige la retencion)", async (_, days) => {
    await assertFails(setDoc(doc(as("alice"), NEW), message({ expireAt: inDays(days) })));
  });
  test.each<[string, Record<string, unknown>]>([
    ["texto vacio", { text: "" }],
    ["texto de 1001", { text: "a".repeat(1001) }],
    ["texto no string", { text: 3 }],
    ["campo extra", { extra: 1 }],
    ["sin expireAt", { expireAt: undefined }],
    ["expireAt no timestamp", { expireAt: "pronto" }],
    ["createdAt del cliente", { createdAt: Timestamp.fromDate(T0) }],
    ["autor suplantado", { senderUid: "tea" }],
  ])("denegado: %s", async (_, over) => {
    const data = message(over);
    for (const k of Object.keys(data)) if (data[k] === undefined) delete data[k];
    await assertFails(setDoc(doc(as("alice"), NEW), data));
  });
  test("un tercero no escribe en el chat de otro par", async () => {
    await assertFails(setDoc(doc(as("eve"), NEW), message({ senderUid: "eve" })));
  });
  test("sin vinculo vivo no se escribe", async () => {
    await unlink();
    await assertFails(setDoc(doc(as("tea"), NEW), message({ senderUid: "tea" })));
  });
  test("id de mas de 64 caracteres se deniega", async () => {
    await assertFails(setDoc(doc(as("alice"), `${COL}/${"x".repeat(65)}`), message()));
  });
});

describe("editar y borrar", () => {
  test("nadie edita un mensaje", async () => {
    await assertFails(updateDoc(doc(as("alice"), OLD), { text: "otro" }));
    await assertFails(updateDoc(doc(as("tea"), OLD), { text: "otro" }));
  });
  test("solo el autor borra su mensaje, incluso ya desvinculado", async () => {
    await assertFails(deleteDoc(doc(as("tea"), OLD)));
    await assertFails(deleteDoc(doc(as("eve"), OLD)));
    await unlink();
    await assertSucceeds(deleteDoc(doc(as("alice"), OLD)));
  });
});
