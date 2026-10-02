import { Auth } from "firebase-admin/auth";
import { Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock, systemClock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { syncClaims } from "../identity/claims";
import { ERASABLE_COLLECTIONS, STORAGE_PREFIXES } from "./registry";

/** Esperas entre reintentos de cada paso (hasta 4 intentos en total). */
export const RETRY_DELAYS_MS = [200, 800, 2000] as const;
const BATCH_SIZE = 400;

export interface ErasureDeps {
  db: Firestore;
  auth: Auth;
  /** Solo lo que se usa del bucket: borrado por prefijo. */
  bucket: { deleteFiles(options: { prefix: string; force?: boolean }): Promise<unknown> };
  clock?: Clock;
  sleep?: (ms: number) => Promise<void>;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface ErasureResult {
  /** Documentos borrados por colección de consulta (contadores, sin identificadores). */
  deleted: Record<string, number>;
}

const codeOf = (e: unknown): unknown => (e as { code?: unknown } | null)?.code;
const isFirestoreNotFound = (e: unknown) => codeOf(e) === 5 || codeOf(e) === "not-found";
const isAuthNotFound = (e: unknown) => codeOf(e) === "auth/user-not-found";

/**
 * Cascada de borrado de `uid`, reanudable e idempotente. Cada paso se reintenta con backoff; el
 * borrado de Auth es siempre el último, de modo que un fallo previo deja la cuenta localizable y el
 * marcador `deletion` permite reintentar. Los logs solo llevan `uidHash`, paso y contadores.
 */
export async function eraseUserData(
  deps: ErasureDeps,
  uid: string,
  opts: { deleteAuth: boolean },
): Promise<ErasureResult> {
  const { db, auth, bucket } = deps;
  const clock = deps.clock ?? systemClock;
  const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const baseLog = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const uidHash = sha256Hex(uid).slice(0, 12);
  const log = (message: string, data: Record<string, unknown>) => baseLog(message, { uidHash, ...data });
  const userRef = db.collection(COLLECTIONS.users).doc(uid);

  async function step<T>(name: string, fn: () => Promise<T>): Promise<T> {
    for (let attempt = 0; ; attempt++) {
      try {
        const result = await fn();
        log("erasure.step", { step: name, attempt: attempt + 1 });
        return result;
      } catch (e) {
        const code = codeOf(e);
        log("erasure.stepFailed", { step: name, attempt: attempt + 1, code: typeof code === "string" || typeof code === "number" ? code : "unknown" });
        if (attempt >= RETRY_DELAYS_MS.length) throw e;
        await sleep(RETRY_DELAYS_MS[attempt]);
      }
    }
  }

  // 1. Marcador de borrado en curso (conserva el original si ya existe) y claims fuera.
  await step("mark", async () => {
    const snap = await userRef.get();
    if (!snap.exists) return;
    if (!snap.data()?.deletion) {
      try {
        await userRef.update({ deletion: { state: "in_progress", startedAt: clock() } });
      } catch (e) {
        if (isFirestoreNotFound(e)) return; // otro borrado concurrente ya eliminó el documento
        throw e;
      }
    }
    try {
      await syncClaims({ db, auth }, uid);
    } catch (e) {
      if (!isAuthNotFound(e)) throw e; // la cuenta Auth ya no existe (p. ej. onUserDeleted)
    }
  });

  // 2. Colecciones con uid como campo: lotes hasta vaciar.
  const deleted: Record<string, number> = {};
  await step("collections", async () => {
    for (const [name, policy] of Object.entries(ERASABLE_COLLECTIONS)) {
      if (policy.kind !== "queryByField") continue;
      deleted[name] = 0;
      for (;;) {
        const snap = await db.collection(name).where(policy.field, "==", uid).limit(BATCH_SIZE).get();
        if (snap.empty) break;
        const writer = db.bulkWriter();
        const pending = snap.docs.map((d) => writer.delete(d.ref));
        await writer.close();
        await Promise.all(pending); // propaga los fallos de escritura que close() no lanza
        deleted[name] += snap.docs.length;
      }
    }
  });

  // 3. Storage.
  await step("storage", async () => {
    for (const prefix of STORAGE_PREFIXES) await bucket.deleteFiles({ prefix: prefix(uid), force: true });
  });

  // 4. Documento de usuario con sus subcolecciones (consents); el marcador vive hasta aquí.
  await step("userDoc", () => db.recursiveDelete(userRef));

  // 5. Auth, siempre el último.
  if (opts.deleteAuth) {
    await step("auth", async () => {
      try {
        await auth.deleteUser(uid);
      } catch (e) {
        if (!isAuthNotFound(e)) throw e;
      }
    });
  }

  log("erasure.done", { deleteAuth: opts.deleteAuth, ...deleted });
  return { deleted };
}
