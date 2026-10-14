import { readFileSync } from "node:fs";
import { join } from "node:path";
import { RECORDING_CONSENT_VERSION } from "../../src/config/identity";
import { MAX_AUDIO_BYTES, MAX_RECORDINGS_PER_OWNER } from "../../src/config/recordings";

/** Paridad entre las constantes de Functions y los literales de `storage.rules` (REQ-REC-01 "Constantes unicas"). */
const rules = readFileSync(join(__dirname, "../../../storage.rules"), "utf8")
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/\/\/.*$/gm, "");

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
