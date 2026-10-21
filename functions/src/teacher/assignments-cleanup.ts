import { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import type { ObjectStore } from "../recordings/finalize";
import { deleteChatMessages } from "./chat-cleanup";
import { deleteSharedDemos } from "./demos-cleanup";
import { LinkCleanupStep } from "./remove-link";

/** Limite de operaciones por lote de Firestore es 500; se deja margen. */
const BATCH = 400;

/**
 * Paso de `removeLink` (REQ-ASG-05): borra las tareas `open` de `teacherUid` en `users/{studentUid}/assignments`
 * y conserva las `done`. Idempotente (repetirlo no encuentra nada) y acotado al par: las tareas de otros
 * profesores no se tocan. Consulta de igualdad doble, resuelta con los indices de campo unico (sin compuesto).
 */
export function deleteOpenAssignments(db: Firestore): LinkCleanupStep {
  return async (teacherUid, studentUid) => {
    const assignments = db.collection(COLLECTIONS.users).doc(studentUid).collection(COLLECTIONS.assignments);
    for (;;) {
      const snap = await assignments.where("teacherUid", "==", teacherUid).where("status", "==", "open").limit(BATCH).get();
      if (snap.empty) return;
      const batch = db.batch();
      for (const doc of snap.docs) batch.delete(doc.ref);
      await batch.commit();
    }
  };
}

/** Pasos por defecto de `removeLink` en produccion. Los pasos nuevos se COMPONEN con este array, no lo sustituyen. */
export function defaultLinkCleanupSteps(db: Firestore, store: ObjectStore): readonly LinkCleanupStep[] {
  return [deleteOpenAssignments(db), deleteSharedDemos(db, store), deleteChatMessages(db)];
}
