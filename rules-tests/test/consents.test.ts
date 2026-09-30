import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc } from "firebase/firestore";
import { createEnv, OK, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;
const PATH = "users/alice/consents/privacy_policy_v1_self";
const consent = { type: "privacy_policy", version: 1, grantedBy: "self", timestamp: new Date("2026-01-01T00:00:00Z") };

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await seed(env, "users/alice", userDoc());
  await seed(env, PATH, consent);
});

const alice = () => env.authenticatedContext("alice", OK).firestore();
const bob = () => env.authenticatedContext("bob", OK).firestore();
const anon = () => env.unauthenticatedContext().firestore();

describe("users/{uid}/consents/{id}", () => {
  test("el dueño lee un consent", async () => {
    await assertSucceeds(getDoc(doc(alice(), PATH)));
  });

  test("el dueño lista sus consents", async () => {
    await assertSucceeds(getDocs(collection(alice(), "users/alice/consents")));
  });

  test("el dueño sin claims al día (estados de espera) también lee", async () => {
    await assertSucceeds(getDoc(doc(env.authenticatedContext("alice", { email_verified: false }).firestore(), PATH)));
  });

  test("otro usuario no lee ni lista", async () => {
    await assertFails(getDoc(doc(bob(), PATH)));
    await assertFails(getDocs(collection(bob(), "users/alice/consents")));
  });

  test("un anónimo no lee ni lista", async () => {
    await assertFails(getDoc(doc(anon(), PATH)));
    await assertFails(getDocs(collection(anon(), "users/alice/consents")));
  });

  test("el dueño no crea, actualiza ni borra (append-only, solo Functions)", async () => {
    await assertFails(setDoc(doc(alice(), "users/alice/consents/terms_v1_self"), consent));
    await assertFails(updateDoc(doc(alice(), PATH), { version: 2 }));
    await assertFails(deleteDoc(doc(alice(), PATH)));
  });

  test("un tercero no escribe ni borra", async () => {
    await assertFails(setDoc(doc(bob(), "users/alice/consents/terms_v1_self"), consent));
    await assertFails(updateDoc(doc(bob(), PATH), { version: 2 }));
    await assertFails(deleteDoc(doc(bob(), PATH)));
  });
});
