import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

function ficheros(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? ficheros(p) : p.endsWith(".ts") ? [p] : [];
  });
}

test("solo config/identity.ts define el umbral de 14 años", () => {
  const src = join(__dirname, "../../src");
  const infractores = ficheros(src)
    .filter((f) => relative(src, f).split(sep).join("/") !== "config/identity.ts")
    .filter((f) => {
      const sinComentarios = readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
      return /\b14\b/.test(sinComentarios);
    });
  expect(infractores).toEqual([]);
});
