import { assertFails, RulesTestContext, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { getBytes, ref, uploadString } from "firebase/storage";
import { createEnv, OK, seed, userDoc } from "./helpers";

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
  ["autenticado", () => env.authenticatedContext("alice", OK)],
];

describe.each(contexts)("usuario %s", (_, ctx) => {
  // `users/alice` ya no es deny-all para Alice (fase 2); una colección no declarada sí lo sigue siendo.
  test("no puede leer una colección Firestore no declarada", async () => {
    await seed(env, "profiles/alice", userDoc());
    await assertFails(getDoc(doc(ctx().firestore(), "profiles/alice")));
  });
  test("no puede escribir una colección Firestore no declarada", async () => {
    await assertFails(setDoc(doc(ctx().firestore(), "profiles/alice"), { name: "Alice" }));
  });
  test("no puede leer Storage", async () => {
    await assertFails(getBytes(ref(ctx().storage(), "users/alice/demo.txt")));
  });
  test("no puede escribir Storage", async () => {
    await assertFails(uploadString(ref(ctx().storage(), "users/alice/demo.txt"), "hola"));
  });
  test("no puede leer ni escribir Storage en users/{uid}/... de otro", async () => {
    await assertFails(getBytes(ref(ctx().storage(), "users/bob/perfil/foto.png")));
    await assertFails(uploadString(ref(ctx().storage(), "users/bob/perfil/foto.png"), "x"));
  });
});
