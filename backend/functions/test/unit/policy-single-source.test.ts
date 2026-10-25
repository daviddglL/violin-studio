import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

function ficheros(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? ficheros(p) : p.endsWith(".ts") ? [p] : [];
  });
}

const src = join(__dirname, "../../src");
const sinComentarios = (f: string) => readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
const fuera = (f: string) => relative(src, f).split(sep).join("/") !== "config/identity.ts";

test("CURRENT_POLICY_VERSION y POLICY_URL solo se definen en config/identity.ts", () => {
  const infractores = ficheros(src)
    .filter(fuera)
    .filter((f) => /\b(const|let|var)\s+(CURRENT_POLICY_VERSION|POLICY_URL)\b/.test(sinComentarios(f)));
  expect(infractores).toEqual([]);
});

test("ningún otro fichero de src incluye la URL de la política como literal", () => {
  const infractores = ficheros(src).filter(fuera).filter((f) => /\/privacy["'`]/.test(sinComentarios(f)));
  expect(infractores).toEqual([]);
});
