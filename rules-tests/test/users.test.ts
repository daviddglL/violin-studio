import { assertFails, assertSucceeds, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, doc, getDoc, getDocs } from "firebase/firestore";
import { createEnv, OK, seed, userDoc } from "./helpers";

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await createEnv();
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
});

describe("users/{uid}: lectura", () => {
  test("el dueño lee su perfil", async () => {
    await seed(env, "users/alice", userDoc());
    await assertSucceeds(getDoc(doc(env.authenticatedContext("alice", OK).firestore(), "users/alice")));
  });

  test("el dueño lee su perfil sin email verificado ni consentimiento (estados de espera)", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "pending", policyVersion: null }));
    const db = env.authenticatedContext("alice", { email_verified: false }).firestore();
    await assertSucceeds(getDoc(doc(db, "users/alice")));
  });

  test("el dueño lee su perfil en parental_pending", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "parental_pending", isMinor: true }));
    await assertSucceeds(getDoc(doc(env.authenticatedContext("alice", OK).firestore(), "users/alice")));
  });

  test("otro usuario no lee el perfil", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(getDoc(doc(env.authenticatedContext("bob", OK).firestore(), "users/alice")));
  });

  test("otro usuario no lee el perfil en parental_pending", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "parental_pending", isMinor: true }));
    await assertFails(getDoc(doc(env.authenticatedContext("bob", OK).firestore(), "users/alice")));
  });

  test("un anónimo no lee el perfil", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "users/alice")));
  });

  test("list sobre users falla para un autenticado", async () => {
    await seed(env, "users/alice", userDoc());
    await assertFails(getDocs(collection(env.authenticatedContext("alice", OK).firestore(), "users")));
  });

  test("list sobre users falla en parental_pending", async () => {
    await seed(env, "users/alice", userDoc({ consentStatus: "parental_pending", isMinor: true }));
    await assertFails(getDocs(collection(env.authenticatedContext("alice", OK).firestore(), "users")));
  });
});
