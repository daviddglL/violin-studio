import { readFileSync } from "node:fs";
import { join } from "node:path";
import { matchBrace, matchParen, splitArgs, stripComments } from "./rules-analysis";

/**
 * B3b.3 / REQ-SEC-R04: cada evaluacion de `firestore.rules` puede leer a lo sumo 4 documentos DISTINTOS (el limite de la
 * plataforma es 10 por peticion; se deja margen). Analisis estatico: union de los `get/exists/getAfter/existsAfter` de
 * todas las ramas (cota superior), expandiendo las funciones con sus argumentos y deduplicando por ruta normalizada.
 */
const MAX_DISTINCT_DOCS = 4;

type Fn = { params: string[]; body: string };

export function parseFunctions(src: string): Map<string, Fn> {
  const fns = new Map<string, Fn>();
  const re = /function\s+(\w+)\s*\(([^)]*)\)\s*\{/g;
  for (let m = re.exec(src); m; m = re.exec(src)) {
    const open = m.index + m[0].length - 1;
    // `return` y `let x =` no son llamadas: se quitan para que el cuerpo sea una sola expresion analizable.
    const body = src.slice(open + 1, matchBrace(src, open)).replace(/\breturn\b/g, "").replace(/\blet\s+\w+\s*=/g, "");
    fns.set(m[1], { params: m[2].split(",").map((p) => p.trim()).filter(Boolean), body });
  }
  return fns;
}

/** Funciones libres del lenguaje de reglas que no son helpers del fichero. */
const BUILTINS = new Set(["in", "is", "get", "exists", "getAfter", "existsAfter", "int", "float", "string", "bool", "path", "duration", "timestamp", "latlng", "bytes", "debug", "hashing"]);
const ACCESS = new Set(["get", "exists", "getAfter", "existsAfter"]);

