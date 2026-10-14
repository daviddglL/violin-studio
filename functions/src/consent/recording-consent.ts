import { DocumentReference, FieldValue, Firestore, Transaction } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { requireObject } from "../common/validation";
import { RecordingFlags } from "../config/features";
import { CURRENT_POLICY_VERSION, RECORDING_CONSENT_VERSION } from "../config/identity";
import { blurRequiredFor } from "../identity/age";
import { buildConsentDoc, consentDocId, GrantedBy, recordingEpochOf } from "./consent-docs";

export interface GrantRecordingConsentDeps {
  db: Firestore;
  clock: Clock;
  flags: RecordingFlags;
  /** Versiones vigentes; solo se inyectan en tests, en producción son las de `config/identity`. */
  currentPolicyVersion?: number;
  currentRecordingVersion?: number;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface GrantRecordingConsentResult {
  recordingConsent: "granted";
}

function parseVersion(data: unknown, current: number): number {
  const version = requireObject(data).version;
  if (typeof version !== "number" || !Number.isInteger(version) || version < 1 || version > current) {
    throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "version" });
  }
  if (version < current) {
    throw fail("failed-precondition", ErrorReason.RECORDING_CONSENT_OUTDATED, { currentVersion: current });
  }
  return version;
}

/**
 * Consentimiento de grabación del propio usuario (REQ-CON-R01, U5): solo 14+ (`isMinor == false`; un valor no booleano
 * cuenta como menor) con el consentimiento base vigente y sin borrado en curso. Decide el documento, nunca el claim.
 * Recalcula `blurRequired` desde `birthDate` y, si es < 18, exige `MINOR_RECORDING_ENABLED`. NO toca `consentStatus` ni
 * `consentOk`. Idempotente: el id del consent es determinista por época de grabación y no se sobrescribe ninguno.
 */
export async function grantRecordingConsentHandler(
  deps: GrantRecordingConsentDeps,
  uid: string,
  data: unknown,
): Promise<GrantRecordingConsentResult> {
  const policy = deps.currentPolicyVersion ?? CURRENT_POLICY_VERSION;
  const version = parseVersion(data, deps.currentRecordingVersion ?? RECORDING_CONSENT_VERSION);
  if (!deps.flags.recordingsEnabled) throw fail("failed-precondition", ErrorReason.RECORDING_DISABLED);
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const now = deps.clock();

  const changed = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const doc = snap.data();
    if (!snap.exists || !doc || doc.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
    if (doc.isMinor !== false) throw fail("failed-precondition", ErrorReason.GUARDIAN_REQUIRED);
    if (doc.consentStatus !== "granted" || doc.policyVersion !== policy) {
      throw fail("failed-precondition", ErrorReason.NO_ACTIVE_CONSENT);
    }
    const blur = blurRequiredFor(doc.birthDate, now);
    if (blur && !deps.flags.minorRecordingEnabled) throw fail("failed-precondition", ErrorReason.RECORDING_DISABLED);
    if (doc.recordingConsent === "granted" && doc.recordingConsentVersion === version) return false;

    const ref = userRef.collection(COLLECTIONS.consents).doc(consentDocId("recording_sharing", version, "self", recordingEpochOf(doc)));
    if (!(await tx.get(ref)).exists) tx.create(ref, buildConsentDoc("recording_sharing", version, "self"));
    tx.update(userRef, {
      recordingConsent: "granted",
      recordingConsentVersion: version,
      blurRequired: blur,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return true;
  });

  (deps.log ?? ((m, d) => logger.info(m, d)))("grantRecordingConsent", { changed, version });
  return { recordingConsent: "granted" };
}

/**
 * Escrituras de la revocación de grabación (REQ-CON-R02), a ejecutar DENTRO de una transacción que ya leyó `doc` con
 * `recordingConsent == "granted"`: registro append-only `recording_revocation` con `action = revoked` (id por época que
 * se cierra), `recordingConsent = revoked`, época + 1 y `recordingPurge.pending` para que el borrado (B5b) sea
 * reanudable. Nunca toca `consentStatus` ni `consentOk`. Una versión guardada ilegible se registra como 0.
 * Devuelve la época cerrada.
 */
export function writeRecordingRevocation(
  tx: Transaction,
  userRef: DocumentReference,
  doc: Record<string, unknown>,
  by: GrantedBy,
): number {
  const closing = recordingEpochOf(doc);
  const v = doc.recordingConsentVersion;
  const version = typeof v === "number" && Number.isInteger(v) && v >= 0 ? v : 0;
  const ref = userRef.collection(COLLECTIONS.consents).doc(consentDocId("recording_revocation", version, by, closing));
  tx.create(ref, buildConsentDoc("recording_revocation", version, by, "revoked"));
  tx.update(userRef, {
    recordingConsent: "revoked",
    recordingConsentEpoch: closing + 1,
    recordingPurge: { pending: true, since: FieldValue.serverTimestamp() },
    updatedAt: FieldValue.serverTimestamp(),
  });
  return closing;
}

export interface RevokeRecordingConsentDeps {
  db: Firestore;
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface RevokeRecordingConsentResult {
  /** Estado resultante: `none` si no había nada que revocar. */
  recordingConsent: string;
}

/**
 * Revoca el consentimiento de grabación del propio usuario (también un menor de 14: la revocación protege datos).
 * Solo actúa sobre `granted`; en cualquier otro estado es un no-op sin escrituras. No toca el consentimiento base.
 * El borrado efectivo de las grabaciones lo completa B5b a partir de `recordingPurge.pending`.
 */
export async function revokeRecordingConsentHandler(
  deps: RevokeRecordingConsentDeps,
  uid: string,
): Promise<RevokeRecordingConsentResult> {
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const outcome = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const doc = snap.data();
    if (!snap.exists || !doc || doc.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
    if (doc.recordingConsent !== "granted") {
      return { revoked: false as const, state: typeof doc.recordingConsent === "string" ? doc.recordingConsent : "none" };
    }
    return { revoked: true as const, state: "revoked", closed: writeRecordingRevocation(tx, userRef, doc, "self") };
  });
  (deps.log ?? ((m, d) => logger.info(m, d)))("revokeRecordingConsent", { revoked: outcome.revoked });
  return { recordingConsent: outcome.state };
}
