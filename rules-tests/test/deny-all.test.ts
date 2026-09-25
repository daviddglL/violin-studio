import { assertFails, initializeTestEnvironment, RulesTestContext, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { getBytes, ref, uploadString } from "firebase/storage";
import { readFileSync } from "node:fs";
import { join } from "node:path";

let env: RulesTestEnvironment;

beforeAll(async () => {
  // Host y puerto salen de FIRESTORE_EMULATOR_HOST / FIREBASE_STORAGE_EMULATOR_HOST (los pone emulators:exec).
  env = await initializeTestEnvironment({
    projectId: "demo-violin-studio",
    firestore: { rules: readFileSync(join(__dirname, "../../firestore.rules"), "utf8") },
    storage: { rules: readFileSync(join(__dirname, "../../storage.rules"), "utf8") },
  });
});

afterAll(() => env.cleanup());

const contexts: [string, () => RulesTestContext][] = [
  ["anónimo", () => env.unauthenticatedContext()],
  ["autenticado", () => env.authenticatedContext("alice")],
];

describe.each(contexts)("usuario %s", (_, ctx) => {
  test("no puede leer Firestore", async () => {
    await assertFails(getDoc(doc(ctx().firestore(), "users/alice")));
  });
  test("no puede escribir Firestore", async () => {
    await assertFails(setDoc(doc(ctx().firestore(), "users/alice"), { name: "Alice" }));
  });
  test("no puede leer Storage", async () => {
    await assertFails(getBytes(ref(ctx().storage(), "users/alice/demo.txt")));
  });
  test("no puede escribir Storage", async () => {
    await assertFails(uploadString(ref(ctx().storage(), "users/alice/demo.txt"), "hola"));
  });
});
