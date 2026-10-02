import { FieldValue } from "firebase-admin/firestore";
import { CONSENT_TYPES, buildConsentDoc, consentDocId, consentEpochOf } from "../../src/consent/consent-docs";

test("consentDocId es determinista y legible, e incluye la época de concesión", () => {
  expect(consentDocId("privacy_policy", 3, "self", 0)).toBe("privacy_policy_v3_self_e0");
  expect(consentDocId("terms", 1, "self", 2)).toBe("terms_v1_self_e2");
  expect(consentDocId("privacy_policy", 3, "self", 1)).toBe(consentDocId("privacy_policy", 3, "self", 1));
});

test("épocas distintas dan ids distintos para la misma versión (traza tras revocar)", () => {
  expect(consentDocId("terms", 1, "self", 0)).not.toBe(consentDocId("terms", 1, "self", 1));
});

test("consentEpochOf: ausente o no válida vale 0; enteros >= 0 se respetan", () => {
  expect(consentEpochOf({})).toBe(0);
  expect(consentEpochOf({ consentEpoch: 3 })).toBe(3);
  for (const v of [-1, 1.5, "2", null, NaN]) expect(consentEpochOf({ consentEpoch: v })).toBe(0);
});

test("tipos de consentimiento previstos", () => {
  expect([...CONSENT_TYPES]).toEqual(["privacy_policy", "terms", "guardian_privacy_policy", "revocation"]);
});

test("el builder solo guarda type/version/grantedBy y un timestamp de servidor", () => {
  const doc = buildConsentDoc("terms", 2, "self");
  expect(Object.keys(doc).sort()).toEqual(["grantedBy", "timestamp", "type", "version"]);
  expect(doc).toMatchObject({ type: "terms", version: 2, grantedBy: "self" });
  expect(doc.timestamp).toEqual(FieldValue.serverTimestamp());
});
