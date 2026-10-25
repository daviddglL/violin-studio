import { DocumentData, FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";

export type CompletePurgeReason = "USER_NOT_FOUND" | "DELETING" | "RECORDINGS_REMAIN";

/** Error del cierre manual; el mensaje solo lleva el motivo (nunca PII). */
export class CompletePurgeError extends Error {
  constructor(
    readonly reason: CompletePurgeReason,
    readonly remaining = 0,
  ) {
    super(reason);
    this.name = "CompletePurgeError";
  }
}

export interface CompletePurgeResult {
  outcome: "completed" | "not_pending";
  /** Docs `ready` con epoca <= la de la purga que seguian ahi (solo > 0 con `force`). */
  remaining: number;
}

/** Misma lectura que `purgeRevokedRecordings`: epoca de purga corrupta = infinita; doc sin epoca = anterior a todo. */
const purgeEpoch = (p: { epoch?: unknown }): number =>
  typeof p.epoch === "number" && Number.isInteger(p.epoch) ? p.epoch : Number.POSITIVE_INFINITY;
const docEpoch = (d: DocumentData): number =>
  typeof d.recordingConsentEpoch === "number" ? d.recordingConsentEpoch : Number.NEGATIVE_INFINITY;

/**
 * Cierre MANUAL de una purga de revocacion atascada (W4 de la revision fresca B5b; runbook en README), tras limpiar a mano lo
 * que `purgeRevokedRecordings` no pudo borrar. En UNA transaccion: exige `users/{uid}` vivo (ni inexistente ni en borrado:
 * nunca se recrea) con `recordingPurge.pending`; cuenta los docs `ready` de `users/{uid}/recordings` con epoca <= la de la
 * purga y, si queda alguno y no hay `force`, rechaza `RECORDINGS_REMAIN` sin escribir. Si no, baja `pending` y sella
 * `completedAt`, `completedBy: "admin"` y `forced` (conserva `since`/`epoch`). Los huerfanos de Storage NO se comprueban
 * (no hay listado transaccional): limpiarlos es parte del runbook. Log solo con `uidHash` y contadores.
 */
export async function completeRecordingPurge(
  db: Firestore,
  uid: string,
  opts: { force?: boolean; log?: (message: string, data: Record<string, unknown>) => void } = {},
): Promise<CompletePurgeResult> {
  const force = opts.force === true;
  const userRef = db.collection(COLLECTIONS.users).doc(uid);
  const result = await db.runTransaction(async (tx): Promise<CompletePurgeResult> => {
    const user = (await tx.get(userRef)).data();
    if (!user) throw new CompletePurgeError("USER_NOT_FOUND");
    if (user.deletion !== undefined && user.deletion !== null) throw new CompletePurgeError("DELETING");
    const p = user.recordingPurge as { pending?: unknown; epoch?: unknown } | undefined;
    if (typeof p !== "object" || p === null || p.pending !== true) return { outcome: "not_pending", remaining: 0 };
    const bound = purgeEpoch(p);
    const docs = await tx.get(userRef.collection(COLLECTIONS.recordings));
    const remaining = docs.docs.filter((d) => d.data().status === "ready" && docEpoch(d.data()) <= bound).length;
    if (remaining > 0 && !force) throw new CompletePurgeError("RECORDINGS_REMAIN", remaining);
    tx.update(userRef, {
      "recordingPurge.pending": false,
      "recordingPurge.completedAt": FieldValue.serverTimestamp(),
      "recordingPurge.completedBy": "admin",
      "recordingPurge.forced": force && remaining > 0,
    });
    return { outcome: "completed", remaining };
  });
  (opts.log ?? ((m, d) => logger.info(m, d)))("completeRecordingPurge", {
    uidHash: sha256Hex(uid).slice(0, 12),
    outcome: result.outcome,
    remaining: result.remaining,
    forced: force && result.remaining > 0,
  });
  return result;
}
