import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

const SRC = resolve(__dirname, "../../src");
/**
 * Provisional del spike 1a.1 (colección propia de pruebas `spikeMarkers`, con clave uid y que eraseUserData NO borra).
 * TODO(7a-bis): reescribir `onUserDeleted` y quitar esta excepción.
 */
const ALLOWED = ["erasure/on-user-deleted.ts"];

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? files(p) : p.endsWith(".ts") ? [p] : [];
  });
}

/** `.collection("x")`, `.collection<T>("x")`, `.collectionGroup("x")` o `.doc("a/b")` con literal (también plantillas). */
const LITERAL = /\.collection(?:Group)?\s*(?:<[^>]*>)?\s*\(\s*["'`]|\.doc\s*\(\s*["'`][^"'`]*\/[^"'`]*["'`]/;
const hasLiteral = (src: string) => LITERAL.test(src);

test("src no usa collection()/collectionGroup()/doc('a/b') con literales fuera de COLLECTIONS", () => {
  const offenders = files(SRC)
    .filter((f) => !ALLOWED.includes(relative(SRC, f).split(sep).join("/")))
    .filter((f) => hasLiteral(readFileSync(f, "utf8")))
    .map((f) => relative(SRC, f));
  expect(offenders).toEqual([]);
});

test.each([
  'db.collection("users")',
  "db.collection('users')",
  "db.collection(`users`)",
  'db.collection<Perfil>("users")',
  'db.collectionGroup("consents")',
  'db.collectionGroup<X>( "consents")',
  'db.doc("users/abc")',
  "db.doc(`users/${uid}`)",
  "db\n  .collection(\n 'users')",
])("el detector reconoce %j", (src) => {
  expect(hasLiteral(src)).toBe(true);
});

test.each(["db.collection(COLLECTIONS.users)", "db.collection<X>(COLLECTIONS.mail)", "db.doc(path)", 'ref.doc("abc")', "db.collectionGroup(name)"])(
  "el detector ignora %s",
  (src) => {
    expect(hasLiteral(src)).toBe(false);
  },
);
