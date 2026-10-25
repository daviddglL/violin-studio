import { readFileSync } from "node:fs";
import { join } from "node:path";
import { CALLABLE_PURGE_BUDGET_MS } from "../../src/recordings/revoke-purge";

const index = readFileSync(join(__dirname, "../../src/index.ts"), "utf8");
/** Cuerpo de un `export const <name> = ...` hasta el siguiente export de primer nivel. */
const exportBody = (name: string) => {
  const start = index.indexOf(`export const ${name} =`);
  expect(start).toBeGreaterThanOrEqual(0);
  const next = index.indexOf("\nexport ", start + 1);
  return index.slice(start, next < 0 ? undefined : next);
};

describe("S3 (revision fresca B5b): cableado de la purga de revocacion en index.ts", () => {
  test.each(["revokeRecordingConsent", "revokeConsent", "guardianConsent"])("%s pasa purgeRecordings: purgeRecordingsHook()", (name) => {
    expect(exportBody(name)).toMatch(/purgeRecordings: purgeRecordingsHook\(\)/);
  });

  test("purgeRecordingsHook usa la purga con presupuesto (S4), no purgeRevokedRecordings a pelo", () => {
    const start = index.indexOf("const purgeRecordingsHook =");
    const body = index.slice(start, index.indexOf("\n};", start));
    expect(body).toMatch(/budgetedPurgeHook\(/);
    expect(body).not.toMatch(/purgeRevokedRecordings\(/);
  });

  test("S4: el presupuesto de los callables es ~45 s (por debajo del timeout de 60 s)", () => {
    expect(CALLABLE_PURGE_BUDGET_MS).toBe(45_000);
  });
});
