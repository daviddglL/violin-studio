import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

const SRC = resolve(__dirname, "../../src");
/** Provisional del spike 1a.1 (colección propia de pruebas); 7a-bis lo reescribe y debe salir de aquí. */
const ALLOWED = ["erasure/on-user-deleted.ts"];

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? files(p) : p.endsWith(".ts") ? [p] : [];
  });
}

test("src no usa collection()/collectionGroup() con literales fuera de COLLECTIONS", () => {
  const offenders = files(SRC)
    .filter((f) => !ALLOWED.includes(relative(SRC, f).split(sep).join("/")))
    .filter((f) => /\.collection(?:Group)?\(\s*["'`]/.test(readFileSync(f, "utf8")))
    .map((f) => relative(SRC, f));
  expect(offenders).toEqual([]);
});

test("el detector reconoce un literal", () => {
  expect(/\.collection(?:Group)?\(\s*["'`]/.test('db.collection("users")')).toBe(true);
  expect(/\.collection(?:Group)?\(\s*["'`]/.test("db.collection(COLLECTIONS.users)")).toBe(false);
});
