import { FieldValue } from "firebase-admin/firestore";

export const CONSENT_TYPES = ["privacy_policy", "terms", "guardian_privacy_policy", "revocation"] as const;
export type ConsentType = (typeof CONSENT_TYPES)[number];
export type GrantedBy = "self" | "guardian";

/** Id determinista (`privacy_policy_v3_self`): reintentar la misma aceptación no duplica documentos. */
export function consentDocId(type: ConsentType, version: number, grantedBy: GrantedBy): string {
  return `${type}_v${version}_${grantedBy}`;
}

/** Registro append-only: solo tipo, versión, quién y marca de tiempo de servidor; nada más. */
export function buildConsentDoc(type: ConsentType, version: number, grantedBy: GrantedBy) {
  return { type, version, grantedBy, timestamp: FieldValue.serverTimestamp() };
}
