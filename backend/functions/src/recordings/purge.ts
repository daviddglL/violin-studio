import { DocumentSnapshot, FieldPath, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { cursorKey, readCursor, writeCursor } from "./cursor";
import { deleteRecording } from "./delete-recording";
import type { SweepStore } from "./orphan-sweep";
import { ReconcileOutcome, reconcileAllOwners } from "./recount";
import { purgeRevokedRecordings } from "./revoke-purge";

/** Se dejan de EMPEZAR borrados a los 480 s de los 540 s de timeout de la funcion; lo pendiente sigue al dia siguiente. */
export const DEFAULT_BUDGET_MS = 480_000;
const DEFAULT_PAGE_SIZE = 100;
/** W4: una revocacion con `recordingPurge.since` mas antigua que esto y aun incompleta se registra como atascada (ERROR). */
export const STUCK_PURGE_AFTER_MS = 3 * 24 * 60 * 60 * 1000;

export interface PurgeRecordingsDeps {
  db: Firestore;
  /** `ObjectStore` + listado: el paso 2 barre tambien los objetos huerfanos del usuario revocado. */
  store: SweepStore;
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
  /** Errores accionables (purga de revocacion atascada); por defecto `logger.error`. */
  error?: (message: string, data: Record<string, unknown>) => void;
}

export interface PurgeRecordingsSummary {
  expired: { found: number; deleted: number; skipped: number; failed: number };
  /** Paso 2: usuarios con `recordingPurge.pending` (`gone` = en borrado de cuenta: lo completa la cascada). */
  revocations: { found: number; completed: number; incomplete: number; gone: number; failed: number };
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
 * Orden (W5 de la revision fresca B5b): (2) -> (1) -> (4). Las revocaciones van primero para que un atasco de caducadas que
 * agote el presupuesto no las deje sin hacer; lo que no quepa sigue al dia siguiente.
 * (1) docs con `expiresAt <= now` (collection group, con cursor) -> `deleteRecording` (objeto por su `objectPath`, doc,
 *     reviews). El contador lo baja `onRecordingDeleted`. Se re-comprueba la caducidad sobre el doc fresco antes de borrar.
 * (4) reconcilia `recordingCount` con el recuento real de docs `ready`, EXCEPTO a los duenos a los que el paso 1 acaba de
 *     borrar docs (su trigger puede no haber corrido todavia; se hace al dia siguiente). Empieza en el cursor guardado en
 *     `maintenance/recordingReconcile` y, si el presupuesto se acaba, guarda donde se corto (W1): la cola no se queda sin
 *     reconciliar aunque la cabeza agote el presupuesto todos los dias. Presupuesto agotado -> aviso `warn`.
 * (2) completa las revocaciones de `recording_sharing` pendientes (`users where recordingPurge.pending == true`, B5b.3) con
 *     `purgeRevokedRecordings` (acotado por epoca, huerfanos incluidos; baja `pending` solo si epoca y `since` siguen siendo
 *     los leidos). Reanudable (cursor `maintenance/recordingPurgePending`, prefijo de uid): si el presupuesto se acaba la
 *     siguiente pasada sigue ahi y, al terminar la cola, da la vuelta a la cabeza. Los duenos a los que borra docs no se
 *     reconcilian ese dia (como en el paso 1). Una purga que sigue incompleta con `since` de hace mas de
 *     `STUCK_PURGE_AFTER_MS` se registra `revokePurge.stuck` a nivel ERROR (W4; runbook en README, `admin:complete-recording-purge`).
 * El paso 3 (`blurRequired` al cumplir 18) NO es de este slice (C2).
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
    revocations: { found: 0, completed: 0, incomplete: 0, gone: 0, failed: 0 },
    reconcile: { scanned: 0, repaired: 0, failed: 0 },
    budgetExhausted: false,
    failedCategories: [],
  };
  const touchedOwners = new Set<string>();
  const outOfTime = () => {
    if (clockMs() >= deadline) summary.budgetExhausted = true;
    return summary.budgetExhausted;
  };
  const error = deps.error ?? ((m: string, d: Record<string, unknown>) => logger.error(m, d));
  /** W4: revocacion pendiente desde hace mas de `STUCK_PURGE_AFTER_MS` que hoy tampoco se completo -> ERROR (alerta; runbook README). */
  const reportStuck = (uid: string, u: Record<string, unknown>) => {
    const since = (u.recordingPurge as { since?: unknown } | undefined)?.since;
    if (!(since instanceof Timestamp)) return;
    const age = nowMs - since.toMillis();
    if (age > STUCK_PURGE_AFTER_MS) error("revokePurge.stuck", { uidHash: sha256Hex(uid).slice(0, 12), ageDays: Math.floor(age / 86_400_000) });
  };
  const failStep = (name: string, e: unknown) => {
    summary.failedCategories.push(name);
    log("purgeRecordings.stepFailed", { category: name, code: safeErrorCode(e) });
  };

  // (2) revocaciones pendientes, PRIMERO (W5): un atasco de caducadas no puede dejarlas sin hacer dia tras dia. El presupuesto
  // se comprueba por usuario (sin gastar una lectura del reloj si no hay ninguno).
  try {
    let current: string | undefined;
    /** Recorre los pendientes con id en [from, until). `false` = parar (presupuesto): `current` es donde se corto. */
    const scan = async (from: string, until?: string): Promise<boolean> => {
      let base = db.collection(COLLECTIONS.users).where("recordingPurge.pending", "==", true);
      if (from !== "") base = base.where(FieldPath.documentId(), ">=", from); // un id vacio no es un id valido
      if (until !== undefined) base = base.where(FieldPath.documentId(), "<", until);
      const query = base.orderBy(FieldPath.documentId());
      let cursor: DocumentSnapshot | undefined;
      for (;;) {
        const snap = await (cursor ? query.startAfter(cursor) : query).limit(pageSize).get();
        for (const d of snap.docs) {
          if (outOfTime()) {
            current = d.id;
            return false;
          }
          summary.revocations.found++;
          try {
            const r = await purgeRevokedRecordings({ db, store, pageSize, shouldStop: outOfTime, log }, d.id);
            if (r.deleted > 0) touchedOwners.add(d.id);
            if (r.outcome === "completed" || r.outcome === "none") summary.revocations.completed++;
            else if (r.outcome === "gone") summary.revocations.gone++;
            else {
              summary.revocations.incomplete++;
              reportStuck(d.id, d.data());
              if (summary.budgetExhausted) {
                current = d.id;
                return false;
              }
            }
          } catch (e) {
            summary.revocations.failed++;
            log("purgeRecordings.recordingFailed", { category: "revocation", uidHash: sha256Hex(d.id).slice(0, 12), code: safeErrorCode(e) });
            reportStuck(d.id, d.data());
          }
        }
        if (snap.size < pageSize) return true;
        cursor = snap.docs[snap.size - 1];
      }
    };
    const startKey = await readCursor(db, "recordingPurgePending");
    let completed = await scan(startKey);
    if (completed && startKey !== "") completed = await scan("", startKey);
    await writeCursor(db, "recordingPurgePending", completed ? "" : current !== undefined ? cursorKey(current) : startKey);
  } catch (e) {
    failStep("revocations", e);
  }

  // (1) caducadas. Cursor por snapshot: borrar los docs ya vistos no desplaza la pagina siguiente.
  if (!summary.budgetExhausted) try {
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
  log("purgeRecordings", {
    ...summary.expired,
    revocations: summary.revocations,
    repaired: summary.reconcile.repaired,
    budgetExhausted: summary.budgetExhausted,
  });
  return summary;
}
