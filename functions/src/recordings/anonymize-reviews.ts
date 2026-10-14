import { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";

const DEFAULT_BATCH = 400;
const PARALLEL = 10;

export interface AnonymizeOpts {
  /** Revisiones por consulta (tests). */
  batchSize?: number;
  /** Recibe el numero de revisiones anonimizadas de cada lote, tambien el de un lote que acaba fallando. */
  onProgress?: (n: number) => void;
}

/**
 * Anonimiza las revisiones de `reviewerUid` (REQ-RVW-04): cada una se re-ubica en un id aleatorio con `reviewerUid: null`
 * y el resto de campos intactos (texto, veredicto, fechas), de modo que ni la ruta (`reviews/{reviewerUid}`) ni los campos
 * conservan el uid. Por revision, UNA transaccion lee la vieja, crea la nueva y borra la vieja: o ocurre todo o nada, asi
 * que un fallo a mitad nunca deja duplicadas ni perdidas, y dos ejecuciones concurrentes no la duplican (la segunda la
 * ve ya movida y la salta). La nueva no casa con la consulta (`reviewerUid == null`), por lo que el bucle termina; repetirla
 * sin revisiones es un no-op. Requiere el indice COLLECTION_GROUP `reviews.reviewerUid`. Devuelve cuantas movio.
 */
export async function anonymizeReviews(db: Firestore, reviewerUid: string, opts: AnonymizeOpts = {}): Promise<number> {
  const batchSize = opts.batchSize ?? DEFAULT_BATCH;
  let total = 0;
  for (;;) {
    const snap = await db.collectionGroup(COLLECTIONS.reviews).where("reviewerUid", "==", reviewerUid).limit(batchSize).get();
    if (snap.empty) return total;
    let moved = 0;
    let failure: { reason: unknown } | undefined;
    for (let i = 0; i < snap.docs.length; i += PARALLEL) {
      const results = await Promise.allSettled(
        // `async` envolvente: un fallo sincrono de `runTransaction` tambien llega como rechazo (y no deja las demas en vuelo).
        snap.docs.slice(i, i + PARALLEL).map(async (d) =>
          db.runTransaction(async (tx) => {
            const cur = await tx.get(d.ref);
            const data = cur.data();
            if (!cur.exists || !data || data.reviewerUid !== reviewerUid) return 0; // otra ejecucion ya la movio
            tx.create(d.ref.parent.doc(), { ...data, reviewerUid: null });
            tx.delete(d.ref);
            return 1;
          }),
        ),
      );
      for (const r of results) {
        if (r.status === "fulfilled") moved += r.value;
        else failure ??= { reason: r.reason };
      }
    }
    total += moved;
    opts.onProgress?.(moved);
    if (failure) throw failure.reason;
    // Un lote entero sin avance (todas saltadas) no puede repetirse sin fin: la consulta no deberia devolverlas.
    if (moved === 0) return total;
  }
}
