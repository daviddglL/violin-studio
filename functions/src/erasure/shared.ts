import { Firestore, Query } from "firebase-admin/firestore";
import { removeLink, RemoveLinkDeps, unlinkAllStudents } from "../teacher/remove-link";
import { ERASABLE_COLLECTIONS } from "./registry";

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

/**
 * Paso `shared`: documentos de subcolecciones AJENAS que llevan el uid (p. ej. las tareas de un profesor en el
 * espacio de cada alumno). Se barren con `collectionGroup(name).where(field, '==', uid)` en lotes hasta vaciar;
 * requiere el indice COLLECTION_GROUP del campo. Los contadores acumulan por coleccion.
 */
export async function sweepShared(db: Firestore, uid: string, deleted: Record<string, number>): Promise<void> {
  for (const [name, policy] of Object.entries(ERASABLE_COLLECTIONS)) {
    if (policy.kind !== "subcollectionOf" || !policy.foreign) continue;
    deleted[name] ??= 0;
    await deleteInBatches(db, db.collectionGroup(name).where(policy.foreign.field, "==", uid), (n) => (deleted[name] += n));
  }
}

/**
 * Politica `link`: desvincula con `removeLink` (contadores, rol, claims de la otra parte). Como profesor,
 * `unlinkAllStudents` cubre tambien a los alumnos con limpieza pendiente (`pendingUnlinks`); como alumno se
 * recorren sus vinculos. El usuario ya lleva `deletion`, asi que `removeLink` no escribe en su documento.
 */
export async function sweepLinks(
  deps: RemoveLinkDeps,
  uid: string,
  deleted: Record<string, number>,
): Promise<void> {
  for (const [name, policy] of Object.entries(ERASABLE_COLLECTIONS)) {
    if (policy.kind !== "link") continue;
    deleted[name] ??= 0;
    const [teacherField, studentField] = policy.fields;
    deleted[name] += await unlinkAllStudents(deps, uid);
    const asStudent = await deps.db.collection(name).where(studentField, "==", uid).get();
    for (const doc of asStudent.docs) {
      const teacherUid = doc.get(teacherField);
      if (typeof teacherUid !== "string") continue;
      await removeLink(deps, teacherUid, uid);
      deleted[name] += 1;
    }
  }
}
