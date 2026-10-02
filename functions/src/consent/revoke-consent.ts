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
 * distinta (la época sube en la misma transacción), así que no choca con revocaciones previas, y `tx.create`
 * impediría sobrescribir una. Supuesto: solo Functions escribe `consentEpoch` (las reglas se lo deniegan al cliente);
 * una época corrupta se lee como 0 y podría colisionar con una revocación e0 existente (la transacción fallaría
 * por `ALREADY_EXISTS` en lugar de sobrescribir: fail-closed).
 *
 * Cualquier usuario puede revocar (también un menor cuyo consentimiento dio el tutor): se registra `by` tal y
 * como llega; el callable usa `"self"`, que es exacto porque quien revoca es el propio usuario.
 *
 * Si el consentimiento ya no está `granted` (p. ej. reintento tras un fallo de `syncClaims`), no se escribe nada,
 * pero se intenta curar los claims (best-effort) antes de rechazar con NO_ACTIVE_CONSENT.
 */
export async function revokeConsentCore(
  deps: RevokeConsentDeps,
  uid: string,
  by: GrantedBy,
): Promise<RevokeConsentResult> {
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const log = deps.log ?? ((m, d) => logger.info(m, d));
  const syncDeps = { db: deps.db, auth: deps.auth, policyVersion: deps.currentVersion ?? CURRENT_POLICY_VERSION };
  const outcome = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const doc = snap.data();
    if (!snap.exists || !doc || doc.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
    if (doc.consentStatus !== "granted") return { revoked: false as const };

    const closing = consentEpochOf(doc);
    // Nunca se bloquea una revocación: una versión ilegible se registra como 0 y se marca como anomalía.
    const validVersion = typeof doc.policyVersion === "number" && Number.isInteger(doc.policyVersion);
    const version = validVersion ? (doc.policyVersion as number) : 0;
    const ref = userRef.collection(COLLECTIONS.consents).doc(consentDocId("revocation", version, by, closing));
    tx.create(ref, buildConsentDoc("revocation", version, by));
    tx.update(userRef, {
      consentStatus: "revoked",
      consentEpoch: closing + 1,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return { revoked: true as const, closing, anomaly: !validVersion };
  });

  if (!outcome.revoked) {
    try {
      await syncClaims(syncDeps, uid);
    } catch (e) {
      const code = (e as { code?: unknown }).code;
      log("revokeConsent.syncFailed", { code: typeof code === "string" ? code : "unknown" });
    }
    throw fail("failed-precondition", ErrorReason.NO_ACTIVE_CONSENT);
  }

  await syncClaims(syncDeps, uid);
  log("revokeConsent", { by, closedEpoch: outcome.closing, ...(outcome.anomaly ? { anomaly: "policyVersion" } : {}) });
  return { consentStatus: "revoked" };
}
