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
const DEFAULT_PAGE_SIZE = 100;
const GET_USERS_MAX = 100;

export interface PurgeDeps {
  db: Firestore;
  auth: Pick<Auth, "getUsers" | "listUsers">;
  /** `eraseUserData` con las deps reales; inyectable para probar la purga sin cascada. */
  erase: (uid: string, opts: { deleteAuth: boolean }) => Promise<unknown>;
  /** Tamano de pagina de las consultas y de `listUsers`. */
  pageSize?: number;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

interface Counter {
  found: number;
  erased: number;
  failed: number;
}
export interface PurgeSummary {
  parentalPending: Counter;
  stuckDeletion: Counter;
  orphanProfiles: Counter;
  orphanAuth: Counter;
  expired: Record<string, number>;
  /** Categorias cuya consulta fallo entera (indice ausente, backend caido). */
  failedCategories: string[];
}

/** Recorre una consulta por paginas con cursor (los fallos no provocan bucles: nunca se vuelve a consultar lo ya visto). */
async function forEachPage(query: Query, pageSize: number, fn: (docs: DocumentSnapshot[]) => Promise<void>): Promise<void> {
  let cursor: DocumentSnapshot | undefined;
  for (;;) {
    const snap = await (cursor ? query.startAfter(cursor) : query).limit(pageSize).get();
    if (snap.empty) return;
    await fn(snap.docs);
    if (snap.size < pageSize) return;
    cursor = snap.docs[snap.size - 1];
  }
}

/**
 * Purga diaria de identidad (D4, R-a, AD3). Un fallo en un usuario o categoria nunca detiene el lote: se
 * registra `{category, uidHash, code}` (jamas uid, email ni el mensaje del error) y se reintenta en la
 * siguiente ejecucion, porque todo lo que no se borra sigue cumpliendo la misma condicion.
 */
export async function purgeIdentityHandler(deps: PurgeDeps, now: Date = new Date()): Promise<PurgeSummary> {
  const { db, auth, erase } = deps;
  const pageSize = deps.pageSize ?? DEFAULT_PAGE_SIZE;
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const nowMs = now.getTime();
  const users = db.collection(COLLECTIONS.users);
  const ts = (ms: number) => Timestamp.fromMillis(ms);
  const counter = (): Counter => ({ found: 0, erased: 0, failed: 0 });
  const summary: PurgeSummary = { parentalPending: counter(), stuckDeletion: counter(), orphanProfiles: counter(), orphanAuth: counter(), expired: {}, failedCategories: [] };
  /** Usuarios ya tratados en esta ejecucion (una categoria no repite el trabajo de otra). */
  const handled = new Set<string>();

  async function eraseOne(category: keyof Omit<PurgeSummary, "expired" | "failedCategories">, uid: string, deleteAuth: boolean) {
    const c = summary[category];
    c.found++;
    handled.add(uid);
    try {
      await erase(uid, { deleteAuth });
      c.erased++;
    } catch (e) {
      c.failed++;
      log("purge.userFailed", { category, uidHash: sha256Hex(uid).slice(0, 12), code: safeErrorCode(e) });
    }
  }
  async function category(name: string, run: () => Promise<void>) {
    try {
      await run();
    } catch (e) {
      summary.failedCategories.push(name);
      log("purge.categoryFailed", { category: name, code: safeErrorCode(e) });
    }
  }

  // (a) parental_pending: la antiguedad se mide desde `guardian.requestedAt` (R-a), no desde `createdAt`.
  await category("parentalPending", () =>
    forEachPage(
      users.where("consentStatus", "==", "parental_pending").where("guardian.requestedAt", "<=", ts(nowMs - PENDING_ACCOUNT_TTL_DAYS * DAY_MS)),
      pageSize,
      async (docs) => {
        for (const d of docs) await eraseOne("parentalPending", d.id, true);
      },
    ),
  );

  // (b) borrados atascados: la cuenta quedo deshabilitada y sin sesiones; el usuario no puede reintentar desde la app.
  await category("stuckDeletion", () =>
    forEachPage(
      users.where("deletion.state", "==", "in_progress").where("deletion.startedAt", "<=", ts(nowMs - GRACE_MS)),
      pageSize,
      async (docs) => {
        for (const d of docs) if (!handled.has(d.id)) await eraseOne("stuckDeletion", d.id, true);
      },
    ),
  );

  // (b') perfiles huerfanos (sin cuenta Auth): un ID token anterior al borrado puede recrearlos hasta 1 h despues.
  // Recorre todos los perfiles creados hace > 1 h (no hay consulta "sin Auth"); los que tienen `deletion` los trata (b).
  await category("orphanProfiles", () =>
    forEachPage(users.where("createdAt", "<=", ts(nowMs - GRACE_MS)).orderBy("createdAt"), Math.min(pageSize, GET_USERS_MAX), async (docs) => {
      const candidates = docs.filter((d) => !handled.has(d.id) && !d.data()?.deletion);
      if (candidates.length === 0) return;
      const { notFound } = await auth.getUsers(candidates.map((d) => ({ uid: d.id })));
      for (const nf of notFound) if ("uid" in nf) await eraseOne("orphanProfiles", nf.uid, false);
    }),
  );

  // (c) barrido determinista de docs caducados (la TTL de Firestore los borra con hasta ~24 h de retraso o mas).
  for (const name of [COLLECTIONS.mail, COLLECTIONS.guardianRequests, COLLECTIONS.guardianEmailLimits]) {
    summary.expired[name] = 0;
    await category(name, () =>
      forEachPage(db.collection(name).where("expireAt", "<", ts(nowMs)), pageSize, async (docs) => {
        const writer = db.bulkWriter();
        const settled = Promise.allSettled(docs.map((d) => writer.delete(d.ref)));
        await writer.close();
        const results = await settled;
        summary.expired[name] += results.filter((r) => r.status === "fulfilled").length;
        const failed = results.find((r): r is PromiseRejectedResult => r.status === "rejected");
        if (failed) log("purge.sweepFailed", { category: name, code: safeErrorCode(failed.reason) });
      }),
    );
  }

  // (d) D4: cuentas Auth sin perfil con >= 7 d. Un perfil existente (incluido uno con `deletion`) las excluye.
  await category("orphanAuth", async () => {
    const cutoff = nowMs - PENDING_ACCOUNT_TTL_DAYS * DAY_MS;
    let pageToken: string | undefined;
    do {
      const page = await auth.listUsers(pageSize, pageToken);
      pageToken = page.pageToken;
      const old = page.users.filter((u) => Date.parse(u.metadata.creationTime) <= cutoff && !handled.has(u.uid));
      for (let i = 0; i < old.length; i += GET_USERS_MAX) {
        const chunk = old.slice(i, i + GET_USERS_MAX);
        const profiles = await db.getAll(...chunk.map((u) => users.doc(u.uid)));
        for (const [j, p] of profiles.entries()) if (!p.exists) await eraseOne("orphanAuth", chunk[j].uid, true);
      }
    } while (pageToken);
  });

  log("purge.done", { ...summary });
  // Un fallo de categoria (consulta entera) debe verse y reintentarse; los fallos por usuario no lanzan.
  if (summary.failedCategories.length > 0) throw new Error(`purgeIdentity: categorias fallidas: ${summary.failedCategories.join(",")}`);
  return summary;
}
