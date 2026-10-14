import { Auth } from "firebase-admin/auth";
import { DocumentSnapshot, Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { CURRENT_POLICY_VERSION } from "../config/identity";
import { reevaluatedStatus, reevaluateConsent, ReevaluateDeps } from "./reevaluate";

export interface ReevaluateAllDeps {
  db: Firestore;
  auth: Auth;
  /** Versión vigente; solo se inyecta en tests. */
  currentVersion?: number;
  pageSize?: number;
  /** Reevaluación de un usuario; inyectable para simular fallos. */
  reevaluate?: (deps: ReevaluateDeps, uid: string) => Promise<void>;
  /** Sumidero de logs sin PII (solo `uidHash` y códigos); por defecto `console.log`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface ReevaluateAllResult {
  /** Perfiles `granted` revisados. */
  scanned: number;
  /** Perfiles con versión anterior que se han reevaluado con éxito. */
  changed: number;
  failed: number;
}

const DEFAULT_PAGE_SIZE = 200;

/**
 * Reevaluación masiva del consentimiento tras subir `CURRENT_POLICY_VERSION` (A4 S1). Sin ella, un alumno
 * vinculado seguiría `granted` con la versión antigua hasta su siguiente `identityConfig`, y las reglas (que miran
 * `consentStatus`, no la versión) dejarían al profesor leer su registro durante ese intervalo.
 *
 * Cada usuario pasa por `reevaluateConsent` (transacción que relee el documento: decide el documento, no el
 * claim, y no toca borrados en curso). Un fallo se cuenta y se registra sin PII y no detiene el lote; la
 * pasada es idempotente y reanudable (lo ya reevaluado deja de ser `granted`).
 */
export async function reevaluateAllConsents(deps: ReevaluateAllDeps): Promise<ReevaluateAllResult> {
  const current = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  const pageSize = deps.pageSize ?? DEFAULT_PAGE_SIZE;
  const run = deps.reevaluate ?? reevaluateConsent;
  const log = deps.log ?? ((m, d) => console.log(m, JSON.stringify(d)));
  const base = deps.db.collection(COLLECTIONS.users).where("consentStatus", "==", "granted").orderBy("__name__");
  const result: ReevaluateAllResult = { scanned: 0, changed: 0, failed: 0 };

  let cursor: DocumentSnapshot | undefined;
  for (;;) {
    const snap = await (cursor ? base.startAfter(cursor) : base).limit(pageSize).get();
    if (snap.empty) break;
    for (const d of snap.docs) {
      result.scanned++;
      if (reevaluatedStatus(d.data(), current) === null) continue;
      try {
        await run({ db: deps.db, auth: deps.auth, currentVersion: current }, d.id);
        result.changed++;
      } catch (e) {
        result.failed++;
        log("reevaluateAll.userFailed", { uidHash: sha256Hex(d.id).slice(0, 12), code: safeErrorCode(e) });
      }
    }
    if (snap.size < pageSize) break;
    cursor = snap.docs[snap.size - 1];
  }
  log("reevaluateAll.done", { ...result });
  return result;
}
