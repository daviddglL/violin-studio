import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import * as functionsV1 from "firebase-functions/v1";
import { REGION } from "../config/runtime";

/**
 * PROVISIONAL (spike 1a.1): escribe un marcador en Firestore cuando se borra un usuario de Auth.
 * Se reescribe en el slice 7a con la limpieza real de datos.
 */
export const onUserDeleted = functionsV1
  .region(REGION)
  .auth.user()
  .onDelete(async (user) => {
    const app = getApps()[0] ?? initializeApp();
    await getFirestore(app).collection("spikeMarkers").doc(user.uid).set({ deleted: true });
  });
