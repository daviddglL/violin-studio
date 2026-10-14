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

interface Nested {
  name: string;
  /** Colección del `match` que lo contiene inmediatamente. */
  parent: string;
}

/**
 * Todas las subcolecciones declaradas bajo los `match` de colección, a profundidad arbitraria, con su
 * padre inmediato. Solo se admiten `match` de la forma `/nombre/{id}`; cualquier otra falla en voz alta.
 */
// Limitación: cuenta llaves sobre el texto sin comentarios; una llave dentro de un literal de cadena la desajustaría.
function nestedCollections(rules: string): Nested[] {
  const src = rules.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  const found: Nested[] = [];
  /** Un elemento por llave abierta: el nombre de colección si es un `match`, null en otro caso. */
  const stack: Array<string | null> = [];
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (c === "{") stack.push(null);
    else if (c === "}") stack.pop();
    else if (src.startsWith("match", i) && /\W/.test(src[i - 1] ?? " ")) {
      const m = /^match\s+(\S+)\s*\{/.exec(src.slice(i));
      if (!m) continue;
      const segs = m[1].split("/").filter(Boolean);
      const ancestors = stack.filter((s): s is string => s !== null);
      // ancestors[0] es el `match /databases/...`; el comodín raíz no declara colecciones.
      if (ancestors.length === 0 || m[1] === "/{document=**}") {
        stack.push("__otro__");
        i += m[0].length - 1;
        continue;
      }
      const wellFormed =
        segs.length > 0 && segs.length % 2 === 0 && segs.every((x, k) => (k % 2 === 0 ? /^\w+$/.test(x) : /^\{\w+\}$/.test(x)));
      if (!wellFormed) throw new Error(`subcolección no soportada en match ${m[1]}`);
      // Un match plano de varios niveles (`/users/{u}/recordings/{id}`) declara una cadena de colecciones.
      let parent: string | undefined = ancestors.length >= 2 ? ancestors[ancestors.length - 1] : undefined;
      for (let k = 0; k < segs.length; k += 2) {
        if (parent !== undefined) found.push({ name: segs[k], parent });
        parent = segs[k];
      }
      stack.push(parent as string);
      i += m[0].length - 1;
    }
  }
  return found;
}

