# Runbook: reglas de Storage multi-servicio (grabaciones, fase 4b)

`storage.rules` lee Firestore (`firestore.get/exists`) para decidir quien sube y quien lee grabaciones
(`users/{uid}/recordings/{id}.m4a`). Pasos de despliegue (design §9, paso 4).

## 1. Permiso IAM del agente de servicio de Storage

Las reglas cruzadas requieren que el agente de servicio de Storage del proyecto pueda leer Firestore.

- La CLI (`firebase deploy --only storage`) lo ofrece conceder la primera vez que despliega reglas con
  `firestore.get/exists`; aceptar la propuesta.
- Manualmente: conceder el rol `roles/firebaserules.firestoreServiceAgent` a
  `service-<PROJECT_NUMBER>@gcp-sa-firebasestorage.iam.gserviceaccount.com`:

  ```
  gcloud projects add-iam-policy-binding <PROJECT_ID> \
    --member="serviceAccount:service-<PROJECT_NUMBER>@gcp-sa-firebasestorage.iam.gserviceaccount.com" \
    --role="roles/firebaserules.firestoreServiceAgent"
  ```

- Verificar: subir una grabacion de prueba con un usuario con `recordingConsent = granted` (debe permitirse) y con uno
  sin consentimiento (debe denegarse con `storage/unauthorized`).

## 2. Limites conocidos

- Tope de plataforma: **2** accesos `firestore.get/exists` por evaluacion (A20). El emulador NO lo aplica (spike
  B3a.0); `rules-tests/test/storage-access-budget.test.ts` lo vigila de forma estatica en CI. Cualquier regla nueva de
  Storage debe pasar ese test.
- El emulador evalua la sobrescritura de un objeto existente como `create`; por eso `create` exige `resource == null`.
- Las constantes de las reglas (25 MiB, cupo 20, version de consentimiento de grabacion) se repiten como literales;
  `functions/test/unit/storage-rules-parity.test.ts` falla si se desalinean de `functions/src/config/`.
- Las reglas son corte rapido: la autoridad sobre cupo, duracion y contenedor es `finalizeRecording` (B4b).

## 3. Despliegue

1. Conceder el IAM (seccion 1).
2. `firebase deploy --only storage --project <PROJECT_ID>`.
3. Aplicar `infra/storage-lifecycle.json` (slice posterior) y habilitar `RECORDINGS_ENABLED` segun el design §9.
