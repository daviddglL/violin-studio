import { reevaluatedStatus } from "../../src/consent/reevaluate";

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
