import { Auth } from "firebase-admin/auth";
import { DocumentSnapshot, Firestore, Query, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { PENDING_ACCOUNT_TTL_DAYS } from "../config/identity";

const MIN_MS = 60_000;
const DAY_MS = 24 * 3600_000;
/** Un borrado `in_progress` o un perfil sin Auth de menos de 1 h puede estar aun en curso normal. */
export const GRACE_MS = 60 * MIN_MS;
/** Se dejan de EMPEZAR borrados a los 480 s de los 540 s de timeout de la funcion; lo pendiente sigue al dia siguiente. */
export const DEFAULT_BUDGET_MS = 480_000;
const DEFAULT_PAGE_SIZE = 100;
const GET_USERS_MAX = 100;

export interface PurgeDeps {
  db: Firestore;
  auth: Pick<Auth, "getUsers" | "listUsers">;
  /** `eraseUserData` con las deps reales; inyectable para probar la purga sin cascada. */
  erase: (uid: string, opts: { deleteAuth: boolean }) => Promise<unknown>;
  /** Tamano de pagina de las consultas y de `listUsers`. */
  pageSize?: number;
  /** Presupuesto de tiempo para empezar borrados (ms). */
  budgetMs?: number;
  /** Reloj en ms del presupuesto (inyectable en tests). */
  nowMs?: () => number;
  /** Solo tests: se invoca entre la consulta y la re-comprobacion, para simular carreras. */
  beforeErase?: (category: string, uid: string) => Promise<void>;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

interface Counter {
  found: number;
  erased: number;
  failed: number;
  /** La condicion dejo de cumplirse entre la consulta y el borrado (carrera): no se borra. */
  skipped: number;
}
type Category = "stuckDeletion" | "orphanProfiles" | "parentalPending" | "orphanAuth";
export interface PurgeSummary extends Record<Category, Counter> {
  /** Perfiles revisados por el barrido de huerfanos (3b). */
  scannedProfiles: number;
  expired: Record<string, number>;
  /** Escrituras fallidas del barrido, por coleccion. */
  expiredFailed: Record<string, number>;
  budgetExhausted: boolean;
  /** Categorias cuya consulta fallo entera (indice ausente, backend caido). */
  failedCategories: string[];
}

/** Recorre una consulta por paginas con cursor (los fallos no provocan bucles: nunca se vuelve a consultar lo ya visto). */
async function forEachPage(query: Query, pageSize: number, fn: (docs: DocumentSnapshot[]) => Promise<boolean | void>): Promise<void> {
  let cursor: DocumentSnapshot | undefined;
  for (;;) {
    const snap = await (cursor ? query.startAfter(cursor) : query).limit(pageSize).get();
    if (snap.empty) return;
    if ((await fn(snap.docs)) === false || snap.size < pageSize) return;
    cursor = snap.docs[snap.size - 1];
  }
}

/**
 * Purga diaria de identidad (D4, R-a, AD3). Un fallo en un usuario o categoria nunca detiene el lote: se
 * registra `{category, uidHash, code}` (jamas uid, email ni el mensaje del error) y se reintenta en la
 * siguiente ejecucion, porque todo lo que no se borra sigue cumpliendo la misma condicion.
 *
 * Orden de prioridad (lo mas critico para RGPD primero, por si el presupuesto de tiempo se agota):
 * (b) borrados atascados, (b') perfiles huerfanos, (a) parental_pending caducado, (d) D4 cuentas Auth sin
 * perfil y por ultimo el barrido de docs caducados (la TTL de Firestore ya los retira con retraso).
 */
export async function purgeIdentityHandler(deps: PurgeDeps, now: Date = new Date()): Promise<PurgeSummary> {
  const { db, auth, erase } = deps;
  const pageSize = deps.pageSize ?? DEFAULT_PAGE_SIZE;
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const clockMs = deps.nowMs ?? Date.now;
  const deadline = clockMs() + (deps.budgetMs ?? DEFAULT_BUDGET_MS);
  const nowMs = now.getTime();
  const users = db.collection(COLLECTIONS.users);
  const ts = (ms: number) => Timestamp.fromMillis(ms);
  const counter = (): Counter => ({ found: 0, erased: 0, failed: 0, skipped: 0 });
  const summary: PurgeSummary = {
    stuckDeletion: counter(),
    orphanProfiles: counter(),
    parentalPending: counter(),
    orphanAuth: counter(),
    scannedProfiles: 0,
    expired: {},
    expiredFailed: {},
    budgetExhausted: false,
    failedCategories: [],
  };
  /** Usuarios ya tratados en esta ejecucion (una categoria no repite el trabajo de otra). */
  const handled = new Set<string>();
  const parentalCutoff = nowMs - PENDING_ACCOUNT_TTL_DAYS * DAY_MS;
  const graceCutoff = nowMs - GRACE_MS;

  /** Devuelve false si el presupuesto se agoto (el llamador deja de recorrer la categoria). */
  async function eraseOne(category: Category, uid: string, deleteAuth: boolean, stillHolds: () => Promise<boolean>): Promise<boolean> {
    if (summary.budgetExhausted || clockMs() >= deadline) {
      summary.budgetExhausted = true;
      return false;
    }
    const c = summary[category];
    c.found++;
    handled.add(uid);
    try {
      await deps.beforeErase?.(category, uid);
      // El doc pudo cambiar entre la consulta y ahora (p. ej. el tutor acepto): se re-comprueba la condicion.
      if (!(await stillHolds())) {
        c.skipped++;
        return true;
      }
      await erase(uid, { deleteAuth });
      c.erased++;
    } catch (e) {
      c.failed++;
      log("purge.userFailed", { category, uidHash: sha256Hex(uid).slice(0, 12), code: safeErrorCode(e) });
    }
    return true;
  }
  async function category(name: string, run: () => Promise<void>) {
    if (summary.budgetExhausted) return;
    try {
      await run();
    } catch (e) {
      summary.failedCategories.push(name);
      log("purge.categoryFailed", { category: name, code: safeErrorCode(e) });
    }
  }
  const millis = (v: unknown) => (v instanceof Timestamp ? v.toMillis() : Number.POSITIVE_INFINITY);
  const stillStuck = (uid: string) => async () => {
    const d = (await users.doc(uid).get()).data();
    return d?.deletion?.state === "in_progress" && millis(d.deletion.startedAt) <= graceCutoff;
  };
  const stillPending = (uid: string) => async () => {
    const d = (await users.doc(uid).get()).data();
    return d?.consentStatus === "parental_pending" && millis(d.guardian?.requestedAt) <= parentalCutoff;
  };

  // (b) borrados atascados: la cuenta quedo deshabilitada y sin sesiones; el usuario no puede reintentar desde la app.
  await category("stuckDeletion", () =>
    forEachPage(users.where("deletion.state", "==", "in_progress").where("deletion.startedAt", "<=", ts(graceCutoff)), pageSize, async (docs) => {
      for (const d of docs) if (!(await eraseOne("stuckDeletion", d.id, true, stillStuck(d.id)))) return false;
    }),
  );

  // (b') perfiles huerfanos (sin cuenta Auth): un ID token anterior al borrado puede recrearlos hasta 1 h despues.
  // Recorre todos los perfiles creados hace > 1 h (no hay consulta "sin Auth"); los que tienen `deletion` los trata (b).
  await category("orphanProfiles", () =>
    forEachPage(users.where("createdAt", "<=", ts(graceCutoff)).orderBy("createdAt"), Math.min(pageSize, GET_USERS_MAX), async (docs) => {
      summary.scannedProfiles += docs.length;
      const candidates = docs.filter((d) => !handled.has(d.id) && !d.data()?.deletion);
      if (candidates.length === 0) return;
      const { notFound } = await auth.getUsers(candidates.map((d) => ({ uid: d.id })));
      for (const nf of notFound) {
        if (!("uid" in nf)) continue;
        // Si la cuenta reaparece (o el perfil cambia) entre medias no hay nada que borrar: se relee el perfil.
        const stillOrphan = async () => (await users.doc(nf.uid).get()).exists && (await auth.getUsers([{ uid: nf.uid }])).notFound.length > 0;
        if (!(await eraseOne("orphanProfiles", nf.uid, false, stillOrphan))) return false;
      }
    }),
  );

  // (a) parental_pending: la antiguedad se mide desde `guardian.requestedAt` (R-a), no desde `createdAt`.
  await category("parentalPending", () =>
    forEachPage(users.where("consentStatus", "==", "parental_pending").where("guardian.requestedAt", "<=", ts(parentalCutoff)), pageSize, async (docs) => {
      for (const d of docs) if (!handled.has(d.id) && !(await eraseOne("parentalPending", d.id, true, stillPending(d.id)))) return false;
    }),
  );

  // (d) D4: cuentas Auth sin perfil con >= 7 d. Un perfil existente (incluido uno con `deletion`) las excluye, se
  // re-comprueba justo antes de borrar y el claim `purgeExempt: true` (cuentas de administracion/pruebas) las protege siempre.
  await category("orphanAuth", async () => {
    let pageToken: string | undefined;
    do {
      const page = await auth.listUsers(pageSize, pageToken);
      pageToken = page.pageToken;
      const old = page.users.filter((u) => Date.parse(u.metadata.creationTime) <= parentalCutoff && !handled.has(u.uid) && u.customClaims?.purgeExempt !== true);
      for (let i = 0; i < old.length; i += GET_USERS_MAX) {
        const chunk = old.slice(i, i + GET_USERS_MAX);
        const profiles = await db.getAll(...chunk.map((u) => users.doc(u.uid)));
        for (const [j, p] of profiles.entries()) {
          if (p.exists) continue;
          const uid = chunk[j].uid;
          if (!(await eraseOne("orphanAuth", uid, true, async () => !(await users.doc(uid).get()).exists))) return;
        }
      }
    } while (pageToken);
  });

  // (c) barrido determinista de docs caducados (la TTL de Firestore los borra con hasta ~24 h de retraso o mas).
  for (const name of [COLLECTIONS.mail, COLLECTIONS.guardianRequests, COLLECTIONS.guardianEmailLimits]) {
    summary.expired[name] = 0;
    summary.expiredFailed[name] = 0;
    await category(name, () =>
      forEachPage(db.collection(name).where("expireAt", "<", ts(nowMs)), pageSize, async (docs) => {
        const writer = db.bulkWriter();
        const settled = Promise.allSettled(docs.map((d) => writer.delete(d.ref)));
        await writer.close();
        const results = await settled;
        summary.expired[name] += results.filter((r) => r.status === "fulfilled").length;
        const failed = results.filter((r): r is PromiseRejectedResult => r.status === "rejected");
        summary.expiredFailed[name] += failed.length;
        if (failed.length > 0) log("purge.sweepFailed", { category: name, failed: failed.length, code: safeErrorCode(failed[0].reason) });
      }),
    );
  }

  if (summary.budgetExhausted) log("purge.budgetExhausted", { ...summary });
  log("purge.done", { ...summary });
  // Un fallo de categoria (consulta entera) debe verse y reintentarse; los fallos por usuario y el presupuesto no lanzan.
  if (summary.failedCategories.length > 0) throw new Error(`purgeIdentity: categorias fallidas: ${summary.failedCategories.join(",")}`);
  return summary;
}
