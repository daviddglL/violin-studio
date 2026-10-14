import { DocumentData, Firestore } from "firebase-admin/firestore";
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
}

/** `ignored` = el evento no trae el doc (no se sabe que objeto borrar). */
export type OnDeletedOutcome = "cleaned" | "ignored";

/**
 * Baja `recordingCount` en uno, con suelo en 0, SOLO si el usuario existe (una cuenta en cascada no se recrea) y el
 * contador es un entero > 0 (ausente, 0 o corrupto no se tocan: la reconciliacion de la purga lo repara). Devuelve si bajo.
 */
async function decrementCount(db: Firestore, uid: string): Promise<boolean> {
  const ref = db.collection(COLLECTIONS.users).doc(uid);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const c = snap.data()?.recordingCount;
    if (!snap.exists || typeof c !== "number" || !Number.isInteger(c) || c <= 0) return false;
    tx.update(ref, { recordingCount: c - 1 });
    return true;
  });
}

/**
 * `onDocumentDeleted(users/{uid}/recordings/{r})` (A12, A21; REQ-REC-07/14, REQ-RVW-05). Limpia lo que el doc deja atras:
 * el objeto (por la `objectPath` + `generation` GUARDADAS, 404/412 = OK), las `reviews` y, si era `ready`, una plaza del cupo.
 *
 * Ventana de resurreccion (W5): un `finalizeRecording` reintentado puede recrear el doc entre su borrado y el del objeto.
 * Tras borrar el objeto se relee el doc: si existe con la MISMA `objectPath` + `generation`, se borra otra vez (el trigger de
 * ESE borrado devuelve la plaza que el finalize recreado subio; una generation distinta es una subida legitima y no se toca).
 * El contador baja AL FINAL: un fallo anterior se reintenta entero sin bajarlo dos veces. Entrega al menos una vez: un
 * disparo duplicado puede bajarlo una vez de mas (nunca por debajo de 0); lo repara la reconciliacion (`purgeRecordings`).
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
  const own = await removeStoredObject(deps.store, event.uid, data);

  if (own) {
    const same = (d: DocumentData) => d.objectPath === data.objectPath && String(d.generation) === String(data.generation);
    await deleteRecording(deps, ref, { guard: same });
  }
  await deleteReviews(deps.db, ref);

  const counted = data.status === "ready" ? await decrementCount(deps.db, event.uid) : false;
  const dir = typeof data.objectPath === "string" ? parseObjectPath(data.objectPath)?.dir : undefined;
  log("onRecordingDeleted", { outcome: "cleaned", counted, ...(own ? {} : { anomaly: "object_path" }), ...(dir ? { dir } : {}), uidHash });
  return "cleaned";
}
