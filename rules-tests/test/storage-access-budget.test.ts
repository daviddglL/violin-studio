import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * A20 / spike B3a.0: el emulador de Storage no aplica el tope de 2 accesos `firestore.get/exists` por evaluacion
 * (produccion si), asi que se vigila de forma estatica sobre `storage.rules`: el peor camino de cada `allow`
 * (suma de accesos en `&&`, maximo entre ramas de `||`, expandiendo las funciones llamadas) debe ser <= 2.
 */
const MAX_CROSS_SERVICE_ACCESSES = 2;

function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
}

function matchBrace(src: string, open: number): number {
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return i;
  }
  throw new Error("llave sin cerrar");
}

/** `function nombre(args) { return EXPR; }` -> mapa nombre -> EXPR. */
export function parseFunctions(src: string): Map<string, string> {
  const fns = new Map<string, string>();
  const re = /function\s+(\w+)\s*\([^)]*\)\s*\{/g;
  for (let m = re.exec(src); m; m = re.exec(src)) {
    const open = m.index + m[0].length - 1;
    const body = src.slice(open + 1, matchBrace(src, open));
    const ret = /return\s+([\s\S]*?);?\s*$/.exec(body.trim());
    if (!ret) throw new Error(`funcion ${m[1]} sin return simple`);
    fns.set(m[1], ret[1].replace(/;\s*$/, ""));
  }
  return fns;
}

/** Parte `expr` por `op` ("&&" o "||") solo al nivel de parentesis 0. */
function splitTop(expr: string, op: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < expr.length; i++) {
    const c = expr[i];
    if (c === "(" || c === "[") depth++;
    else if (c === ")" || c === "]") depth--;
    else if (depth === 0 && expr.startsWith(op, i)) {
      parts.push(expr.slice(start, i));
      i += op.length - 1;
      start = i + 1;
    }
  }
  parts.push(expr.slice(start));
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

function unwrap(term: string): string | null {
  const t = term.trim();
  if (!t.startsWith("(") || !t.endsWith(")")) return null;
  let depth = 0;
  for (let i = 0; i < t.length; i++) {
    if (t[i] === "(") depth++;
    else if (t[i] === ")" && --depth === 0 && i < t.length - 1) return null;
  }
  return t.slice(1, -1);
}

export function worstCase(expr: string, fns: Map<string, string>, seen: string[] = []): number {
  const branches = splitTop(expr, "||");
  if (branches.length > 1) return Math.max(...branches.map((b) => worstCase(b, fns, seen)));
  const terms = splitTop(expr, "&&");
  if (terms.length > 1) return terms.reduce((n, t) => n + worstCase(t, fns, seen), 0);
  const inner = unwrap(expr);
  if (inner !== null) return worstCase(inner, fns, seen);
  const direct = (expr.match(/firestore\.(get|exists)\s*\(/g) ?? []).length;
  let viaFns = 0;
  for (const [name, body] of fns) {
    if (!new RegExp(`(^|[^\\w.])${name}\\s*\\(`).test(expr)) continue;
    if (seen.includes(name)) throw new Error(`recursion en ${name}`);
    const calls = (expr.match(new RegExp(`(^|[^\\w.])${name}\\s*\\(`, "g")) ?? []).length;
    viaFns += calls * worstCase(body, fns, [...seen, name]);
  }
  return direct + viaFns;
}

/** Expresion de cada `allow` (condicion tras `if`) con el metodo(s) que cubre. */
export function allowRules(src: string): { methods: string; expr: string }[] {
  const out: { methods: string; expr: string }[] = [];
  const re = /allow\s+([\w,\s]+?)\s*:\s*if\s+([\s\S]*?);/g;
  for (let m = re.exec(src); m; m = re.exec(src)) out.push({ methods: m[1].replace(/\s+/g, ""), expr: m[2].trim() });
  return out;
}

describe("contador estatico de accesos entre servicios (autoprueba)", () => {
  const fns = parseFunctions("function g(x) { return firestore.get(/a/$(x)).data; } function e(x) { return firestore.exists(/a/$(x)); }");
  test("suma en && y maximo en ||", () => {
    expect(worstCase("e(1) && e(2) && e(3)", fns)).toBe(3);
    expect(worstCase("e(1) || (e(2) && g(3))", fns)).toBe(2);
    expect(worstCase("g(1).ok == true && (e(2) || e(3))", fns)).toBe(2);
    expect(worstCase("a == b", fns)).toBe(0);
  });
  test("una funcion invocada dos veces cuenta dos veces", () => {
    expect(worstCase("g(1).a == g(2).a", fns)).toBe(2);
  });
});

describe("storage.rules respeta el presupuesto de accesos (A20)", () => {
  const raw = stripComments(readFileSync(join(__dirname, "../../storage.rules"), "utf8"));
  const fns = parseFunctions(raw);
  const rules = allowRules(raw);

  test("hay reglas allow que analizar", () => {
    expect(rules.length).toBeGreaterThanOrEqual(3);
  });
  test.each(rules.map((r) => [r.methods, r.expr] as const))("allow %s: <= 2 accesos en el peor camino", (_m, expr) => {
    expect(worstCase(expr, fns)).toBeLessThanOrEqual(MAX_CROSS_SERVICE_ACCESSES);
  });
});

describe("el cupo no anade accesos (REQ-REC-14)", () => {
  test("allow create usa exactamente 1 acceso (el get del doc de usuario)", () => {
    const raw = stripComments(readFileSync(join(__dirname, "../../storage.rules"), "utf8"));
    const create = allowRules(raw).find((r) => r.methods === "create");
    expect(create).toBeDefined();
    expect(worstCase(create!.expr, parseFunctions(raw))).toBe(1);
  });
});
