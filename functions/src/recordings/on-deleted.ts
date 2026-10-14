import { DocumentData, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { deleteRecording, deleteReviews, removeStoredObject } from "./delete-recording";
import type { ObjectStore } from "./finalize";
import { parseObjectPath } from "./validate";

export interface OnDeletedDeps {
  db: Firestore;
  store: ObjectStore;
  /** Sumidero de logs sin PII (solo `uidHash`); por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/** Lo que trae `onDocumentDeleted`: el doc ya NO existe; `data` es su contenido justo antes de borrarse. */
export interface DeletedRecordingEvent {
  uid: string;
  id: string;
  data: DocumentData | undefined;
  /**
   * Identidad de ESTA instancia del doc (su `createTime`, `segundos.nanos`): dos entregas del mismo evento la comparten;
   * un doc recreado con el mismo id y generation (finalize tardio, W5) tiene otra. Sin ella se usa `createdAt`.
   */
  instance?: string;
  /** Instante del borrado (`event.time`): un `recordingCount` reconciliado despues ya no cuenta este doc. */
  deletedAt?: Date;
}

/** `ignored` = el evento no trae el doc (no se sabe que objeto borrar). */
export type OnDeletedOutcome = "cleaned" | "ignored";

/**
 * Retencion de las marcas de idempotencia (TTL de Firestore sobre `expireAt`): muy por encima de la ventana de reintentos
 * de un trigger (24 h en Functions v2), asi una reentrega nunca encuentra la marca ya caducada.
 */
export const LEDGER_TTL_DAYS = 30;
const DAY_MS = 24 * 60 * 60 * 1000;

/** `decremented` = bajo; `duplicate` = esta instancia ya se desconto; `reconciled` = una reconciliacion posterior ya la conto. */
type CountOutcome = "decremented" | "duplicate" | "reconciled" | "untouched";

const tsKey = (v: unknown): string | undefined => (v instanceof Timestamp ? `${v.seconds}.${v.nanoseconds}` : undefined);

/** Id de la marca: id del doc + generation + instancia. Nunca contiene `/` (ids y generations validados por la ruta). */
function ledgerId(event: DeletedRecordingEvent, data: DocumentData): string {
  const instance = event.instance ?? tsKey(data.createdAt) ?? "na";
  return `${event.id}_${String(data.generation ?? "na")}_${instance}`.replace(/\//g, "_");
}

/**
 * Baja `recordingCount` en uno, idempotente (W2 de la revision fresca de B5a): en la MISMA transaccion se crea la marca
 * `users/{uid}/recordingCountLedger/{id}_{generation}_{instancia}`; si ya existe, otra entrega ya lo hizo y no se baja.
 * Si el contador se reconcilio (`recordingCountReconciledAt`) en o despues del borrado, el recuento ya excluye este doc
 * y tampoco se baja (una reconciliacion que corre con el trigger en vuelo no deja el contador una plaza por debajo).
 * Usuario inexistente (cuenta en cascada) o en borrado: no se escribe nada (no se recrea `users/{uid}` ni se dejan marcas
 * que sobrevivan a la cascada). Contador ausente, 0 o corrupto: se deja la marca y no se toca (lo repara la reconciliacion).
 */
async function decrementCount(db: Firestore, uid: string, markId: string, deletedAt: Date | undefined, now: Date): Promise<CountOutcome> {
  const ref = db.collection(COLLECTIONS.users).doc(uid);
  const mark = ref.collection(COLLECTIONS.recordingCountLedger).doc(markId);
  return db.runTransaction(async (tx): Promise<CountOutcome> => {
    const [snap, markSnap] = await Promise.all([tx.get(ref), tx.get(mark)]);
    const u = snap.data();
    if (!snap.exists || !u || (u.deletion !== undefined && u.deletion !== null)) return "untouched";
    if (markSnap.exists) return "duplicate";
    tx.create(mark, { expireAt: Timestamp.fromMillis(now.getTime() + LEDGER_TTL_DAYS * DAY_MS) });
    const reconciledAt = u.recordingCountReconciledAt;
    if (deletedAt && reconciledAt instanceof Timestamp && reconciledAt.toMillis() >= deletedAt.getTime()) return "reconciled";
    const c = u.recordingCount;
    if (typeof c !== "number" || !Number.isInteger(c) || c <= 0) return "untouched";
    tx.update(ref, { recordingCount: c - 1 });
    return "decremented";
  });
}

/**
 * `onDocumentDeleted(users/{uid}/recordings/{r})` (A12, A21; REQ-REC-07/14, REQ-RVW-05). Limpia lo que el doc deja atras:
 * el objeto (por la `objectPath` + `generation` GUARDADAS, 404/412 = OK), las `reviews` y, si era `ready`, una plaza del cupo.
 *
 * Ventana de resurreccion (W5): un `finalizeRecording` reintentado puede recrear el doc entre su borrado y el del objeto.
 * Tras borrar el objeto se relee el doc: si existe con la MISMA `objectPath` + `generation`, se borra otra vez (el trigger de
 * ESE borrado devuelve la plaza que el finalize recreado subio; una generation distinta es una subida legitima y no se toca).
 * Reviews (W4): si en ese id ya vive un doc NUEVO (otra `objectPath`/`generation`), sus reviews no se tocan: un trigger
 * retrasado o reintentado nunca borra las de la grabacion que sustituyo a la borrada.
 * El contador baja AL FINAL y de forma idempotente (marca por instancia del doc): un fallo anterior se reintenta entero y
 * una entrega duplicada no baja dos veces.
 */
export async function onRecordingDeletedHandler(deps: OnDeletedDeps, event: DeletedRecordingEvent): Promise<OnDeletedOutcome> {
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const uidHash = sha256Hex(event.uid).slice(0, 12);
  const { data } = event;
  if (!data) {
    log("onRecordingDeleted", { outcome: "ignored", uidHash });
    return "ignored";
  }
  const ref = deps.db.collection(COLLECTIONS.users).doc(event.uid).collection(COLLECTIONS.recordings).doc(event.id);
  const own = await removeStoredObject(deps.store, event.uid, event.id, data);

  const same = (d: DocumentData) => d.objectPath === data.objectPath && String(d.generation) === String(data.generation);
  if (own) await deleteRecording(deps, ref, { guard: same });
  const current = (await ref.get()).data();
  const newer = current !== undefined && !same(current);
  if (!newer) await deleteReviews(deps.db, ref);

  const count = data.status === "ready" ? await decrementCount(deps.db, event.uid, ledgerId(event, data), event.deletedAt, new Date()) : "untouched";
  const dir = typeof data.objectPath === "string" ? parseObjectPath(data.objectPath)?.dir : undefined;
  log("onRecordingDeleted", {
    outcome: "cleaned",
    counted: count === "decremented",
    ...(count === "duplicate" || count === "reconciled" ? { count } : {}),
    ...(newer ? { reviews: "kept_newer" } : {}),
    ...(own ? {} : { anomaly: "object_path" }),
    ...(dir ? { dir } : {}),
    uidHash,
  });
  return "cleaned";
}
