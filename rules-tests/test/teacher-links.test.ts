import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from "firebase/firestore";
import { Claims, createEnv, OK, seed } from "./helpers";

let env: RulesTestEnvironment;
const LINK = "teacherLinks/tea_stu";
const link = {
  teacherUid: "tea",
  studentUid: "stu",
  teacherDisplayName: "Profe",
  studentDisplayName: "Alumno",
  studentInstrument: "violin",
  ackVersion: 1,
  createdAt: new Date("2026-03-01T10:00:00Z"),
};

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env, LINK, link);
  await seed(env, "teacherLinks/tea_other", { ...link, studentUid: "other" });
  await seed(env, "teacherCodes/abc", { teacherUid: "tea" });
});

const as = (uid: string, claims: Claims = OK) => env.authenticatedContext(uid, claims).firestore();
const col = (db: ReturnType<typeof as>) => collection(db, "teacherLinks");

describe("teacherLinks lectura (REQ-SEC-T02)", () => {
  test("el profesor y el alumno leen su vínculo", async () => {
    await assertSucceeds(getDoc(doc(as("tea"), LINK)));
    await assertSucceeds(getDoc(doc(as("stu"), LINK)));
  });
  test("un tercero y el anónimo no leen", async () => {
    await assertFails(getDoc(doc(as("eve"), LINK)));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), LINK)));
  });
  test("listar con filtro por el propio uid funciona", async () => {
    const t = await assertSucceeds(getDocs(query(col(as("tea")), where("teacherUid", "==", "tea"))));
    expect(t.size).toBe(2);
    const s = await assertSucceeds(getDocs(query(col(as("stu")), where("studentUid", "==", "stu"))));
    expect(s.size).toBe(1);
  });
  test("listar sin filtro o filtrando por otro uid se deniega", async () => {
    await assertFails(getDocs(col(as("tea"))));
    await assertFails(getDocs(query(col(as("tea")), where("teacherUid", "==", "other"))));
    await assertFails(getDocs(query(col(as("stu")), where("teacherUid", "==", "tea"))));
    await assertFails(getDocs(query(col(as("eve")), where("studentUid", "==", "stu"))));
  });
});

describe("teacherLinks escritura (REQ-SEC-T03)", () => {
  test("nadie crea, actualiza ni borra desde el cliente", async () => {
    for (const uid of ["tea", "stu", "eve"]) {
      const db = as(uid);
      await assertFails(setDoc(doc(db, "teacherLinks/tea_new"), { ...link, studentUid: "new" }));
      await assertFails(updateDoc(doc(db, LINK), { studentDisplayName: "x" }));
      await assertFails(deleteDoc(doc(db, LINK)));
    }
  });
});

describe("teacherCodes (REQ-SEC-T03)", () => {
  test("deny total", async () => {
    const db = as("tea");
    await assertFails(getDoc(doc(db, "teacherCodes/abc")));
    await assertFails(getDocs(query(collection(db, "teacherCodes"), where("teacherUid", "==", "tea"))));
    await assertFails(setDoc(doc(db, "teacherCodes/new"), { teacherUid: "tea" }));
    await assertFails(deleteDoc(doc(db, "teacherCodes/abc")));
  });
});

describe("teacherLinks endurecimiento", () => {
  test("email sin verificar no lee ni lista", async () => {
    const t = as("tea", { email_verified: false, consentOk: true });
    await assertFails(getDoc(doc(t, LINK)));
    await assertFails(getDocs(query(col(t), where("teacherUid", "==", "tea"))));
  });
  test("el profesor no lista filtrando por studentUid de su alumno", async () => {
    await assertFails(getDocs(query(col(as("tea")), where("studentUid", "==", "stu"))));
  });
});
