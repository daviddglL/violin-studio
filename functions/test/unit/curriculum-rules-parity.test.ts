import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * Tripwire: los ids de leccion que admite `validProgress` en `firestore.rules` son exactamente los del curriculo fijo de
 * dominio (`Curriculum.lessons`). Anadir o retirar una leccion sin tocar las reglas (o al reves) rompe CI. El
 * comportamiento lo prueba `rules-tests/test/progress.test.ts`.
 */
const root = join(__dirname, "../../..");
const rules = readFileSync(join(root, "firestore.rules"), "utf8").replace(/\/\/.*$/gm, "");
const curriculum = readFileSync(
  join(root, "domain/src/main/kotlin/com/violinstudio/domain/feature/curriculum/model/Curriculum.kt"),
  "utf8",
);

function ruleIds(): string[] {
  const fn = rules.slice(rules.indexOf("function validProgress"));
  const list = /d\.completed\.hasOnly\(\[([^\]]*)\]\)/.exec(fn);
  if (!list) throw new Error("validProgress sin hasOnly de ids");
  return [...list[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
}

const domainIds = [...curriculum.matchAll(/Lesson\("([^"]+)"/g)].map((m) => m[1]);

test("validProgress admite exactamente las lecciones de Curriculum", () => {
  expect(domainIds.length).toBeGreaterThan(0);
  expect([...ruleIds()].sort()).toEqual([...domainIds].sort());
});

test("el tope de la lista es el numero de lecciones", () => {
  expect(rules).toContain(`d.completed.size() <= ${domainIds.length}`);
});
