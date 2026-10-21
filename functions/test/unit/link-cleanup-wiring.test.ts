import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Firestore } from "firebase-admin/firestore";
import { defaultLinkCleanupSteps } from "../../src/teacher/assignments-cleanup";

const src = (f: string) => readFileSync(join(__dirname, "../../src/teacher", f), "utf8");

describe("cableado de la limpieza de removeLink (A6)", () => {
  test("los pasos por defecto incluyen la limpieza de tareas", () => {
    expect(defaultLinkCleanupSteps({} as Firestore)).toHaveLength(1);
  });
  test("removeLink usa defaultLinkCleanupSteps cuando no se pasan pasos", () => {
    expect(src("remove-link.ts")).toMatch(/deps\.cleanupSteps \?\? defaultLinkCleanupSteps\(db\)/);
  });
  test("ningun llamador de produccion reemplaza los pasos (deben componerse con el defecto)", () => {
    for (const f of ["unlink.ts", "../admin/teacher-role.ts", "../erasure/shared.ts"]) {
      expect(src(f)).not.toMatch(/cleanupSteps\s*:/);
    }
  });
});
