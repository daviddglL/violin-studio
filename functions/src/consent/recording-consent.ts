import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { requireObject } from "../common/validation";
import { RecordingFlags } from "../config/features";
import { CURRENT_POLICY_VERSION, RECORDING_CONSENT_VERSION } from "../config/identity";
import { blurRequiredFor } from "../identity/age";
import { buildConsentDoc, consentDocId, recordingEpochOf } from "./consent-docs";

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
