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

function purgeOf(doc: Record<string, unknown>): Record<string, unknown> | undefined {
  const p = doc.recordingPurge;
  return typeof p === "object" && p !== null ? (p as Record<string, unknown>) : undefined;
}

function isPurgePending(doc: Record<string, unknown>): boolean {
  return purgeOf(doc)?.pending === true;
}

/**
 * Desvincula la concesión/solicitud de grabación del tutor (`recordingGuardian.requestId`) conservando `sends` (límite de
 * reenvíos). Sin ese vínculo un enlace viejo del tutor no puede revocar una concesión posterior ni reanimar una solicitud.
 * Solo si el mapa existe: un borrado anidado sobre un mapa ausente no debe crear uno vacío.
 */
export function clearRecordingRequest(doc: Record<string, unknown>): Record<string, unknown> {
  return typeof doc.recordingGuardian === "object" && doc.recordingGuardian !== null
    ? { "recordingGuardian.requestId": FieldValue.delete() }
    : {};
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
 * Consentimiento de grabación del propio usuario (REQ-CON-R01, U5): mientras `recordingPurge.pending` siga activo falla
 * con `TEMPORARILY_UNAVAILABLE` (reintentable) para que una purga en curso no borre grabaciones de la nueva concesión.
 * Solo 14+ (`isMinor == false`; un valor no booleano
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
    // Purga de una revocación anterior aún en curso (B5b): reconsentir ahora mezclaría grabaciones nuevas con el borrado.
    if (isPurgePending(doc)) throw fail("unavailable", ErrorReason.TEMPORARILY_UNAVAILABLE);
    if (doc.recordingConsent === "granted" && doc.recordingConsentVersion === version) return false;

    const ref = userRef.collection(COLLECTIONS.consents).doc(consentDocId("recording_sharing", version, "self", recordingEpochOf(doc)));
    if (!(await tx.get(ref)).exists) tx.create(ref, buildConsentDoc("recording_sharing", version, "self"));
    tx.update(userRef, {
      recordingConsent: "granted",
      recordingConsentVersion: version,
      blurRequired: blur,
      // La concesión es del propio usuario: ningún enlace del tutor (de una solicitud anterior) puede revocarla.
      ...clearRecordingRequest(doc),
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
 * se cierra), `recordingConsent = revoked`, época + 1 y `recordingPurge {pending, since, epoch}` para que el borrado
 * (B5b) sea reanudable y acotado: `epoch` es la mayor época cerrada aún sin purgar (con una purga ya pendiente se
 * conservan su `since` y el máximo de épocas, nunca se pierde el pendiente). Nunca toca `consentStatus` ni `consentOk`. Una versión guardada ilegible se registra como 0.
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
  const prev = isPurgePending(doc) ? purgeOf(doc) : undefined;
  const prevEpoch = prev && typeof prev.epoch === "number" && Number.isInteger(prev.epoch) ? prev.epoch : -1;
  tx.update(userRef, {
    recordingConsent: "revoked",
    recordingConsentEpoch: closing + 1,
    ...clearRecordingRequest(doc),
    recordingPurge: {
      pending: true,
      since: prev?.since ?? FieldValue.serverTimestamp(),
      epoch: Math.max(closing, prevEpoch),
    },
    updatedAt: FieldValue.serverTimestamp(),
  });
  return closing;
}

/** Borrado efectivo de las grabaciones tras una revocacion (`purgeRevokedRecordings`); inyectado para no acoplar el handler a Storage. */
export type PurgeRecordingsHook = (uid: string) => Promise<unknown>;

export interface RevokeRecordingConsentDeps {
  db: Firestore;
  log?: (message: string, data: Record<string, unknown>) => void;
  /**
   * Se ejecuta DESPUES de la transaccion de revocacion (REQ-REC-09). Su fallo nunca deshace ni falla la revocacion (ya consta;
   * `recordingPurge.pending` sigue y la reanudan la propia llamada repetida o la purga diaria): solo se registra el codigo.
   */
  purgeRecordings?: PurgeRecordingsHook;
}

/** Lanza la purga tras revocar sin que un fallo escape: el estado revocado ya esta escrito y la purga es reanudable. */
export async function runPurgeAfterRevocation(
  hook: PurgeRecordingsHook | undefined,
  uid: string,
  log: (message: string, data: Record<string, unknown>) => void,
): Promise<void> {
  if (!hook) return;
  try {
    await hook(uid);
  } catch (e) {
    const code = (e as { code?: unknown } | null)?.code;
    log("revokeRecordingConsent.purgeFailed", { code: typeof code === "string" || typeof code === "number" ? code : "unknown" });
  }
}

export interface RevokeRecordingConsentResult {
  /** Estado resultante: `none` si no había nada que revocar. */
  recordingConsent: string;
}

/**
 * Revoca el consentimiento de grabación del propio usuario (también un menor de 14: la revocación protege datos).
 * Solo actúa sobre `granted`; en cualquier otro estado es un no-op sin escrituras. No toca el consentimiento base.
 * El borrado efectivo de las grabaciones lo hace `purgeRecordings` (B5b.3) justo despues; en cualquier estado que no sea
 * `granted` la llamada repetida reanuda una purga que siga pendiente (reintento tras un fallo parcial).
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
    writeRecordingRevocation(tx, userRef, doc, "self");
    return { revoked: true as const, state: "revoked" };
  });
  const log = deps.log ?? ((m, d) => logger.info(m, d));
  log("revokeRecordingConsent", { revoked: outcome.revoked });
  await runPurgeAfterRevocation(deps.purgeRecordings, uid, log);
  return { recordingConsent: outcome.state };
}

/**
 * Revocación de la grabación por el tutor desde su enlace (REQ-GRD-R02): en una transacción exige que la concesión activa
 * sea la de ESA solicitud (`recordingGuardian.requestId`) y la revoca con `writeRecordingRevocation(..., "guardian")`.
 * No exige `isMinor` (la revocación protege datos aunque el menor ya haya cumplido 14) ni toca el consentimiento base.
 * Sin concesión activa de esa solicitud falla con `NO_ACTIVE_CONSENT` sin escrituras.
 */
export async function revokeRecordingByGuardian(deps: RevokeRecordingConsentDeps, uid: string, requestId: string): Promise<void> {
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  await deps.db.runTransaction(async (tx) => {
    const doc = (await tx.get(userRef)).data();
    if (!doc || doc.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
    const owner = (doc.recordingGuardian as { requestId?: unknown } | undefined)?.requestId;
    if (doc.recordingConsent !== "granted" || owner !== requestId) throw fail("failed-precondition", ErrorReason.NO_ACTIVE_CONSENT);
    writeRecordingRevocation(tx, userRef, doc, "guardian");
  });
  const log = deps.log ?? ((m, d) => logger.info(m, d));
  log("revokeRecordingConsent", { revoked: true, by: "guardian" });
  await runPurgeAfterRevocation(deps.purgeRecordings, uid, log);
}
