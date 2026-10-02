import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { COLLECTIONS } from "../../src/common/collections";
import { ERASABLE_COLLECTIONS } from "../../src/erasure/registry";

/** Nombres de los `match /x/{...}` de primer nivel bajo `/databases/{database}/documents` (profundidad de llaves 2). */
function topLevelMatches(rules: string): string[] {
  const src = rules.replace(/\/\/.*$/gm, "");
  const names: string[] = [];
  let depth = 0;
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (c === "{") depth++;
    else if (c === "}") depth--;
    else if (depth === 2 && src.startsWith("match", i) && /\W/.test(src[i - 1] ?? " ")) {
      const m = /^match\s+\/(\w+)\/\{/.exec(src.slice(i));
      if (m) names.push(m[1]);
    }
  }
  return names;
}

function unregistered(names: string[]): string[] {
  const known = new Set<string>(Object.values(COLLECTIONS));
  const registered = new Set<string>(Object.keys(ERASABLE_COLLECTIONS));
  return names.filter((n) => !known.has(n) || !registered.has(n));
}

const rules = readFileSync(resolve(__dirname, "../../../firestore.rules"), "utf8");

test("el parser ve las colecciones de primer nivel y no las subcolecciones", () => {
  const names = topLevelMatches(rules);
  expect(names).toEqual(expect.arrayContaining(["users", "guardianRequests", "guardianEmailLimits", "mail"]));
  expect(names).not.toContain("consents");
});

test("todas las colecciones de las reglas están en COLLECTIONS y registradas", () => {
  expect(unregistered(topLevelMatches(rules))).toEqual([]);
});

test("una colección nueva en las reglas sin registrar falla nombrándola", () => {
  const conNueva = rules.replace(
    "match /{document=**}",
    "match /diarios/{id} { allow read: if false; }\n    match /{document=**}",
  );
  expect(unregistered(topLevelMatches(conNueva))).toEqual(["diarios"]);
});
