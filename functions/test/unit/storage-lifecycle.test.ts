import { readFileSync } from "node:fs";
import { join } from "node:path";
import { READY_TTL_DAYS as RECORDING_RETENTION_DAYS } from "../../src/recordings/finalize";

const cfg = JSON.parse(readFileSync(join(__dirname, "../../../infra/storage-lifecycle.json"), "utf8"));

describe("infra/storage-lifecycle.json (red de seguridad de B5a, README 4b paso 3)", () => {
  test("una unica regla: delete a 95 dias sobre .m4a y .mp4", () => {
    expect(cfg.lifecycle.rule).toEqual([
      { action: { type: "Delete" }, condition: { age: 95, matchesSuffix: [".m4a", ".mp4"] } },
    ]);
  });

  test("95 dias = 90 de caducidad de las grabaciones + 5 de margen para la purga diaria", () => {
    expect(RECORDING_RETENTION_DAYS).toBe(90);
    expect(cfg.lifecycle.rule[0].condition.age).toBeGreaterThan(RECORDING_RETENTION_DAYS);
  });

  test("es el formato de `gcloud storage buckets update --lifecycle-file` (solo lifecycle.rule)", () => {
    expect(Object.keys(cfg)).toEqual(["lifecycle"]);
    expect(Object.keys(cfg.lifecycle)).toEqual(["rule"]);
  });
});
