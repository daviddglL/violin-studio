import { FieldValue } from "firebase-admin/firestore";
import { CONSENT_TYPES, buildConsentDoc, consentDocId } from "../../src/consent/consent-docs";

test("consentDocId es determinista y legible", () => {
  expect(consentDocId("privacy_policy", 3, "self")).toBe("privacy_policy_v3_self");
  expect(consentDocId("terms", 1, "self")).toBe("terms_v1_self");
  expect(consentDocId("privacy_policy", 3, "self")).toBe(consentDocId("privacy_policy", 3, "self"));
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