function normalizePath(p: string): string {
  let out = p.replace(/\s+/g, " ").replace(/^\s*\/databases\/\$\(database\)\/documents\//, "").trim();
  // Los argumentos se sustituyen entre parentesis: `$((uid))` y `$(uid)` son el mismo documento.
  for (let prev = ""; prev !== out; ) {
    prev = out;
    out = out.replace(/\(([\w.]+)\)/g, "$1");
  }
  return out;
}

/** Sustituye los parametros de una funcion por el texto de sus argumentos, todos a la vez. */
function substitute(body: string, params: string[], args: string[]): string {
  if (params.length === 0) return body;
  const map = new Map(params.map((p, i) => [p, `(${args[i] ?? "undefined"})`] as const));
  return body.replace(new RegExp(String.raw`(?<![\w.$])(${params.join("|")})(?!\w)`, "g"), (_, name: string) => map.get(name) ?? name);
}

export function distinctDocs(expr: string, fns: Map<string, Fn>, seen: string[] = []): Set<string> {
  const docs = new Set<string>();
  for (const m of expr.matchAll(/(?<![\w.$])(\w+)\s*\(/g)) {
    const name = m[1];
    const open = m.index! + m[0].length - 1;
    const inner = expr.slice(open + 1, matchParen(expr, open));
    if (ACCESS.has(name)) {
      docs.add(normalizePath(inner));
      // El argumento puede contener otras llamadas (p. ej. `$(f(x))`): se recorren tambien.
      for (const d of distinctDocs(inner, fns, seen)) docs.add(d);
    } else if (fns.has(name)) {
      if (seen.includes(name)) throw new Error(`recursion en ${name}`);
      const fn = fns.get(name)!;
      for (const d of distinctDocs(substitute(fn.body, fn.params, splitArgs(inner)), fns, [...seen, name])) docs.add(d);
    } else if (!BUILTINS.has(name)) {
      throw new Error(`helper no definido: ${name}`);
    }
  }
  return docs;
}

/** Cada `allow` con su expresion, recorriendo los `match` anidados (nombre = metodos + ruta de match). */
export function allowRules(src: string): { where: string; methods: string; expr: string }[] {
  const out: { where: string; methods: string; expr: string }[] = [];
  const re = /allow\s+([\w,\s]+?)\s*:\s*if\s+([\s\S]*?);/g;
  for (let m = re.exec(src); m; m = re.exec(src)) {
    const matches = [...src.slice(0, m.index).matchAll(/match\s+(\S+)\s*\{/g)].filter((x) => matchBrace(src, x.index! + x[0].length - 1) > m!.index);
    out.push({ where: matches.map((x) => x[1]).join(""), methods: m[1].replace(/\s+/g, ""), expr: m[2].trim() });
  }
  return out;
}

describe("contador estatico de documentos distintos (autoprueba)", () => {
  const fns = parseFunctions(
    "function userDoc(u) { return get(/databases/$(database)/documents/users/$(u)).data; }" +
      " function link(t, s) { let l = get(/databases/$(database)/documents/links/$(t + '_' + s)).data; return l.a == t; }" +
      " function both(s) { return link(request.auth.uid, s) && userDoc(s).x && userDoc(request.auth.uid).y; }",
  );
  const n = (e: string) => [...distinctDocs(e, fns)].sort();
  test("el mismo documento leido dos veces cuenta una", () => {
    expect(n("userDoc(a).x == 1 && userDoc(a).y == 2")).toEqual(["users/$a"]);
  });
  test("documentos distintos suman; los argumentos se sustituyen en el cuerpo", () => {
    expect(n("userDoc(a).x == 1 && userDoc(b).y == 2")).toHaveLength(2);
    expect(n("link(x, y)")).toEqual(["links/$(x + '_' + y)"]);
  });
  test("un `let` dentro del cuerpo de la funcion cuenta", () => {
    expect(n("link(x, y)")).toHaveLength(1);
  });
  test("expansion anidada: both(s) lee vinculo + 2 usuarios", () => {
    expect(n("both(z)")).toHaveLength(3);
  });
  test("los metodos .get('k', d) del mapa no son accesos y un helper no definido falla", () => {
    expect(n("m.get('k', 0) == 1 && m.keys().hasOnly(['a'])")).toEqual([]);
    expect(() => n("inexistente(1)")).toThrow(/inexistente/);
  });
  test("las ramas || se suman (cota superior)", () => {
    expect(n("userDoc(a).x == 1 || userDoc(b).y == 2")).toHaveLength(2);
  });
});

describe("firestore.rules respeta el presupuesto de accesos (REQ-SEC-R04)", () => {
  const raw = stripComments(readFileSync(join(__dirname, "../../firestore.rules"), "utf8"));
  const fns = parseFunctions(raw);
  const rules = allowRules(raw);
  const count = (where: string, methods: string) => {
    const r = rules.find((x) => x.where.endsWith(where) && x.methods === methods);
    if (!r) throw new Error(`sin regla ${methods} en ${where}`);
    return distinctDocs(r.expr, fns).size;
  };

  test("el analizador ve todas las reglas allow", () => {
    expect(rules.length).toBe((raw.match(/\ballow\b/g) ?? []).length);
  });
  test.each(rules.map((r) => [`${r.methods} @ ${r.where}`, r.expr] as const))("allow %s: <= 4 documentos distintos", (_n, expr) => {
    expect(distinctDocs(expr, fns).size).toBeLessThanOrEqual(MAX_DISTINCT_DOCS);
  });
  test("cotas conocidas de recordings y reviews (tripwire: si suben, revisar a mano)", () => {
    // profesor: vinculo + alumno + profesor; destinatario de demo: otro vinculo -> 4.
    expect(count("/recordings/{r}", "get,list")).toBe(4);
    // profesor vinculado (3) + doc de la grabacion = 4; el `get` del alumno se reutiliza para recordingGranted.
    expect(count("/reviews/{reviewId}", "create")).toBe(4);
    expect(count("/reviews/{reviewId}", "update")).toBe(3);
    expect(count("/reviews/{reviewId}", "get")).toBe(3);
  });
});
