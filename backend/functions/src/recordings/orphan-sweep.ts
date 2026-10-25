import { Bucket } from "@google-cloud/storage";
import { DocumentData, DocumentReference, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { DEMOS_DIR, RECORDINGS_DIR } from "../config/recordings";
import { cursorKey, readCursor, writeCursor } from "./cursor";
import { bucketStore, ObjectStore } from "./finalize";
import { parseObjectPath } from "./validate";

export interface ObjectListing {
  name: string;
  generation: string;
  timeCreated: Date;
}

/** `ObjectStore` + listado paginado (los tests y el barrido no dependen del SDK de Storage). */
export interface SweepStore extends ObjectStore {
  /**
   * Subprefijos de primer nivel bajo `prefix` (delimitador `/`), p. ej. `users/{uid}/`. `startOffset` (opcional) empieza
   * el listado en ese nombre (orden lexicografico); el llamador vuelve a filtrar por si el backend lo ignorara.
   */
  listPrefixes(prefix: string, pageToken?: string, pageSize?: number, startOffset?: string): Promise<{ prefixes: string[]; next?: string }>;
  /** Objetos bajo `prefix` (a cualquier profundidad). */
  listObjects(prefix: string, pageToken?: string, pageSize?: number): Promise<{ objects: ObjectListing[]; next?: string }>;
}

export function bucketSweepStore(bucket: Bucket): SweepStore {
  return {
    ...bucketStore(bucket),
    async listPrefixes(prefix, pageToken, pageSize, startOffset) {
      const [, next, resp] = await bucket.getFiles({
        prefix,
        delimiter: "/",
        autoPaginate: false,
        maxResults: pageSize,
        pageToken,
        ...(startOffset ? { startOffset } : {}),
      });
      const prefixes = (resp as { prefixes?: string[] } | undefined)?.prefixes ?? [];
      return { prefixes, next: (next as { pageToken?: string } | null)?.pageToken };
    },
    async listObjects(prefix, pageToken, pageSize) {
      const [files, next] = await bucket.getFiles({ prefix, autoPaginate: false, maxResults: pageSize, pageToken });
      return {
        objects: files.map((f) => ({
          name: f.name,
          generation: String(f.metadata.generation),
          timeCreated: new Date(String(f.metadata.timeCreated)),
        })),
        next: (next as { pageToken?: string } | null)?.pageToken,
      };
    },
  };
}

/**
 * Un objeto sin doc que lo apunte solo se barre pasada esta antiguedad: no compite con un finalize en vuelo ni con reintentos.
 * `finalizeRecording` nunca escribe `ready` para un objeto de `READY_MAX_OBJECT_AGE_MS` (20 h) o mas (W3): a partir de
 * aqui ningun finalize tardio puede crear un doc `ready` para el objeto que el barrido va a borrar.
 */
export const ORPHAN_MIN_AGE_MS = 24 * 60 * 60 * 1000;
/** Se dejan de EMPEZAR borrados a los 480 s de los 540 s de timeout de la funcion; lo pendiente sigue al dia siguiente. */
export const DEFAULT_BUDGET_MS = 480_000;
const DEFAULT_PAGE_SIZE = 100;

export interface SweepDeps {
  db: Firestore;
  store: SweepStore;
  pageSize?: number;
  /** Presupuesto de tiempo para empezar trabajo (ms). */
  budgetMs?: number;
  /** Reloj en ms del presupuesto (inyectable en tests). */
  nowMs?: () => number;
  /** Solo tests: entre el listado de una pagina y la lectura de sus docs, para simular carreras. */
  beforeDelete?: () => Promise<void>;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
  /** Avisos (presupuesto agotado); por defecto `logger.warn`. */
  warn?: (message: string, data: Record<string, unknown>) => void;
}

export interface SweepSummary {
  /** Objetos bajo los dos prefijos que se han examinado. */
  scanned: number;
  deleted: number;
  /** Con doc `ready` que los apunta (objectPath + generation). */
  kept: number;
  /** Aun no cumplen la antiguedad minima. */
  tooNew: number;
  failed: number;
  budgetExhausted: boolean;
  /** `list` = el listado de Storage fallo (se reintenta mañana). */
  failedCategories: string[];
}

/**
 * Barrido diario de objetos huerfanos (W4 de la revision fresca de B4b; REQ-REC-07/08). Recorre `users/{uid}/recordings/` y
 * `users/{uid}/demos/` (un listado con delimitador da los uids; no se lista nada fuera de esos dos prefijos) y borra, solo
 * la generation listada (`ifGenerationMatch`: un cliente que resube el nombre conserva lo nuevo), todo objeto con
 * `timeCreated` de mas de 24 h que NO apunte un doc `ready` de `users/{uid}/recordings/{id}` con el mismo `objectPath` y
 * `generation`. Cubre: finalize abandonado por envenenamiento con ruta sin id, `dropped` cuyo borrado fallo, generations
 * sobrescritas, objetos de docs `rejected` (su borrado fallo) y rutas invalidas dentro de los prefijos.
 *
 * Reanudable (W1 de la revision fresca de B5a): empieza en el cursor de `maintenance/sweepRecordingOrphans` (prefijo
 * truncado del uid donde se corto la pasada anterior) y lo actualiza al terminar: si el presupuesto se acaba, la siguiente
 * pasada sigue ahi; si la vuelta se completa, vuelve a "". Los docs de cada pagina se leen con UN `getAll`. Presupuesto
 * agotado -> aviso `warn`.
 *
 * `teacherReads` de `storage.rules` NO exige doc: un huerfano de `recordings/` es legible por el profesor vinculado hasta que
 * pasa este barrido (o la regla de ciclo de vida a 100 d). Este barrido es la cota. Un fallo en un objeto nunca detiene el
 * resto; se registra `{category, uidHash, code}` (jamas uid, id ni ruta).
 */
export async function sweepOrphansHandler(deps: SweepDeps, now: Date = new Date()): Promise<SweepSummary> {
  const { db, store } = deps;
  const pageSize = deps.pageSize ?? DEFAULT_PAGE_SIZE;
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const warn = deps.warn ?? ((m: string, d: Record<string, unknown>) => logger.warn(m, d));
  const clockMs = deps.nowMs ?? Date.now;
  const deadline = clockMs() + (deps.budgetMs ?? DEFAULT_BUDGET_MS);
  const summary: SweepSummary = { scanned: 0, deleted: 0, kept: 0, tooNew: 0, failed: 0, budgetExhausted: false, failedCategories: [] };
  const outOfTime = () => {
    if (clockMs() >= deadline) summary.budgetExhausted = true;
    return summary.budgetExhausted;
  };
  const objectFailed = (uid: string, e: unknown) => {
    summary.failed++;
    log("sweepOrphans.objectFailed", { category: "orphan", uidHash: sha256Hex(uid).slice(0, 12), code: safeErrorCode(e) });
  };

  /** Examina una pagina de objetos de `uid`: una sola lectura por lotes de los docs candidatos. `false` = parar. */
  async function examinePage(uid: string, objects: ObjectListing[]): Promise<boolean> {
    const old = objects.filter((o) => now.getTime() - o.timeCreated.getTime() >= ORPHAN_MIN_AGE_MS);
    const refs = new Map<string, DocumentReference>();
    for (const o of old) {
      const p = parseObjectPath(o.name);
      if (p && p.uid === uid && !refs.has(p.id)) {
        refs.set(p.id, db.collection(COLLECTIONS.users).doc(uid).collection(COLLECTIONS.recordings).doc(p.id));
      }
    }
    let docs: Map<string, DocumentData | undefined> | null = new Map();
    if (old.length > 0) {
      try {
        await deps.beforeDelete?.();
        const snaps = refs.size > 0 ? await db.getAll(...refs.values()) : [];
        for (const s of snaps) docs.set(s.id, s.data());
      } catch (e) {
        docs = null; // sin los docs no se puede decidir nada de esta pagina: cada candidato cuenta como fallo
        log("sweepOrphans.objectFailed", { category: "orphan_docs", uidHash: sha256Hex(uid).slice(0, 12), code: safeErrorCode(e) });
      }
    }

    for (const o of objects) {
      if (outOfTime()) return false;
      summary.scanned++;
      if (now.getTime() - o.timeCreated.getTime() < ORPHAN_MIN_AGE_MS) {
        summary.tooNew++;
        continue;
      }
      if (docs === null) {
        summary.failed++;
        continue;
      }
      const p = parseObjectPath(o.name);
      const doc = p && p.uid === uid ? docs.get(p.id) : undefined;
      if (doc?.status === "ready" && doc.objectPath === o.name && String(doc.generation) === o.generation) {
        summary.kept++;
        continue;
      }
      try {
        await store.remove(o.name, o.generation);
        summary.deleted++;
      } catch (e) {
        objectFailed(uid, e);
      }
    }
    return true;
  }

  let startKey = "";
  try {
    startKey = await readCursor(db, "sweepRecordingOrphans");
  } catch (e) {
    log("sweepOrphans.stepFailed", { category: "cursor", code: safeErrorCode(e) });
  }
  /** Uid en curso: si la pasada se corta, la siguiente empieza en su prefijo (lo ya hecho de el se repite, idempotente). */
  let current: string | undefined;
  let completed = false;
  try {
    let userToken: string | undefined;
    sweep: do {
      const { prefixes, next } = await store.listPrefixes("users/", userToken, pageSize, startKey ? `users/${startKey}` : undefined);
      userToken = next;
      for (const prefix of prefixes) {
        const uid = prefix.slice("users/".length).replace(/\/$/, "");
        if (!uid || uid.includes("/") || uid < startKey) continue;
        current = uid;
        for (const dir of [RECORDINGS_DIR, DEMOS_DIR]) {
          let token: string | undefined;
          do {
            if (outOfTime()) break sweep;
            const page = await store.listObjects(`users/${uid}/${dir}/`, token, pageSize);
            token = page.next;
            if (!(await examinePage(uid, page.objects))) break sweep;
          } while (token);
        }
      }
    } while (userToken);
    completed = !summary.budgetExhausted;
  } catch (e) {
    summary.failedCategories.push("list");
    log("sweepOrphans.stepFailed", { category: "list", code: safeErrorCode(e) });
  }

  const nextKey = completed ? "" : current !== undefined ? cursorKey(current) : startKey;
  try {
    await writeCursor(db, "sweepRecordingOrphans", nextKey);
  } catch (e) {
    log("sweepOrphans.stepFailed", { category: "cursor", code: safeErrorCode(e) });
  }

  const totals = { scanned: summary.scanned, deleted: summary.deleted, kept: summary.kept, tooNew: summary.tooNew, failed: summary.failed, budgetExhausted: summary.budgetExhausted };
  if (summary.budgetExhausted) warn("sweepOrphans.budgetExhausted", totals);
  log("sweepOrphans", totals);
  return summary;
}
