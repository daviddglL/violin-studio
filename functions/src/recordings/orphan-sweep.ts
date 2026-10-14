import { Bucket } from "@google-cloud/storage";
import { Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { DEMOS_DIR, RECORDINGS_DIR } from "../config/recordings";
import { bucketStore, ObjectStore } from "./finalize";
import { parseObjectPath } from "./validate";

export interface ObjectListing {
  name: string;
  generation: string;
  timeCreated: Date;
}

/** `ObjectStore` + listado paginado (los tests y el barrido no dependen del SDK de Storage). */
export interface SweepStore extends ObjectStore {
  /** Subprefijos de primer nivel bajo `prefix` (delimitador `/`), p. ej. `users/{uid}/`. */
  listPrefixes(prefix: string, pageToken?: string, pageSize?: number): Promise<{ prefixes: string[]; next?: string }>;
  /** Objetos bajo `prefix` (a cualquier profundidad). */
  listObjects(prefix: string, pageToken?: string, pageSize?: number): Promise<{ objects: ObjectListing[]; next?: string }>;
}

export function bucketSweepStore(bucket: Bucket): SweepStore {
  return {
    ...bucketStore(bucket),
    async listPrefixes(prefix, pageToken, pageSize) {
      const [, next, resp] = await bucket.getFiles({ prefix, delimiter: "/", autoPaginate: false, maxResults: pageSize, pageToken });
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

/** Un objeto sin doc que lo apunte solo se barre pasada esta antiguedad: no compite con un finalize en vuelo ni con reintentos. */
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
  /** Solo tests: entre el listado y la comprobacion del doc, para simular carreras. */
  beforeDelete?: () => Promise<void>;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
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
 * `teacherReads` de `storage.rules` NO exige doc: un huerfano de `recordings/` es legible por el profesor vinculado hasta que
 * pasa este barrido (o la regla de ciclo de vida a 95 d). Este barrido es la cota. Un fallo en un objeto nunca detiene el
 * resto; se registra `{category, uidHash, code}` (jamas uid, id ni ruta).
 */
export async function sweepOrphansHandler(deps: SweepDeps, now: Date = new Date()): Promise<SweepSummary> {
  const { db, store } = deps;
  const pageSize = deps.pageSize ?? DEFAULT_PAGE_SIZE;
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const clockMs = deps.nowMs ?? Date.now;
  const deadline = clockMs() + (deps.budgetMs ?? DEFAULT_BUDGET_MS);
  const summary: SweepSummary = { scanned: 0, deleted: 0, kept: 0, tooNew: 0, failed: 0, budgetExhausted: false, failedCategories: [] };
  const outOfTime = () => {
    if (clockMs() >= deadline) summary.budgetExhausted = true;
    return summary.budgetExhausted;
  };

  /** `false` = parar (presupuesto agotado). */
  async function examine(uid: string, o: ObjectListing): Promise<boolean> {
    if (outOfTime()) return false;
    summary.scanned++;
    if (now.getTime() - o.timeCreated.getTime() < ORPHAN_MIN_AGE_MS) {
      summary.tooNew++;
      return true;
    }
    try {
      await deps.beforeDelete?.();
      const path = parseObjectPath(o.name);
      if (path && path.uid === uid) {
        const doc = (await db.collection(COLLECTIONS.users).doc(uid).collection(COLLECTIONS.recordings).doc(path.id).get()).data();
        if (doc?.status === "ready" && doc.objectPath === o.name && String(doc.generation) === o.generation) {
          summary.kept++;
          return true;
        }
      }
      await store.remove(o.name, o.generation);
      summary.deleted++;
    } catch (e) {
      summary.failed++;
      log("sweepOrphans.objectFailed", { category: "orphan", uidHash: sha256Hex(uid).slice(0, 12), code: safeErrorCode(e) });
    }
    return true;
  }

  try {
    let userToken: string | undefined;
    sweep: do {
      const { prefixes, next } = await store.listPrefixes("users/", userToken, pageSize);
      userToken = next;
      for (const prefix of prefixes) {
        const uid = prefix.slice("users/".length).replace(/\/$/, "");
        if (!uid || uid.includes("/")) continue;
        for (const dir of [RECORDINGS_DIR, DEMOS_DIR]) {
          let token: string | undefined;
          do {
            if (outOfTime()) break sweep;
            const page = await store.listObjects(`users/${uid}/${dir}/`, token, pageSize);
            token = page.next;
            for (const o of page.objects) if (!(await examine(uid, o))) break sweep;
          } while (token);
        }
      }
    } while (userToken);
  } catch (e) {
    summary.failedCategories.push("list");
    log("sweepOrphans.stepFailed", { category: "list", code: safeErrorCode(e) });
  }

  log("sweepOrphans", { scanned: summary.scanned, deleted: summary.deleted, kept: summary.kept, tooNew: summary.tooNew, failed: summary.failed, budgetExhausted: summary.budgetExhausted });
  return summary;
}
