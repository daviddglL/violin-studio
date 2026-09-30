import { FieldValue } from "firebase-admin/firestore";

export const CONSENT_TYPES = ["privacy_policy", "terms", "guardian_privacy_policy", "revocation"] as const;
export type ConsentType = (typeof CONSENT_TYPES)[number];
export type GrantedBy = "self" | "guardian";

/**
 * Época de concesión del usuario (`users/{uid}.consentEpoch`, ausente = 0). La revocación (2b) la incrementa,
 * así un nuevo consentimiento de la misma versión crea registros nuevos en lugar de chocar con los anteriores.
 * Un valor no entero o negativo se lee como 0 (nunca se usa un id arbitrario).
 */
export function consentEpochOf(doc: Record<string, unknown>): number {
  const e = doc.consentEpoch;
  return typeof e === "number" && Number.isInteger(e) && e >= 0 ? e : 0;
}

/**
 * Id determinista (`privacy_policy_v3_self_e0`): reintentar la misma aceptación dentro de la misma época
 * no duplica documentos; tras una revocación la época sube y la traza conserva cada concesión.
 */
export function consentDocId(type: ConsentType, version: number, grantedBy: GrantedBy, epoch: number): string {
  return `${type}_v${version}_${grantedBy}_e${epoch}`;
}

/** Registro append-only: solo tipo, versión, quién y marca de tiempo de servidor; nada más. */
export function buildConsentDoc(type: ConsentType, version: number, grantedBy: GrantedBy) {
  return { type, version, grantedBy, timestamp: FieldValue.serverTimestamp() };
}
