import { assertFails, assertSucceeds, initializeTestEnvironment, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, setDoc } from "firebase/firestore";
import { getBytes, ref, uploadBytes } from "firebase/storage";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * Spike B3a.0 (Q4 + Q2): ¿resuelve el emulador de Storage `firestore.get/exists` contra el de Firestore con un
 * proyecto `demo-*`, y cuántos accesos cruzados admite por evaluación? Reglas mínimas propias (no las de producción).
 */
const RULES = `
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /one/{f} { allow read: if firestore.exists(/databases/(default)/documents/probe/a); }
    match /get/{f} { allow read: if firestore.get(/databases/(default)/documents/probe/a).data.ok == true; }
    match /two/{f} {
      allow read: if firestore.exists(/databases/(default)/documents/probe/a)
        && firestore.get(/databases/(default)/documents/probe/b).data.ok == true;
    }
    match /three/{f} {
      allow read: if firestore.exists(/databases/(default)/documents/probe/a)
        && firestore.exists(/databases/(default)/documents/probe/b)
        && firestore.exists(/databases/(default)/documents/probe/c);
    }
    match /{allPaths=**} { allow read, write: if false; }
  }
}`;

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-violin-studio",
    firestore: { rules: readFileSync(join(__dirname, "../../firestore.rules"), "utf8") },
    storage: { rules: RULES },
  });
  await env.withSecurityRulesDisabled(async (ctx) => {
    for (const p of ["one", "get", "two", "three"]) {
      await uploadBytes(ref(ctx.storage(), `${p}/x.bin`), new Uint8Array([1, 2, 3]));
    }
  });
});
afterAll(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
});

async function seedProbe(...ids: string[]): Promise<void> {
  await env.withSecurityRulesDisabled(async (ctx) => {
    for (const id of ids) await setDoc(doc(ctx.firestore(), `probe/${id}`), { ok: true });
  });
}
const read = (p: string) => getBytes(ref(env.authenticatedContext("u").storage(), `${p}/x.bin`));

describe("spike Q4: reglas de Storage leen Firestore en el emulador (demo-violin-studio)", () => {
  test("exists: sin doc deniega, con doc permite", async () => {
    await assertFails(read("one"));
    await seedProbe("a");
    await assertSucceeds(read("one"));
  });
  test("get: lee campos del doc", async () => {
    await assertFails(read("get"));
    await seedProbe("a");
    await assertSucceeds(read("get"));
  });
});

describe("spike Q2: límite de accesos cruzados por evaluación", () => {
  test("2 accesos (exists + get) funcionan", async () => {
    await seedProbe("a", "b");
    await assertSucceeds(read("two"));
  });
  // Medido en B3a.0: el emulador NO aplica el tope de 2 accesos cruzados de producción (3 pasan). El límite real
  // (A20, doc. de Firebase: 2 `firestore.get/exists` por evaluación) no es verificable offline: `storage-recordings`
  // lo comprueba de forma estática sobre `storage.rules`. Si algún día el emulador lo aplica, este test avisará.
  test("el emulador no aplica el tope de 2 accesos: 3 accesos pasan (el límite se vigila estáticamente)", async () => {
    await seedProbe("a", "b", "c");
    await assertSucceeds(read("three"));
  });
});
