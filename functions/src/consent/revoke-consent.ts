import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { CURRENT_POLICY_VERSION } from "../config/identity";
import { syncClaims } from "../identity/claims";
import { buildConsentDoc, consentDocId, consentEpochOf, GrantedBy } from "./consent-docs";

export interface RevokeConsentDeps {
  db: Firestore;
  auth: Auth;
  /** Versión vigente (solo para alinear claims); solo se inyecta en tests. */
  currentVersion?: number;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface RevokeConsentResult {
  consentStatus: "revoked";
}

/**
 * Núcleo de la revocación (D2), reutilizable por la revocación del tutor (3c) con `by = "guardian"`.
 * En UNA transacción: exige `consentStatus == "granted"` en el documento (no en el claim), lo pasa a `revoked`,
 * crea el consent `revocation` y sube `consentEpoch` (ausente = 0) para que un reconsentimiento posterior cree
 * registros nuevos. Nunca borra consents previos (append-only).
 *
 * Id de la revocación: `revocation_v{versión}_{by}_e{época que se cierra}`. Cada revocación cierra una época
 * distinta (la época sube en la misma transacción), así que no puede chocar con revocaciones anteriores y
 * `tx.create` falla si alguien intentara reescribir una.
 */
export async function revokeConsentCore(
  deps: RevokeConsentDeps,
  uid: string,
  by: GrantedBy,
): Promise<RevokeConsentResult> {
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const epoch = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const doc = snap.data();
    if (!snap.exists || !doc || doc.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
    if (doc.consentStatus !== "granted") throw fail("failed-precondition", ErrorReason.NO_ACTIVE_CONSENT);

    const closing = consentEpochOf(doc);
    const version = typeof doc.policyVersion === "number" && Number.isInteger(doc.policyVersion) ? doc.policyVersion : 0;
    const ref = userRef.collection(COLLECTIONS.consents).doc(consentDocId("revocation", version, by, closing));
    tx.create(ref, buildConsentDoc("revocation", version, by));
    tx.update(userRef, {
      consentStatus: "revoked",
      consentEpoch: closing + 1,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return closing;
  });

  await syncClaims({ db: deps.db, auth: deps.auth, policyVersion: deps.currentVersion ?? CURRENT_POLICY_VERSION }, uid);
  (deps.log ?? ((m, d) => logger.info(m, d)))("revokeConsent", { by, closedEpoch: epoch });
  return { consentStatus: "revoked" };
}