/** Subcolecciones sin registro como `subcollectionOf` de su padre inmediato. */
function unregisteredSubs(
  subs: Nested[],
  policies: Record<string, ErasurePolicy> = ERASABLE_COLLECTIONS,
  known: readonly string[] = Object.values(COLLECTIONS),
): string[] {
  return subs
    .filter(({ name, parent }) => {
      const p = policies[name];
      return !known.includes(name) || p?.kind !== "subcollectionOf" || p.parent !== parent;
    })
    .map((s) => s.name);
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

test("el parser ve las subcolecciones y exige su registro como subcollectionOf de su padre inmediato", () => {
  const subs = nestedCollections(rules);
  expect(subs).toEqual(
    expect.arrayContaining([
      { name: "consents", parent: "users" },
      { name: "practiceSessions", parent: "users" },
      { name: "assignments", parent: "users" },
    ]),
  );
  expect(unregisteredSubs(subs)).toEqual([]);
});

test("una subcolección inventada bajo users falla nombrándola", () => {
  const conNueva = rules.replace("match /consents/{id} {", "match /inventada/{id} { allow read: if false; }\n      match /consents/{id} {");
  expect(unregisteredSubs(nestedCollections(conNueva))).toEqual(["inventada"]);
});

test("una sub-subcolección inventada falla nombrándola", () => {
  const conNueva = rules.replace("match /consents/{id} {", "match /consents/{id} {\n        match /profunda/{x} { allow read: if false; }");
  expect(nestedCollections(conNueva)).toContainEqual({ name: "profunda", parent: "consents" });
  expect(unregisteredSubs(nestedCollections(conNueva))).toEqual(["profunda"]);
});

test("una subcolección registrada pero bajo otro padre falla", () => {
  const src = wrap("match /users/{u} { match /consents/{id} { match /assignments/{x} { } } }");
  expect(unregisteredSubs(nestedCollections(src))).toEqual(["assignments"]);
});

test("profundidad arbitraria: cada nivel exige el padre inmediato", () => {
  const src = wrap("match /a/{x} { match /b/{y} { match /c/{z} { match /d/{w} { } } } }");
  const subs = nestedCollections(src);
  expect(subs.map((s) => `${s.parent}>${s.name}`)).toEqual(["a>b", "b>c", "c>d"]);
  const sub = (parent: string): ErasurePolicy => ({ kind: "subcollectionOf", parent: parent as never });
  // Con padres que no son el inmediato (todos users) fallan los tres niveles.
  const lejanos = { b: sub("users"), c: sub("users"), d: sub("users") };
  expect(unregisteredSubs(subs, lejanos, ["b", "c", "d"])).toEqual(["b", "c", "d"]);
  // Con padres inmediatos (a, b, c) todo pasa.
  const ok = { b: sub("a"), c: sub("b"), d: sub("c") };
  expect(unregisteredSubs(subs, ok, ["b", "c", "d"])).toEqual([]);
});

test("un match plano de varios niveles declara su cadena de subcolecciones y se valida como las anidadas", () => {
  const plano = wrap("match /users/{u}/fantasma/{r}/espectro/{x} { }");
  expect(nestedCollections(plano)).toEqual([
    { name: "fantasma", parent: "users" },
    { name: "espectro", parent: "fantasma" },
  ]);
  expect(unregisteredSubs(nestedCollections(plano))).toEqual(["fantasma", "espectro"]);
  const conReglas = rules.replace("match /guardianRequests/{id}", "match /users/{u}/fantasma/{id} { allow read: if false; }\n    match /guardianRequests/{id}");
  expect(unregisteredSubs(nestedCollections(conReglas))).toEqual(["fantasma"]);
});

test("toda subcoleccion registrada tiene su match explicito bajo su padre inmediato (B3b.3)", () => {
  const declared = nestedCollections(rules);
  for (const [name, p] of Object.entries(ERASABLE_COLLECTIONS)) {
    if (p.kind === "subcollectionOf") expect(declared).toContainEqual({ name, parent: p.parent });
  }
  expect(declared).toContainEqual({ name: "recordings", parent: "users" });
});

test("quitar el match de una subcoleccion registrada se detecta (negativo)", () => {
  const sin = rules.replace("match /recordings/{r} {", "match /otraCosa/{r} {");
  expect(nestedCollections(sin)).not.toContainEqual({ name: "recordings", parent: "users" });
  expect(unregisteredSubs(nestedCollections(sin))).toEqual(["otraCosa"]);
});

test("un match plano mal formado falla en voz alta", () => {
  expect(() => nestedCollections(wrap("match /users/{u}/recordings { }"))).toThrow(/subcolección/);
  expect(() => nestedCollections(wrap("match /users/x/{id} { }"))).toThrow(/subcolección/);
});

test("solo cuentan los match anidados y las formas raras fallan", () => {
  const src = wrap("match /users/{u} { match /a/{id} { } }\nmatch /otros/{o} { match /b/{id} { } }");
  expect(nestedCollections(src)).toEqual([
    { name: "a", parent: "users" },
    { name: "b", parent: "otros" },
  ]);
  expect(() => nestedCollections(wrap("match /users/{u} { match /{x}/{id} { } }"))).toThrow(/subcolección/);
  expect(() => nestedCollections(wrap("match /users/{u} { match /a/b/{id} { } }"))).toThrow(/subcolección/);
});

test("todas las colecciones de las reglas están en COLLECTIONS y registradas", () => {
  expect(unregistered(topLevelMatches(rules))).toEqual([]);
});

test("toda colección registrada tiene un match explícito y teacherCodes es deny total (lección 6)", () => {
  const names = new Set(topLevelMatches(rules));
  const sinMatch = Object.values(COLLECTIONS).filter((c) => !names.has(c));
  // Las subcolecciones viven bajo su padre; aquí solo se exigen las de primer nivel.
  const topLevel = sinMatch.filter((c) => ERASABLE_COLLECTIONS[c].kind !== "subcollectionOf");
  expect(topLevel).toEqual([]);
  // assignments (A6): reglas reales, no el deny provisional de A5; la escritura no es un deny total.
  expect(rules).toMatch(/match \/assignments\/\{id\}\s*\{\s*(?:\/\/[^\n]*\s*)*allow get, list: if isOwner\(uid\)/);
  expect(rules).not.toMatch(/match \/assignments\/\{id\}\s*\{\s*allow read, write: if false;/);
  expect(rules).toMatch(/match \/teacherCodes\/\{id\}\s*\{\s*allow read, write: if false;\s*\}/);
  // teacherLinks: lectura solo por las partes (A4), escritura siempre denegada.
  expect(rules).toMatch(
    /match \/teacherLinks\/\{id\}\s*\{\s*allow get, list: if isSignedIn\(\) && emailVerified\(\)\s*&&\s*\(resource\.data\.teacherUid == request\.auth\.uid \|\| resource\.data\.studentUid == request\.auth\.uid\);\s*allow write: if false;\s*\}/,
  );
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
