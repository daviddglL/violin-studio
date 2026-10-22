import { assertFails, RulesTestContext, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc } from "firebase/firestore";
import { createEnv, OK, seed } from "./helpers";

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
});

const contexts: [string, () => RulesTestContext][] = [
  ["anónimo", () => env.unauthenticatedContext()],
  ["dueño autenticado", () => env.authenticatedContext("alice", OK)],
];

// Colecciones del servidor: solo Admin SDK. Un match explícito por cada una (convención de fase 2).
describe.each(["guardianRequests", "guardianEmailLimits", "mail", "maintenance", "appConfig", "coleccionDesconocida"])("%s", (name) => {
  describe.each(contexts)("como %s", (_, ctx) => {
    beforeEach(async () => {
      await seed(env, `${name}/x`, { uid: "alice" });
    });
    test("no lee, lista, crea, actualiza ni borra", async () => {
      const db = ctx().firestore();
      await assertFails(getDoc(doc(db, `${name}/x`)));
      await assertFails(getDocs(collection(db, name)));
      await assertFails(setDoc(doc(db, `${name}/nuevo`), { uid: "alice" }));
      await assertFails(updateDoc(doc(db, `${name}/x`), { uid: "bob" }));
      await assertFails(deleteDoc(doc(db, `${name}/x`)));
    });
  });
});

// B5a (revision fresca, W2): marcas de idempotencia del decremento del cupo bajo el propio usuario; solo Admin SDK.
describe("users/{uid}/recordingCountLedger", () => {
  describe.each(contexts)("como %s", (_, ctx) => {
    beforeEach(async () => {
      await seed(env, "users/alice", { consentStatus: "granted" });
      await seed(env, "users/alice/recordingCountLedger/r1_1_x", { expireAt: new Date() });
    });
    test("ni el dueno lee, lista, crea, actualiza ni borra", async () => {
      const db = ctx().firestore();
      const path = "users/alice/recordingCountLedger";
      await assertFails(getDoc(doc(db, `${path}/r1_1_x`)));
      await assertFails(getDocs(collection(db, path)));
      await assertFails(setDoc(doc(db, `${path}/nuevo`), { expireAt: new Date() }));
      await assertFails(updateDoc(doc(db, `${path}/r1_1_x`), { expireAt: new Date() }));
      await assertFails(deleteDoc(doc(db, `${path}/r1_1_x`)));
    });
  });
});
