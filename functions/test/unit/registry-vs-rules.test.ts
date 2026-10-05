import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { COLLECTIONS } from "../../src/common/collections";
import { ERASABLE_COLLECTIONS, ErasurePolicy } from "../../src/erasure/registry";

/**
 * Primer segmento de los `match` de primer nivel bajo `/databases/{database}/documents` (profundidad de llaves 2).
 * Falla si el primer segmento no es un nombre simple (salvo el comodín `{document=**}`).
 */
function topLevelMatches(rules: string): string[] {
  const src = rules.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  const names: string[] = [];
  let depth = 0;
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (c === "{") depth++;
    else if (c === "}") depth--;
    else if (depth === 2 && src.startsWith("match", i) && /\W/.test(src[i - 1] ?? " ")) {
      const m = /^match\s+(\S+)\s*\{/.exec(src.slice(i));
      if (!m) continue;
      const first = m[1].split("/")[1] ?? "";
      if (m[1] === "/{document=**}") continue;
      if (!/^\w+$/.test(first)) throw new Error(`primer segmento no soportado en match ${m[1]}`);
      names.push(first);
    }
  }
  return names;
}

/**
 * Nombres de las subcolecciones declaradas bajo `match /<parent>/{id}` (profundidad de llaves 3).
 * Solo se admiten `match` de la forma `/nombre/{id}`; cualquier otra falla en voz alta.
 */
function subcollectionMatches(rules: string, parent: string): string[] {
  const src = rules.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  const names: string[] = [];
  let depth = 0;
  let inParent = false;
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (c === "{") depth++;
    else if (c === "}") depth--;
    else if (src.startsWith("match", i) && /\W/.test(src[i - 1] ?? " ")) {
      const m = /^match\s+(\S+)\s*\{/.exec(src.slice(i));
      if (!m) continue;
      if (depth === 2) inParent = m[1].split("/")[1] === parent;
      else if (depth === 3 && inParent) {
        const segs = m[1].split("/").filter(Boolean);
        if (segs.length !== 2 || !/^\w+$/.test(segs[0])) throw new Error(`subcolección no soportada en match ${m[1]}`);
        names.push(segs[0]);
      }
    }
  }
  return names;
}

function unregisteredSubs(names: string[], parent: string): string[] {
  const policies: Record<string, ErasurePolicy> = ERASABLE_COLLECTIONS;
  return names.filter((n) => {
    const p = policies[n];
    return !Object.values(COLLECTIONS).includes(n as never) || p?.kind !== "subcollectionOf" || p.parent !== parent;
  });
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

const wrap = (body: string) =>
  "service cloud.firestore {\n  match /databases/{database}/documents {\n" + body + "\n  }\n}";

test("el parser ve las subcolecciones de users y exige su registro como subcollectionOf users", () => {
  const subs = subcollectionMatches(rules, "users");
  expect(subs).toEqual(expect.arrayContaining(["consents", "practiceSessions"]));
  expect(unregisteredSubs(subs, "users")).toEqual([]);
});

test("una subcolección inventada bajo users falla nombrándola", () => {
  const conNueva = rules.replace("match /consents/{id} {", "match /inventada/{id} { allow read: if false; }\n      match /consents/{id} {");
  expect(unregisteredSubs(subcollectionMatches(conNueva, "users"), "users")).toEqual(["inventada"]);
});

test("las subcolecciones solo se leen del padre pedido y las formas raras fallan", () => {
  const src = wrap("match /users/{u} { match /a/{id} { } }\nmatch /otros/{o} { match /b/{id} { } }");
  expect(subcollectionMatches(src, "users")).toEqual(["a"]);
  expect(() => subcollectionMatches(wrap("match /users/{u} { match /{x}/{id} { } }"), "users")).toThrow(/subcolección/);
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

describe("parser de reglas (endurecido)", () => {
  test("ignora comentarios de línea y de bloque", () => {
    const src = wrap("// match /fantasma/{id} { }\n/* match /otra/{id} { allow read: if false; } */\nmatch /users/{uid} { }");
    expect(topLevelMatches(src)).toEqual(["users"]);
  });

  test("un match de varios segmentos cuenta por su primer segmento", () => {
    expect(topLevelMatches(wrap("match /a/b/{id} { }"))).toEqual(["a"]);
    expect(unregistered(topLevelMatches(wrap("match /a/b/{id} { }")))).toEqual(["a"]);
  });

  test("el comodín {document=**} está permitido y no es una colección", () => {
    expect(topLevelMatches(wrap("match /{document=**} { allow read: if false; }"))).toEqual([]);
  });

  test.each([["match /{col}/{id} { }"], ["match /{col=**} { }"], ["match /users-x/{id} { }"], ["match /{document=**}/x/{id} { }"]])(
    "un primer segmento que no es un nombre simple falla en voz alta: %s",
    (m) => {
      expect(() => topLevelMatches(wrap(m))).toThrow(/primer segmento/);
    },
  );
});
