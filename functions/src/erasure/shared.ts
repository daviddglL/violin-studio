import { Firestore, Query } from "firebase-admin/firestore";

const BATCH_SIZE = 400;

/**
 * Borra en lotes todo lo que devuelva `query` hasta vaciarla. `onDeleted` recibe el numero de borrados de
 * cada lote (tambien los de un lote parcialmente fallido, para que los contadores acumulen entre reintentos).
 */
export async function deleteInBatches(db: Firestore, query: Query, onDeleted: (n: number) => void): Promise<void> {
  for (;;) {
    const snap = await query.limit(BATCH_SIZE).get();
    if (snap.empty) return;
    const writer = db.bulkWriter();
    // Se observan ANTES de close(): un rechazo sin manejador sería unhandledRejection.
    const settled = Promise.allSettled(snap.docs.map((d) => writer.delete(d.ref)));
    await writer.close();
    const results = await settled;
    onDeleted(results.filter((r) => r.status === "fulfilled").length);
    const failed = results.find((r): r is PromiseRejectedResult => r.status === "rejected");
    if (failed) throw failed.reason;
  }
}
