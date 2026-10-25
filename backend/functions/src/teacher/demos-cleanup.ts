import { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { deleteRecording } from "../recordings/delete-recording";
import type { ObjectStore } from "../recordings/finalize";
import { LinkCleanupStep } from "./remove-link";

const PAGE = 100;
/** Pasadas sin vaciar la consulta antes de rendirse: un doc que siempre cambia entre leerlo y borrarlo no es normal. */
const MAX_PASSES = 3;

/**
 * Paso de `removeLink` (REQ-REC-10, B5b.4): borra las demostraciones de `teacherUid` dirigidas a `studentUid`
 * (`users/{t}/recordings` con `sharedWith == s`) con `deleteRecording`, que borra el objeto por la `objectPath` ALMACENADA
 * (`demos/`, nunca una ruta derivada del id), el doc y sus revisiones. Solo toca las demos de ESE par: las de otros alumnos,
 * las grabaciones propias del profesor y todo lo del alumno quedan. Idempotente y reanudable: un fallo del objeto deja el doc
 * (se relanza y `removeLink` conserva la marca `pendingUnlinks`); repetirlo sin demos no hace nada.
 */
export function deleteSharedDemos(db: Firestore, store: ObjectStore): LinkCleanupStep {
  return async (teacherUid, studentUid) => {
    const recordings = db.collection(COLLECTIONS.users).doc(teacherUid).collection(COLLECTIONS.recordings);
    for (let pass = 0; pass < MAX_PASSES; pass++) {
      const snap = await recordings.where("sharedWith", "==", studentUid).limit(PAGE).get();
      if (snap.empty) return;
      for (const d of snap.docs) await deleteRecording({ db, store }, d.ref);
    }
    if (!(await recordings.where("sharedWith", "==", studentUid).limit(1).get()).empty) {
      throw new Error("deleteSharedDemos: quedan demostraciones tras varias pasadas");
    }
  };
}
