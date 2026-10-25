import { reevaluatedRecording, reevaluatedStatus } from "../../src/consent/reevaluate";

describe("reevaluatedStatus (D3)", () => {
  test.each([
    ["adulto granted v1 con vigente 2", { isMinor: false, consentStatus: "granted", policyVersion: 1 }, 2, "pending"],
    ["menor granted v1 con vigente 2", { isMinor: true, consentStatus: "granted", policyVersion: 1 }, 2, "pending"],
    ["granted con versión nula (fail-closed)", { consentStatus: "granted", policyVersion: null }, 2, "pending"],
    ["granted con versión no numérica (fail-closed)", { consentStatus: "granted", policyVersion: "1" }, 2, "pending"],
    ["granted vigente", { consentStatus: "granted", policyVersion: 2 }, 2, null],
    ["granted con versión futura (no se degrada)", { consentStatus: "granted", policyVersion: 3 }, 2, null],
    ["pending", { consentStatus: "pending", policyVersion: null }, 2, null],
    ["revoked", { consentStatus: "revoked", policyVersion: 1 }, 2, null],
    ["parental_pending", { consentStatus: "parental_pending", policyVersion: null }, 2, null],
    ["menor que cumple 14 con granted vigente (G8)", { isMinor: true, birthDate: "2012-01-01", consentStatus: "granted", policyVersion: 2 }, 2, null],
  ])("%s", (_n, doc, vigente, esperado) => {
    expect(reevaluatedStatus(doc, vigente)).toBe(esperado);
  });
});

describe("reevaluatedRecording (REQ-CON-R03): versión propia, independiente de la política", () => {
  test.each([
    ["granted con versión antigua", { recordingConsent: "granted", recordingConsentVersion: 1 }, 2, "none"],
    ["granted con versión nula (fail-closed)", { recordingConsent: "granted", recordingConsentVersion: null }, 2, "none"],
    ["granted sin versión (fail-closed)", { recordingConsent: "granted" }, 2, "none"],
    ["granted con versión no numérica", { recordingConsent: "granted", recordingConsentVersion: "1" }, 2, "none"],
    ["granted vigente", { recordingConsent: "granted", recordingConsentVersion: 2 }, 2, null],
    ["granted con versión futura (no se degrada)", { recordingConsent: "granted", recordingConsentVersion: 3 }, 2, null],
    ["revoked", { recordingConsent: "revoked", recordingConsentVersion: 1 }, 2, null],
    ["parental_pending", { recordingConsent: "parental_pending" }, 2, null],
    ["ausente (none)", {}, 2, null],
    ["la política antigua no influye", { consentStatus: "granted", policyVersion: 1, recordingConsent: "granted", recordingConsentVersion: 2 }, 2, null],
  ])("%s", (_n, doc, vigente, esperado) => {
    expect(reevaluatedRecording(doc, vigente)).toBe(esperado);
  });
});
