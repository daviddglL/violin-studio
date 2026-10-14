# Runbook: reglas de Storage multi-servicio (grabaciones, fase 4b)

`storage.rules` lee Firestore (`firestore.get/exists`) para decidir quien sube y quien lee grabaciones
(`users/{uid}/recordings/{id}.m4a`) y demostraciones del profesor (`users/{uid}/demos/{id}.m4a`; su doc vive en
`users/{uid}/recordings/{id}`). Pasos de despliegue (design §9, paso 4).

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

- Verificar en staging (smoke test, obligatorio antes de produccion). El emulador NO aplica el tope de 2 accesos, asi
  que solo un proyecto real demuestra que las lecturas caben en el presupuesto:
  1. Subida: un usuario con `recordingConsent = granted` sube (permitido); uno sin consentimiento recibe
     `storage/unauthorized`.
  2. Lectura del profesor: un profesor vinculado descarga una grabacion `ready` de su alumno (permitido; camino de 2
     accesos: vinculo + doc del alumno). Un profesor no vinculado recibe `storage/unauthorized`.
  3. Lectura de demo: el alumno `sharedWith` descarga la demo del profesor en `users/{t}/demos/{id}.m4a` (permitido;
     camino de 2 accesos: doc de la grabacion + vinculo). Otro alumno recibe `storage/unauthorized`.
  Si 2 o 3 se deniegan al usuario legitimo, sospechar del tope (o del IAM) antes que de los datos.

## 2. Limites conocidos

- Tope de plataforma: **2** accesos `firestore.get/exists` por peticion (A20). Cuentan TODOS los evaluados: una rama
  de `||` que da false ya gasto sus accesos antes de pasar a la siguiente. Por eso cada ruta tiene una sola rama de
  lectura que gasta accesos (grabaciones: profesor vinculado; demos: alumno destinatario) y el test estatico
  `rules-tests/test/storage-access-budget.test.ts` suma el peor caso acumulado. **El emulador NO aplica el tope**
  (spike B3a.0): el limite real queda sin verificar hasta el smoke test de staging (seccion 1). Cualquier regla nueva
  de Storage debe pasar ese test.
- El emulador evalua la sobrescritura de un objeto existente como `create`; por eso `create` exige `resource == null`.
- Las constantes de las reglas (25 MiB, cupo 20, version de consentimiento de grabacion) se repiten como literales;
  `functions/test/unit/storage-rules-parity.test.ts` falla si se desalinean de `functions/src/config/`.
- Las reglas son corte rapido: la autoridad sobre cupo, duracion y contenedor es `finalizeRecording` (B4b).
- `recordingCount` solo cuenta objetos finalizados con doc `ready`: las subidas abandonadas a medias (o los objetos
  que finalize aun no ha procesado) no consumen cupo, asi que su volumen solo lo acota la regla de ciclo de vida del
  bucket (`infra/storage-lifecycle.json`). Esa regla DEBE estar aplicada antes de `RECORDINGS_ENABLED=true` en
  cualquier proyecto real.

## 2b. Riesgos aceptados (revision B3a)

- **Claim `consentOk` obsoleto (W2):** la lectura del profesor mira el claim `consentOk`, no su doc (no queda acceso
  libre). Si el profesor revoca su consentimiento base, puede seguir leyendo hasta que se refresque su ID token
  (<= 1 h). Test: "riesgo aceptado W2" en `storage-recordings.test.ts`.
- **Demo sin consentimiento de grabacion en la lectura (W3):** `demoReads` no comprueba el `recordingConsent` del
  profesor ni del alumno (los 2 accesos ya se gastan en el doc de la grabacion y el vinculo). Revocar el del profesor
  borra sus demos (B5b). Test: "riesgo aceptado W3".

## 3. Despliegue

1. Conceder el IAM (seccion 1).
2. `firebase deploy --only storage --project <PROJECT_ID>`.
3. Smoke test de staging (seccion 1: subida, lectura del profesor y lectura de demo).
4. Aplicar `infra/storage-lifecycle.json` (slice posterior) con
   `gcloud storage buckets update gs://<BUCKET> --lifecycle-file=infra/storage-lifecycle.json`.
5. Solo despues, habilitar `RECORDINGS_ENABLED` segun el design §9.
