import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { CURRENT_POLICY_VERSION, RECORDING_CONSENT_VERSION } from "../config/identity";
import { claimsFromProfile, syncClaims } from "../identity/claims";

export interface ReevaluateDeps {
  db: Firestore;
  auth: Auth;
  /** Versión vigente; solo se inyecta en tests. */
  currentVersion?: number;
  /** Versión vigente del consentimiento de grabación; solo se inyecta en tests. */
  currentRecordingVersion?: number;
  /** `consentOk` del token de quien llama, si se conoce: permite evitar llamadas a Admin Auth innecesarias. */
  knownConsentOk?: boolean;
}

/**
 * Decisión pura (D3): un `granted` cuya versión es anterior a la vigente (o ilegible: fail-closed)
 * vuelve a `pending`; el resto no cambia. Devuelve el nuevo estado o `null` si no hay cambio.
 */
export function reevaluatedStatus(doc: Record<string, unknown>, currentVersion: number): "pending" | null {
  if (doc.consentStatus !== "granted") return null;
  const v = doc.policyVersion;
  if (typeof v === "number" && v >= currentVersion) return null;
  return "pending";
}

/**
 * Consentimiento de grabación con versión propia (REQ-CON-R03): un `granted` cuya `recordingConsentVersion` es anterior
 * a la vigente (o ilegible/ausente: fail-closed) pasa a `none`. No mira la política ni borra datos: solo bloquea nuevas
 * subidas y la lectura del profesor hasta reconsentir. `revoked` y `parental_pending` no se tocan.
 */
export function reevaluatedRecording(doc: Record<string, unknown>, currentRecordingVersion: number): "none" | null {
  if (doc.recordingConsent !== "granted") return null;
  const v = doc.recordingConsentVersion;
  if (typeof v === "number" && v >= currentRecordingVersion) return null;
  return "none";
}

/**
 * Reevalúa el consentimiento de `uid` contra la versión vigente (decide el documento, no el claim)
 * y deja los claims alineados. No migra datos y no hace nada si el usuario no tiene perfil.
 */
export async function reevaluateConsent(deps: ReevaluateDeps, uid: string): Promise<void> {
  const current = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  const currentRecording = deps.currentRecordingVersion ?? RECORDING_CONSENT_VERSION;
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);
  const { changed, expectedConsentOk } = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const doc = snap.data();
    if (!snap.exists || !doc) return { changed: false, expectedConsentOk: undefined };
    // Borrado en curso: no se reevalúa ni se toca el documento (el claim ya sale falso por `deletion`).
    const next = doc.deletion ? null : reevaluatedStatus(doc, current);
    const recording = doc.deletion ? null : reevaluatedRecording(doc, currentRecording);
    if (next || recording) {
      tx.update(ref, {
        ...(next ? { consentStatus: next } : {}),
        // `none` = campo ausente (REQ-PRF-T03).
        ...(recording ? { recordingConsent: FieldValue.delete() } : {}),
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
    const finalDoc = next ? { ...doc, consentStatus: next } : doc;
    return { changed: next !== null, expectedConsentOk: claimsFromProfile(finalDoc, current).consentOk };
  });
  // Sin cambios y con el claim del token ya correcto no hay nada que curar: se ahorra la llamada a Admin Auth.
  if (!changed && deps.knownConsentOk !== undefined && deps.knownConsentOk === expectedConsentOk) return;
  await syncClaims({ db: deps.db, auth: deps.auth, policyVersion: current }, uid);
}
