import { readFileSync } from "node:fs";
import { join } from "node:path";
import { RECORDING_CONSENT_VERSION } from "../../src/config/identity";
import { DEMOS_DIR, MAX_AUDIO_BYTES, MAX_RECORDINGS_PER_OWNER, RECORDINGS_DIR } from "../../src/config/recordings";

/**
 * Tripwire, no prueba de comportamiento: busca literales de `storage.rules` para que cambiar una constante de Functions
 * sin tocar las reglas (o al reves) rompa CI (REQ-REC-01 "Constantes unicas"). El comportamiento lo prueban
 * `rules-tests/test/storage-recordings.test.ts` y `storage-access-budget.test.ts`.
 */
const rules = readFileSync(join(__dirname, "../../../storage.rules"), "utf8")
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/\/\/.*$/gm, "");

describe("tripwire de literales: storage.rules <-> functions/src/config", () => {
  test("el cupo de la regla de subida es MAX_RECORDINGS_PER_OWNER", () => {
    expect(MAX_RECORDINGS_PER_OWNER).toBe(20);
    expect(rules).toContain(`u.get('recordingCount', 0) < ${MAX_RECORDINGS_PER_OWNER}`);
  });

  test("el tamano maximo de audio de la regla es MAX_AUDIO_BYTES (25 MiB)", () => {
    expect(MAX_AUDIO_BYTES).toBe(25 * 1024 * 1024);
    expect(rules).toContain("r.size <= 25 * 1024 * 1024");
  });

  test("la regla exige recordingConsentVersion >= RECORDING_CONSENT_VERSION", () => {
    expect(rules).toContain(`u.get('recordingConsentVersion', 0) >= ${RECORDING_CONSENT_VERSION}`);
  });

  test("sello de epoca de captura (W3 de B4b): metadato recordingConsentEpoch admitido y atado a la epoca del doc (ausente = 0)", () => {
    expect(rules).toMatch(/hasOnly\(\[[^\]]*'recordingConsentEpoch'/);
    expect(rules).toContain("int(m.recordingConsentEpoch) == u.get('recordingConsentEpoch', 0)");
    const finalize = readFileSync(join(__dirname, "../../src/recordings/finalize.ts"), "utf8");
    expect(finalize).toContain("info.metadata.recordingConsentEpoch !== String(result.consentEpoch)");
  });

  test("rutas de objeto: grabaciones en RECORDINGS_DIR y demos del profesor en DEMOS_DIR (C1, rutas disjuntas)", () => {
    expect([RECORDINGS_DIR, DEMOS_DIR]).toEqual(["recordings", "demos"]);
    expect(rules).toContain(`match /users/{uid}/${RECORDINGS_DIR}/{file} {`);
    expect(rules).toContain(`match /users/{uid}/${DEMOS_DIR}/{file} {`);
  });
});
