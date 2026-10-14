import { DocumentSnapshot, Firestore, Query } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";

export type ReconcileOutcome = "ok" | "repaired" | "skipped";

/**
 * Reconcilia `users/{uid}.recordingCount` con el recuento real de docs `ready` de `users/{uid}/recordings` (A21; REQ-REC-14).
 * Todo en UNA transaccion: el contador y el recuento (consulta de agregacion) se leen bajo los mismos bloqueos que usa
 * `finalizeRecording`, asi una subida concurrente no se pierde. No escribe si ya coincide (ausente == 0). Sin doc de
 * usuario (cascada ya hecha) o con la cuenta en borrado no se toca nada: nunca se recrea `users/{uid}`.
 */
export async function reconcileOwner(db: Firestore, uid: string): Promise<ReconcileOutcome> {
  const userRef = db.collection(COLLECTIONS.users).doc(uid);
  const ready = userRef.collection(COLLECTIONS.recordings).where("status", "==", "ready");
  return db.runTransaction(async (tx): Promise<ReconcileOutcome> => {
    const [user, agg] = await Promise.all([tx.get(userRef), tx.get(ready.count())]);
    const u = user.data();
    if (!user.exists || !u || (u.deletion !== undefined && u.deletion !== null)) return "skipped";
    const real = agg.data().count;
    const current = u.recordingCount;
    if (current === real || (current === undefined && real === 0)) return "ok";
    tx.update(userRef, { recordingCount: real });
    return "repaired";
  });
}

export interface ReconcileAllOpts {
  pageSize?: number;
  /** Se consulta antes de cada dueno (presupuesto de tiempo): `true` corta la pasada. */
  shouldStop?: () => boolean;
  /** Duenos a los que NO se toca (la purga acaba de borrarles docs y el trigger de esos borrados aun puede bajar el contador). */
  skip?: ReadonlySet<string>;
  /** Inyectable para probar fallos parciales. */
  reconcile?: (db: Firestore, uid: string) => Promise<ReconcileOutcome>;
  /** Un fallo no detiene la pasada: se avisa con el codigo (nunca uid ni mensaje en claro). */
  onFailure?: (uid: string, code: string | number) => void;
}

export interface ReconcileAllResult {
  scanned: number;
  repaired: number;
  failed: number;
  stopped: boolean;
}

const DEFAULT_PAGE = 100;

/** Recorre una consulta con cursor: un fallo del dueno nunca provoca bucles (no se vuelve a consultar lo ya visto). */
async function pages(query: Query, pageSize: number, fn: (docs: DocumentSnapshot[]) => Promise<boolean>): Promise<boolean> {
  let cursor: DocumentSnapshot | undefined;
  for (;;) {
    const snap = await (cursor ? query.startAfter(cursor) : query).limit(pageSize).get();
    if (snap.empty) return true;
    if (!(await fn(snap.docs))) return false;
    if (snap.size < pageSize) return true;
    cursor = snap.docs[snap.size - 1];
  }
}

/**
 * Candidatos: (1) todo usuario con campo `recordingCount` (cualquier tipo: `orderBy` incluye valores corruptos) y (2) los
 * duenos de algun doc `ready` (cubre un contador ausente). La decision real la toma `reconcileOwner` dentro de su transaccion.
 */
export async function reconcileAllOwners(db: Firestore, opts: ReconcileAllOpts = {}): Promise<ReconcileAllResult> {
  const pageSize = opts.pageSize ?? DEFAULT_PAGE;
  const reconcile = opts.reconcile ?? reconcileOwner;
  const res: ReconcileAllResult = { scanned: 0, repaired: 0, failed: 0, stopped: false };
  const seen = new Set<string>();

  /** `false` = parar. */
  const visit = async (uid: string): Promise<boolean> => {
    if (seen.has(uid) || opts.skip?.has(uid)) return true;
    seen.add(uid);
    if (opts.shouldStop?.()) {
      res.stopped = true;
      return false;
    }
    res.scanned++;
    try {
      if ((await reconcile(db, uid)) === "repaired") res.repaired++;
    } catch (e) {
      res.failed++;
      opts.onFailure?.(uid, safeErrorCode(e));
    }
    return true;
  };

  const withCounter = db.collection(COLLECTIONS.users).orderBy("recordingCount");
  if (!(await pages(withCounter, pageSize, async (docs) => {
    for (const d of docs) if (!(await visit(d.id))) return false;
    return true;
  }))) return res;

  const readyDocs = db.collectionGroup(COLLECTIONS.recordings).where("status", "==", "ready");
  await pages(readyDocs, pageSize, async (docs) => {
    for (const d of docs) {
      const owner = d.ref.parent.parent?.id;
      if (owner && !(await visit(owner))) return false;
    }
    return true;
  });
  return res;
}
