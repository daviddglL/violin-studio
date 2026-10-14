import { DocumentData, DocumentReference, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { sha256Hex } from "../common/hashing";
import type { ObjectStore } from "./finalize";
import { parseObjectPath } from "./validate";

export interface DeleteRecordingDeps {
  db: Firestore;
  store: ObjectStore;
  /** Sumidero de logs sin PII (solo `uidHash`); por defecto `logger.info`. Solo se usa ante anomalias. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface DeleteRecordingOpts {
  /** Se evalua con el doc leido; `false` -> `skipped` sin tocar nada (p. ej. la purga re-comprueba la caducidad). */
  guard?: (data: DocumentData) => boolean;
  /** Solo tests: entre la lectura del doc y el borrado, para simular carreras. */
  beforeDelete?: () => Promise<void>;
}

/**
 * - `deleted`: objeto y doc borrados (y la cascada de `reviews`).
 * - `absent`: el doc ya no existia (idempotencia): solo se completa la cascada de `reviews` por si un intento anterior cayo.
 * - `skipped`: el `guard` dijo que no, o el doc cambio entre la lectura y el borrado (el doc nuevo manda).
 */
export type DeleteRecordingOutcome = "deleted" | "absent" | "skipped";

const FAILED_PRECONDITION = 9;

/**
 * Ruta del objeto a borrar para ese doc: SIEMPRE la `objectPath` almacenada (una demo vive en `demos/` aunque su doc este
 * en `recordings/`), nunca una reconstruida desde el id. Solo se acepta si cuelga del mismo `users/{uid}` que el doc y
 * dentro de los dos prefijos: un doc con una ruta ajena nunca hace borrar un objeto ajeno.
 */
export function storedObjectPath(ownerUid: string, data: DocumentData | undefined): string | null {
  const p = typeof data?.objectPath === "string" ? parseObjectPath(data.objectPath) : null;
  return p && p.uid === ownerUid ? (data!.objectPath as string) : null;
}

/**
 * Borrado coherente de una grabacion (REQ-REC-07/08): primero el objeto (solo la generation del doc; 404/412 = OK) y
 * DESPUES el doc con su cascada de `reviews`. Si el objeto falla se relanza y el doc sigue ahi: reintentar lo completa.
 * `recordingCount` NO se toca aqui: lo baja `onRecordingDeleted` al borrarse un doc `ready` (un solo sitio, A21).
 */
export async function deleteRecording(
  deps: DeleteRecordingDeps,
  ref: DocumentReference,
  opts: DeleteRecordingOpts = {},
): Promise<DeleteRecordingOutcome> {
  const snap = await ref.get();
  if (!snap.exists) {
    await deps.db.recursiveDelete(ref);
    return "absent";
  }
  const data = snap.data()!;
  if (opts.guard && !opts.guard(data)) return "skipped";

  const ownerUid = ref.parent.parent?.id ?? "";
  const objectPath = storedObjectPath(ownerUid, data);
  if (objectPath) {
    // Sin generation (no deberia: finalize siempre la guarda) se usa la viva, para poder precondicionar igualmente.
    const generation = data.generation !== undefined ? String(data.generation) : (await deps.store.stat(objectPath))?.generation;
    if (generation !== undefined) await deps.store.remove(objectPath, generation);
  } else {
    const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
    log("deleteRecording", { anomaly: "object_path", uidHash: sha256Hex(ownerUid).slice(0, 12) });
  }

  await opts.beforeDelete?.();
  try {
    await ref.delete({ lastUpdateTime: snap.updateTime });
  } catch (e) {
    if ((e as { code?: unknown } | null)?.code === FAILED_PRECONDITION) return "skipped";
    throw e;
  }
  await deps.db.recursiveDelete(ref);
  return "deleted";
}
