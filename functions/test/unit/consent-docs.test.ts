import { FieldValue } from "firebase-admin/firestore";
import {
  CONSENT_TYPES,
  buildConsentDoc,
  consentDocId,
  consentEpochOf,
  recordingEpochOf,
} from "../../src/consent/consent-docs";

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
  expect([...CONSENT_TYPES]).toEqual([
    "privacy_policy",
    "terms",
    "guardian_privacy_policy",
    "revocation",
    "recording_sharing",
    "recording_revocation",
  ]);
});

test("el builder solo guarda type/version/grantedBy y un timestamp de servidor", () => {
  const doc = buildConsentDoc("terms", 2, "self");
  expect(Object.keys(doc).sort()).toEqual(["grantedBy", "timestamp", "type", "version"]);
  expect(doc).toMatchObject({ type: "terms", version: 2, grantedBy: "self" });
  expect(doc.timestamp).toEqual(FieldValue.serverTimestamp());
});

test("ids deterministas del consentimiento de grabación: recording_sharing_v{n}_{self|guardian}_e{epoch}", () => {
  expect(consentDocId("recording_sharing", 1, "self", 0)).toBe("recording_sharing_v1_self_e0");
  expect(consentDocId("recording_sharing", 2, "guardian", 3)).toBe("recording_sharing_v2_guardian_e3");
  expect(consentDocId("recording_revocation", 1, "self", 0)).toBe("recording_revocation_v1_self_e0");
  expect(consentDocId("recording_sharing", 1, "self", 0)).not.toBe(consentDocId("recording_sharing", 1, "self", 1));
});

test("recordingEpochOf: ausente o no válida vale 0 y es independiente de consentEpoch", () => {
  expect(recordingEpochOf({})).toBe(0);
  expect(recordingEpochOf({ recordingConsentEpoch: 4, consentEpoch: 1 })).toBe(4);
  expect(recordingEpochOf({ consentEpoch: 7 })).toBe(0);
  for (const v of [-1, 1.5, "2", null, NaN]) expect(recordingEpochOf({ recordingConsentEpoch: v })).toBe(0);
});

test("la revocación de grabación es append-only con action=revoked; sin action el doc no la lleva", () => {
  const doc = buildConsentDoc("recording_revocation", 1, "self", "revoked");
  expect(Object.keys(doc).sort()).toEqual(["action", "grantedBy", "timestamp", "type", "version"]);
  expect(doc).toMatchObject({ type: "recording_revocation", action: "revoked" });
  expect("action" in buildConsentDoc("recording_sharing", 1, "guardian")).toBe(false);
});
