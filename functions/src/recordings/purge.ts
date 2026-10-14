import { DocumentSnapshot, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { readCursor, writeCursor } from "./cursor";
import { deleteRecording } from "./delete-recording";
import type { ObjectStore } from "./finalize";
import { ReconcileOutcome, reconcileAllOwners } from "./recount";

/** Se dejan de EMPEZAR borrados a los 480 s de los 540 s de timeout de la funcion; lo pendiente sigue al dia siguiente. */
export const DEFAULT_BUDGET_MS = 480_000;
const DEFAULT_PAGE_SIZE = 100;

export interface PurgeRecordingsDeps {
  db: Firestore;
  store: ObjectStore;
  pageSize?: number;
  /** Presupuesto de tiempo para empezar trabajo (ms). */
  budgetMs?: number;
  /** Reloj en ms del presupuesto (inyectable en tests). */
  nowMs?: () => number;
  /** Paso 4 (reconciliacion de `recordingCount`); `false` solo en tests que aislan el paso 1. */
  reconcile?: boolean;
  /** Solo tests: reconciliacion de un dueno inyectable (fallos). */
  reconcileOwner?: (db: Firestore, uid: string) => Promise<ReconcileOutcome>;
  /** Solo tests: entre la consulta y el borrado, para simular carreras. */
  beforeDelete?: () => Promise<void>;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
  /** Avisos (presupuesto agotado); por defecto `logger.warn`. */
  warn?: (message: string, data: Record<string, unknown>) => void;
}

export interface PurgeRecordingsSummary {
  expired: { found: number; deleted: number; skipped: number; failed: number };
  reconcile: { scanned: number; repaired: number; failed: number };
  budgetExhausted: boolean;
  /** Pasos cuya consulta fallo entera (indice ausente, backend caido). */
  failedCategories: string[];
}

const millis = (v: unknown): number => (v instanceof Timestamp ? v.toMillis() : Number.POSITIVE_INFINITY);

/**
 * Purga diaria de grabaciones (REQ-REC-08, REQ-REC-14), 03:30 Europe/Madrid. Un fallo en una grabacion nunca detiene el
 * lote: se registra `{category, uidHash, code}` (jamas uid, id, ruta ni el mensaje) y se reintenta al dia siguiente,
 * porque lo que no se borra sigue cumpliendo la misma condicion.
 *
 * (1) docs con `expiresAt <= now` (collection group, con cursor) -> `deleteRecording` (objeto por su `objectPath`, doc,
 *     reviews). El contador lo baja `onRecordingDeleted`. Se re-comprueba la caducidad sobre el doc fresco antes de borrar.
 * (4) reconcilia `recordingCount` con el recuento real de docs `ready`, EXCEPTO a los duenos a los que el paso 1 acaba de
 *     borrar docs (su trigger puede no haber corrido todavia; se hace al dia siguiente). Empieza en el cursor guardado en
 *     `maintenance/recordingReconcile` y, si el presupuesto se acaba, guarda donde se corto (W1): la cola no se queda sin
 *     reconciliar aunque la cabeza agote el presupuesto todos los dias. Presupuesto agotado -> aviso `warn`.
 * Los pasos 2 (revocacion pendiente) y 3 (`blurRequired` al cumplir 18) NO son de este slice (B5b y C2).
 */
export async function purgeRecordingsHandler(deps: PurgeRecordingsDeps, now: Date = new Date()): Promise<PurgeRecordingsSummary> {
  const { db, store } = deps;
  const pageSize = deps.pageSize ?? DEFAULT_PAGE_SIZE;
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const warn = deps.warn ?? ((m: string, d: Record<string, unknown>) => logger.warn(m, d));
  const clockMs = deps.nowMs ?? Date.now;
  const deadline = clockMs() + (deps.budgetMs ?? DEFAULT_BUDGET_MS);
  const nowMs = now.getTime();
  const summary: PurgeRecordingsSummary = {
    expired: { found: 0, deleted: 0, skipped: 0, failed: 0 },
    reconcile: { scanned: 0, repaired: 0, failed: 0 },
    budgetExhausted: false,
    failedCategories: [],
  };
  const touchedOwners = new Set<string>();
  const outOfTime = () => {
    if (clockMs() >= deadline) summary.budgetExhausted = true;
    return summary.budgetExhausted;
  };
  const failStep = (name: string, e: unknown) => {
    summary.failedCategories.push(name);
    log("purgeRecordings.stepFailed", { category: name, code: safeErrorCode(e) });
  };

  // (1) caducadas. Cursor por snapshot: borrar los docs ya vistos no desplaza la pagina siguiente.
  try {
    const query = db.collectionGroup(COLLECTIONS.recordings).where("expiresAt", "<=", Timestamp.fromMillis(nowMs));
    let cursor: DocumentSnapshot | undefined;
    paging: for (;;) {
      const snap = await (cursor ? query.startAfter(cursor) : query).limit(pageSize).get();
      for (const d of snap.docs) {
        if (outOfTime()) break paging;
        summary.expired.found++;
        const owner = d.ref.parent.parent?.id ?? "";
        try {
          await deps.beforeDelete?.();
          const out = await deleteRecording({ db, store, log }, d.ref, { guard: (data) => millis(data.expiresAt) <= nowMs });
          if (out === "skipped") summary.expired.skipped++;
          else {
            summary.expired.deleted++;
            touchedOwners.add(owner);
          }
        } catch (e) {
          summary.expired.failed++;
          log("purgeRecordings.recordingFailed", { category: "expired", uidHash: sha256Hex(owner).slice(0, 12), code: safeErrorCode(e) });
        }
      }
      if (snap.size < pageSize) break;
      cursor = snap.docs[snap.size - 1];
    }
  } catch (e) {
    failStep("expired", e);
  }

  // (4) reconciliacion del contador.
  if (deps.reconcile !== false && !outOfTime()) {
    try {
      const resumeFrom = await readCursor(db, "recordingReconcile");
      const r = await reconcileAllOwners(db, {
        pageSize,
        resumeFrom,
        shouldStop: outOfTime,
        skip: touchedOwners,
        reconcile: deps.reconcileOwner,
        onFailure: (uid, code) => log("purgeRecordings.recordingFailed", { category: "reconcile", uidHash: sha256Hex(uid).slice(0, 12), code }),
      });
      summary.reconcile = { scanned: r.scanned, repaired: r.repaired, failed: r.failed };
      await writeCursor(db, "recordingReconcile", r.next);
    } catch (e) {
      failStep("reconcile", e);
    }
  }

  if (summary.budgetExhausted) warn("purgeRecordings.budgetExhausted", { found: summary.expired.found, scanned: summary.reconcile.scanned });
  log("purgeRecordings", { ...summary.expired, repaired: summary.reconcile.repaired, budgetExhausted: summary.budgetExhausted });
  return summary;
}
