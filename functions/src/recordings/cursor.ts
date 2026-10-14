import { Firestore, Timestamp } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";

/**
 * Cursores de reanudacion de los trabajos diarios (W1 de la revision fresca de B5a) en `maintenance/{trabajo}` (solo
 * servidor). Guardan un PREFIJO TRUNCADO del uid donde se corto la pasada, nunca el uid completo: la siguiente pasada
 * empieza ahi (repite como mucho los uids que comparten prefijo, todo idempotente) y una vuelta completa lo deja en ""
 * (vuelve a la cabeza). Asi la cola de un listado mayor que el presupuesto no se queda sin procesar.
 */
export type CursorJob = "sweepRecordingOrphans" | "recordingReconcile";

/** 8 caracteres de un uid aleatorio de 28: no identifica por si solo y avanza siempre (no hay miles de uids por prefijo). */
export const CURSOR_KEY_LEN = 8;

export const cursorKey = (uid: string): string => uid.slice(0, CURSOR_KEY_LEN);

export async function readCursor(db: Firestore, job: CursorJob): Promise<string> {
  const c = (await db.collection(COLLECTIONS.maintenance).doc(job).get()).data()?.cursor;
  return typeof c === "string" ? c : "";
}

export async function writeCursor(db: Firestore, job: CursorJob, cursor: string): Promise<void> {
  await db.collection(COLLECTIONS.maintenance).doc(job).set({ cursor, updatedAt: Timestamp.now() });
}
