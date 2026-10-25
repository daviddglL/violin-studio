import { DocumentSnapshot, FieldPath, FieldValue, Firestore, Query } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { cursorKey } from "./cursor";

export type ReconcileOutcome = "ok" | "repaired" | "skipped";

/**
 * Reconcilia `users/{uid}.recordingCount` con el recuento real de docs `ready` de `users/{uid}/recordings` (A21; REQ-REC-14).
 * Todo en UNA transaccion: el contador y el recuento (consulta de agregacion) se leen bajo los mismos bloqueos que usa
 * `finalizeRecording`, asi una subida concurrente no se pierde. No escribe si ya coincide (ausente == 0). Sin doc de
 * usuario (cascada ya hecha) o con la cuenta en borrado no se toca nada: nunca se recrea `users/{uid}`.
 *
 * Al reparar sella `recordingCountReconciledAt` (hora del commit): un `onRecordingDeleted` de un borrado ANTERIOR a ese
 * instante que llegue despues ya esta contado aqui y no vuelve a bajar el contador (W2 de la revision fresca de B5a).
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
    tx.update(userRef, { recordingCount: real, recordingCountReconciledAt: FieldValue.serverTimestamp() });
    return "repaired";
  });
}

export interface ReconcileAllOpts {
  pageSize?: number;
  /** Cursor de la pasada anterior (prefijo de uid, ver `cursor.ts`): se empieza ahi. "" o ausente = desde la cabeza. */
  resumeFrom?: string;
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
  /** Cursor para la siguiente pasada: prefijo del uid donde se corto, o "" si la vuelta se completo. */
  next: string;
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
 * Candidatos: (1) todo usuario con campo `recordingCount` (cualquier tipo, incluso corrupto), recorriendo `users` por id
 * desde `resumeFrom` y (2) los duenos de algun doc `ready` desde ese mismo punto (cubre un contador ausente). La decision
 * real la toma `reconcileOwner` dentro de su transaccion. Si `shouldStop` corta la pasada, `next` es el prefijo del uid en
 * el que se corto (la siguiente pasada sigue ahi en vez de empezar siempre por la cabeza: W1); si llega al final, "".
 * Los duenos por delante del cursor sin contador se cubren cuando la vuelta vuelve a la cabeza.
 */
export async function reconcileAllOwners(db: Firestore, opts: ReconcileAllOpts = {}): Promise<ReconcileAllResult> {
  const pageSize = opts.pageSize ?? DEFAULT_PAGE;
  const reconcile = opts.reconcile ?? reconcileOwner;
  const from = opts.resumeFrom ?? "";
  const res: ReconcileAllResult = { scanned: 0, repaired: 0, failed: 0, stopped: false, next: "" };
  const seen = new Set<string>();

  /** `false` = parar. */
  const visit = async (uid: string): Promise<boolean> => {
    if (seen.has(uid) || opts.skip?.has(uid)) return true;
    seen.add(uid);
    if (opts.shouldStop?.()) {
      res.stopped = true;
      res.next = cursorKey(uid);
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

  let users: Query = db.collection(COLLECTIONS.users).orderBy(FieldPath.documentId());
  if (from) users = users.where(FieldPath.documentId(), ">=", from);
  if (!(await pages(users, pageSize, async (docs) => {
    for (const d of docs) if (d.get("recordingCount") !== undefined && !(await visit(d.id))) return false;
    return true;
  }))) return res;

  // Orden por ruta completa (`users/{uid}/recordings/{id}`) = orden por uid: lo anterior al cursor se salta.
  const readyDocs = db.collectionGroup(COLLECTIONS.recordings).where("status", "==", "ready");
  await pages(readyDocs, pageSize, async (docs) => {
    for (const d of docs) {
      const owner = d.ref.parent.parent?.id;
      if (owner && owner >= from && !(await visit(owner))) return false;
    }
    return true;
  });
  return res;
}
