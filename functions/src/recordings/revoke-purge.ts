import { DocumentData, DocumentReference, DocumentSnapshot, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { DEMOS_DIR, RECORDINGS_DIR } from "../config/recordings";
import { deleteRecording } from "./delete-recording";
import type { SweepStore } from "./orphan-sweep";
import { parseObjectPath } from "./validate";

const DEFAULT_PAGE_SIZE = 100;
/** Rondas (releer la purga, borrar, intentar cerrar) antes de rendirse: cada revocacion nueva exige una ronda mas. */
const MAX_ROUNDS = 5;

export interface RevokePurgeDeps {
  db: Firestore;
  /** `ObjectStore` + listado: ademas de los objetos de cada doc se barren los huerfanos bajo los dos prefijos del usuario. */
  store: SweepStore;
  pageSize?: number;
  /** Se consulta antes de cada trabajo; `true` corta la purga (queda `pending`, la reanuda la purga diaria). */
  shouldStop?: () => boolean;
  /** Solo tests: entre los barridos y el intento de cerrar la purga, para simular revocaciones concurrentes. */
  beforeFinish?: () => Promise<void>;
  /** Sumidero de logs sin PII (solo `uidHash`, contadores y codigos); por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/**
 * - `none`: sin purga pendiente (o usuario ya con purga cerrada): no se toca nada.
 * - `completed`: todo borrado y `recordingPurge.pending` bajado.
 * - `incomplete`: quedan cosas (fallos, presupuesto agotado, revocaciones encadenadas); `pending` sigue y se reanuda.
 * - `gone`: el usuario ya no existe o esta en borrado: la cascada de borrado de cuenta es la duena de sus datos.
 */
export type RevokePurgeOutcome = "none" | "completed" | "incomplete" | "gone";

export interface RevokePurgeResult {
  outcome: RevokePurgeOutcome;
  /** Docs de grabacion borrados (con su objeto y revisiones). */
  deleted: number;
  /** Objetos huerfanos (sin doc vigente) borrados. */
  orphans: number;
  /** Docs u objetos que fallaron en la ultima ronda (se reintentan). */
  failed: number;
}

interface PurgeState {
  /** Epoca mayor cerrada aun sin purgar; una corrupta se lee como infinita (falla cerrado: se borra todo lo del usuario). */
  epoch: number;
  since: unknown;
}

const isDeleting = (u: DocumentData) => u.deletion !== undefined && u.deletion !== null;

function readPurge(u: DocumentData): PurgeState | null {
  const p = u.recordingPurge as { pending?: unknown; epoch?: unknown; since?: unknown } | undefined;
  if (typeof p !== "object" || p === null || p.pending !== true) return null;
  return { epoch: typeof p.epoch === "number" && Number.isInteger(p.epoch) ? p.epoch : Number.POSITIVE_INFINITY, since: p.since };
}

/** Epoca con la que finalize estampo el doc; ausente (p. ej. un `rejected`) = anterior a todo: se purga. */
const docEpoch = (d: DocumentData): number => (typeof d.recordingConsentEpoch === "number" ? d.recordingConsentEpoch : Number.NEGATIVE_INFINITY);

const sameSince = (a: unknown, b: unknown): boolean => (a instanceof Timestamp && b instanceof Timestamp ? a.isEqual(b) : a === b);

/**
 * Purga de una revocacion de `recording_sharing` (REQ-REC-09, REQ-REC-10, B5b.3), reanudable e idempotente. Parte de
 * `users/{uid}.recordingPurge {pending, since, epoch}` (lo escribe la revocacion de B1):
 *  1. borra con `deleteRecording` (objeto por la `objectPath` ALMACENADA, doc, revisiones; `recordingCount` lo baja
 *     `onRecordingDeleted`) cada doc de `users/{uid}/recordings` con `recordingConsentEpoch <= recordingPurge.epoch`
 *     (sin epoca = anterior a todo): las demos del profesor son docs suyos y caen igual. Una grabacion de una epoca
 *     posterior no se toca (la re-concesion esta bloqueada mientras `pending`, pero la cota no depende de ello);
 *  2. barre los objetos bajo `users/{uid}/recordings/` y `users/{uid}/demos/` que ningun doc vigente apunta (huerfanos,
 *     de cualquier antiguedad: sin consentimiento nadie sube y un finalize en vuelo se rechaza), solo la generation listada;
 *  3. baja `pending` en una transaccion SOLO si `epoch` y `since` siguen siendo los leidos al empezar la ronda; si hubo
 *     otra revocacion (epoca mayor, `since` nuevo), repite con la nueva (hasta `MAX_ROUNDS`).
 * Un fallo en un doc/objeto no detiene el resto: se cuenta, `pending` se queda y el reintento (callable, purga diaria)
 * lo completa con el mismo resultado final. Usuario inexistente o en borrado: `gone`, sin escribir (no se recrea `users/{uid}`).
 */
export async function purgeRevokedRecordings(deps: RevokePurgeDeps, uid: string): Promise<RevokePurgeResult> {
  const { db, store } = deps;
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const uidHash = sha256Hex(uid).slice(0, 12);
  const pageSize = deps.pageSize ?? DEFAULT_PAGE_SIZE;
  const userRef = db.collection(COLLECTIONS.users).doc(uid);
  const recordings = userRef.collection(COLLECTIONS.recordings);
  const result: RevokePurgeResult = { outcome: "none", deleted: 0, orphans: 0, failed: 0 };
  let stopped = false;
  const stop = () => (stopped ||= deps.shouldStop?.() === true);
  const fail = (category: string, e: unknown) => {
    result.failed++;
    log("revokePurge.itemFailed", { category, uidHash, code: safeErrorCode(e) });
  };
  const finish = (outcome: RevokePurgeOutcome): RevokePurgeResult => {
    result.outcome = outcome;
    log("revokePurge", { uidHash, ...result });
    return result;
  };

  for (let round = 0; round < MAX_ROUNDS; round++) {
    const user = (await userRef.get()).data();
    if (!user || isDeleting(user)) return finish("gone");
    const purge = readPurge(user);
    if (!purge) return finish(round === 0 ? "none" : "completed");
    result.failed = 0;

    // 1. docs de esta epoca o anteriores. Cursor por snapshot: borrar los ya vistos no desplaza la pagina siguiente.
    let cursor: DocumentSnapshot | undefined;
    for (;;) {
      const snap = await (cursor ? recordings.startAfter(cursor) : recordings).limit(pageSize).get();
      for (const d of snap.docs) {
        if (stop()) return finish("incomplete");
        if (docEpoch(d.data()) > purge.epoch) continue;
        try {
          const out = await deleteRecording({ db, store, log }, d.ref, { guard: (data) => docEpoch(data) <= purge.epoch });
          if (out === "deleted") result.deleted++;
        } catch (e) {
          fail("doc", e);
        }
      }
      if (snap.size < pageSize) break;
      cursor = snap.docs[snap.size - 1];
    }

    // 2. objetos huerfanos de los dos prefijos.
    for (const dir of [RECORDINGS_DIR, DEMOS_DIR]) {
      let token: string | undefined;
      do {
        if (stop()) return finish("incomplete");
        let page;
        try {
          page = await store.listObjects(`users/${uid}/${dir}/`, token, pageSize);
        } catch (e) {
          fail("list", e);
          break;
        }
        token = page.next;
        await sweepPage(page.objects);
      } while (token);
    }

    if (result.failed > 0 || stop()) return finish("incomplete");
    await deps.beforeFinish?.();

    // 3. cerrar solo si nadie volvio a revocar mientras tanto.
    const closed = await db.runTransaction(async (tx): Promise<"done" | "changed" | "gone"> => {
      const cur = (await tx.get(userRef)).data();
      if (!cur || isDeleting(cur)) return "gone";
      const now = readPurge(cur);
      if (!now) return "done"; // otra instancia (callable vs. purga diaria) ya la cerro
      if (now.epoch !== purge.epoch || !sameSince(now.since, purge.since)) return "changed";
      tx.update(userRef, { "recordingPurge.pending": false, "recordingPurge.completedAt": FieldValue.serverTimestamp() });
      return "done";
    });
    if (closed === "gone") return finish("gone");
    if (closed === "done") return finish("completed");
  }
  return finish("incomplete");

  /** Borra los objetos de la pagina que ningun doc vigente (`ready` con la misma `objectPath` + `generation`) apunta. */
  async function sweepPage(objects: { name: string; generation: string }[]): Promise<void> {
    const refs = new Map<string, DocumentReference>();
    for (const o of objects) {
      const p = parseObjectPath(o.name);
      if (p && p.uid === uid && !refs.has(p.id)) refs.set(p.id, recordings.doc(p.id));
    }
    const docs = new Map<string, DocumentData | undefined>();
    try {
      if (refs.size > 0) for (const s of await db.getAll(...refs.values())) docs.set(s.id, s.data());
    } catch (e) {
      fail("orphan_docs", e);
      return;
    }
    for (const o of objects) {
      if (stop()) return;
      const p = parseObjectPath(o.name);
      const doc = p && p.uid === uid ? docs.get(p.id) : undefined;
      if (doc && doc.objectPath === o.name && String(doc.generation) === o.generation) continue;
      try {
        await store.remove(o.name, o.generation);
        result.orphans++;
      } catch (e) {
        fail("orphan", e);
      }
    }
  }
}
