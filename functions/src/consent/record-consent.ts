import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { requireObject } from "../common/validation";
import { CURRENT_POLICY_VERSION } from "../config/identity";
import { syncClaims } from "../identity/claims";
import { buildConsentDoc, consentDocId, consentEpochOf, ConsentType } from "./consent-docs";

export interface RecordConsentDeps {
  db: Firestore;
  auth: Auth;
  /** Versión vigente; solo se inyecta en tests, en producción es la de `config/identity`. */
  currentVersion?: number;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface RecordConsentResult {
  consentStatus: "granted";
}

/** Documentos que acepta el adulto: política de privacidad y términos de la versión vigente. */
const ADULT_TYPES: readonly ConsentType[] = ["privacy_policy", "terms"];

function parseVersion(data: unknown, current: number): number {
  const version = requireObject(data).policyVersion;
  if (typeof version !== "number" || !Number.isInteger(version) || version < 1) {
    throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "policyVersion" });
  }
  if (version > current) throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "policyVersion" });
  if (version < current) {
    throw fail("failed-precondition", ErrorReason.POLICY_OUTDATED, { currentVersion: current });
  }
  return version;
}

/**
 * Registra el consentimiento de un adulto. La decisión se toma contra el documento `users/{uid}`
 * (nunca contra el claim). Un menor (o con `isMinor` no booleano: fail-closed) no puede consentir.
 * Idempotente: los ids de consent son deterministas por época de concesión y no se sobrescribe ninguno existente.
 */
export async function recordConsentHandler(
  deps: RecordConsentDeps,
  uid: string,
  data: unknown,
): Promise<RecordConsentResult> {
  const current = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  const version = parseVersion(data, current);
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const consentsRef = userRef.collection(COLLECTIONS.consents);

  const changed = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const doc = snap.data();
    if (!snap.exists || !doc || doc.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
    if (doc.isMinor !== false) throw fail("permission-denied", ErrorReason.GUARDIAN_REQUIRED);
    if (doc.consentStatus === "granted" && doc.policyVersion === version) return false;

    const epoch = consentEpochOf(doc);
    const refs = ADULT_TYPES.map((type) => consentsRef.doc(consentDocId(type, version, "self", epoch)));
    const existing = await tx.getAll(...refs);
    ADULT_TYPES.forEach((type, i) => {
      if (!existing[i].exists) tx.create(refs[i], buildConsentDoc(type, version, "self"));
    });
    tx.update(userRef, { consentStatus: "granted", policyVersion: version, updatedAt: FieldValue.serverTimestamp() });
    return true;
  });

  await syncClaims({ db: deps.db, auth: deps.auth, policyVersion: current }, uid);
  (deps.log ?? ((m, d) => logger.info(m, d)))("recordConsent", { changed, version });
  return { consentStatus: "granted" };
}
