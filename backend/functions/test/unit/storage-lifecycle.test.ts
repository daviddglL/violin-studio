import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ORPHAN_MIN_AGE_MS } from "../../src/recordings/orphan-sweep";
import { READY_MAX_OBJECT_AGE_MS, READY_TTL_DAYS as RECORDING_RETENTION_DAYS } from "../../src/recordings/finalize";

const cfg = JSON.parse(readFileSync(join(__dirname, "../../../infra/storage-lifecycle.json"), "utf8"));

describe("infra/storage-lifecycle.json (red de seguridad de B5a, README 4b paso 3)", () => {
  test("una unica regla: delete a 100 dias sobre .m4a y .mp4 SOLO bajo users/", () => {
    expect(cfg.lifecycle.rule).toEqual([
      { action: { type: "Delete" }, condition: { age: 100, matchesPrefix: ["users/"], matchesSuffix: [".m4a", ".mp4"] } },
    ]);
  });

  test("100 dias >= 90 de caducidad (contados desde finalize) + el tope de 20 h de finalize + >= 7 dias de margen para la purga", () => {
    // `age` cuenta desde la creacion del objeto; la caducidad del doc, desde finalize, que nunca escribe `ready` pasado
    // READY_MAX_OBJECT_AGE_MS. La regla debe llegar despues de la purga diaria (con presupuesto y posibles dias de retraso).
    expect(RECORDING_RETENTION_DAYS).toBe(90);
    const DAY = 24 * 60 * 60 * 1000;
    const latestExpiryAgeDays = RECORDING_RETENTION_DAYS + READY_MAX_OBJECT_AGE_MS / DAY;
    expect(cfg.lifecycle.rule[0].condition.age - latestExpiryAgeDays).toBeGreaterThanOrEqual(7);
  });

  test("es el formato de `gcloud storage buckets update --lifecycle-file` (solo lifecycle.rule)", () => {
    expect(Object.keys(cfg)).toEqual(["lifecycle"]);
    expect(Object.keys(cfg.lifecycle)).toEqual(["rule"]);
  });
});

test("el tope de antiguedad de finalize queda claramente por debajo de la edad minima del barrido de huerfanos (W3)", () => {
  expect(READY_MAX_OBJECT_AGE_MS).toBe(20 * 60 * 60 * 1000);
  expect(ORPHAN_MIN_AGE_MS - READY_MAX_OBJECT_AGE_MS).toBeGreaterThanOrEqual(4 * 60 * 60 * 1000);
});
