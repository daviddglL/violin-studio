import { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { LinkCleanupStep } from "./remove-link";

/** Limite de operaciones por lote de Firestore es 500; se deja margen. */
const BATCH = 400;

/**
 * Paso de `removeLink` (fase 4c): borra todos los mensajes del chat del par (`chatMessages` con `teacherUid == t` y
 * `studentUid == s`), de los dos autores. Los chats de otros pares no se tocan. Idempotente: repetirlo no encuentra
 * nada. Consulta de igualdad doble, resuelta con los indices de campo unico (sin compuesto).
 */
export function deleteChatMessages(db: Firestore): LinkCleanupStep {
  return async (teacherUid, studentUid) => {
    const messages = db.collection(COLLECTIONS.chatMessages);
    for (;;) {
      const snap = await messages.where("teacherUid", "==", teacherUid).where("studentUid", "==", studentUid).limit(BATCH).get();
      if (snap.empty) return;
      const batch = db.batch();
      for (const doc of snap.docs) batch.delete(doc.ref);
      await batch.commit();
    }
  };
}
