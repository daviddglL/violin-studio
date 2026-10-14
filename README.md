# Violin Studio

App Android para aprender y practicar violín, pensada tanto para quien estudia por su cuenta como para
alumnos que trabajan con un profesor.

## Qué ofrece

- **Práctica diaria**: afinador, metrónomo, sesiones de estudio con estadísticas y un currículo de
  lecciones por niveles.
- **Profesor y alumno**: el alumno graba sus ejercicios, el profesor los revisa, deja comentarios y
  asigna tareas, y ambos hablan por chat.
- **Motivación**: logros, rachas y feedback sobre la práctica.
- **Privacidad desde el diseño**: cuentas con email o Google, consentimiento versionado,
  autorización del tutor para menores de 14 años y borrado completo de la cuenta y sus datos en
  cualquier momento.

## Estado

El desarrollo va por fases:

| Fase | Contenido | Estado |
|---|---|---|
| 1. Base | Arquitectura, Firebase, CI/CD, pantalla de comprobación del servidor | ✅ |
| 2. Identidad y privacidad | Registro, perfil, consentimiento, flujo del tutor, borrado de cuenta | En curso |
| 3. Práctica | Afinador, metrónomo, sesiones, estadísticas, currículo | Pendiente |
| 4. Profesor–alumno | Grabación, subida, tareas, revisión, chat | Pendiente |
| 5. Extras | Gamificación, feedback inteligente, configuración remota | Pendiente |

## Tecnología

- **Android**: Kotlin, Jetpack Compose con Material 3, Hilt, Navigation Compose y coroutines/Flow.
- **Backend**: Firebase (Auth, Firestore, Storage, App Check, Crashlytics, Performance) y Cloud
  Functions en TypeScript para la lógica que debe aplicarse en servidor.
- **Calidad**: JUnit 5, Turbine, MockK, Robolectric, capturas con Roborazzi, JaCoCo (umbral del 80 %),
  ktlint y tests de reglas de seguridad contra los emuladores de Firebase.
- **Entrega**: GitHub Actions y fastlane, que publica en Firebase App Distribution.

## Arquitectura

Clean Architecture en cuatro módulos:

```
app/     Application, App Check y configuración por flavor (dev/prod). Ensambla el resto.
ui/      → domain · MainActivity, navegación y feature/<nombre>/{view,viewmodel};
                      en commons/ el tema, los componentes compartidos y la base MVI.
domain/  (Kotlin puro) · feature/<nombre>/{model,repository,usecase,failure}.
data/    → domain · feature/<nombre>/{datasource,dto,repository,utils};
                      en commons/ la DI y la configuración de Firebase.
functions/  Cloud Functions (TypeScript).
```

- Los repositorios son interfaces en `domain`, implementadas en `data`. Cada caso de uso es una clase.
- Cada pantalla sigue MVI: `Contract` (State/Intent/Effect), `Reducer` puro, `ViewModel` que extiende
  `MviViewModel` y usa casos de uso, y `Screen` sin estado.
- Las reglas de Firestore y Storage deniegan todo por defecto. Cada funcionalidad abre solo lo que
  necesita, siempre con sus tests.

## Requisitos

JDK 21, Android SDK 36, Node 22 y Firebase CLI (`npm i -g firebase-tools`).

Los `google-services.json` no están en git: descárgalos de la consola de Firebase a `app/src/dev/` y
`app/src/prod/`. Dev y prod usan de momento el mismo proyecto, `violin-app-dev-f0b55`.

## Comandos

```bash
./gradlew check coverage                           # ktlint, lint, tests y cobertura
./gradlew :ui:recordRoborazziDebug                 # regrabar capturas
./gradlew :ui:verifyRoborazziDebug                 # comparar capturas
npm --prefix functions test                        # tests unitarios de Functions

# Tests de integración de Functions contra los emuladores
firebase emulators:exec --only functions,auth,firestore,storage \
  --project demo-violin-studio "npm --prefix functions run test:integration"

# Tests de las reglas de seguridad
firebase emulators:exec --only firestore,storage --project demo-violin-studio \
  "npm --prefix rules-tests test"

# E2E: arranca los emuladores y lanza los tests instrumentados
firebase emulators:start --only functions,auth,firestore,storage --project violin-app-dev-f0b55
./gradlew :app:connectedDevDebugAndroidTest

bash scripts/secrets-guard.sh                      # comprueba que no hay secretos en git
```

Para probar en un móvil físico contra los emuladores del PC:
`./gradlew installDevDebug -Pviolin.emulatorHost=<IP del PC>`. Los emuladores ya escuchan en
`0.0.0.0`, así que úsalo solo en una red de confianza.

## Despliegue

### Primer despliegue de Functions

1. Confirma que el proyecto (`violin-app-dev-f0b55`) está en el plan Blaze, que Functions requiere.
2. Crea `functions/.env.violin-app-dev-f0b55` con `ENFORCE_APP_CHECK=false`. No se versiona.
3. `npm --prefix functions run build`
4. `firebase deploy --only firestore:indexes --project violin-app-dev-f0b55` y espera a que los indices esten READY (el borrado de cuenta consulta `collectionGroup(assignments)`), luego `firebase deploy --only functions --project violin-app-dev-f0b55`
5. Verifica el despliegue con "Comprobar servidor" en la app.

### Publicar la fase 4a (política v2 y vínculo profesor-alumno)

Orden obligatorio (design §9). Cada paso termina antes de empezar el siguiente:

0. **Puerta bloqueante:** revisión legal del texto de la política v2 hecha (riesgo Q1). Sin ella no se publica nada de lo siguiente.
1. `firebase functions:secrets:set TEACHER_CODE_PEPPER` (>= 32 caracteres, distinto de `GUARDIAN_EMAIL_PEPPER`; guárdalo fuera del repo).
2. `firebase deploy --only firestore:rules,firestore:indexes` y espera a que los índices estén READY.
3. `firebase deploy --only hosting`: publica la política v2 ANTES que las Functions (el texto debe existir cuando alguien vea la v2).
4. `firebase deploy --only functions`: `CURRENT_POLICY_VERSION = 2` empieza a regir; cada usuario `granted` con la v1 pasa a `pending` al abrir la app.
5. Reevaluación masiva (cierra la ventana en la que un alumno vinculado sigue `granted` con la v1 y su profesor aún lee su registro):
   `GCLOUD_PROJECT=<id> CONFIRM_PROJECT=<id> npm --prefix functions run admin:reevaluate-consents` con credenciales de administración. Es idempotente; cada fallo se reintenta una vez en la pasada y devuelve 1 si algún usuario sigue fallando. Repetirla solo reintenta a quienes siguen `granted` con la v1: si el fallo fue de `syncClaims` tras el commit, el claim `consentOk` obsoleto se cura en el siguiente `identityConfig` de ese usuario, no al repetir la pasada.
6. Distribuye la app y pasa el checklist manual en dispositivo (A11.6 de tasks.md).

Ninguna cuenta de menor se borra por el cambio de versión: vuelven a `pending` y el tutor debe confirmar de nuevo.

### Publicar la fase 4b (grabaciones)

Antes de `firebase deploy --only functions`, `functions/.env.<project-id>` (no versionado) debe definir los tres interruptores: `RECORDINGS_ENABLED=true`, `VIDEO_ENABLED=...` y `MINOR_RECORDING_ENABLED=...`. Sin ellos (o con cualquier valor distinto de `true`) las grabaciones quedan apagadas en producción.

`finalizeRecording` es un trigger de Storage y debe desplegarse en la region del bucket (spike Q5): por defecto `europe-west1` (la de las demas funciones); si el bucket esta en otra, define `STORAGE_TRIGGER_REGION=<region>` en el mismo `.env.<project-id>`. Debe ser una region de Functions (`europe-west3`, `us-central1`...): un valor con otra forma hace FALLAR la carga del codigo (no hay caida silenciosa a `europe-west1`), y las multirregiones (`EU`, `US`, `ASIA`) no valen: con un bucket multirregion usa una region miembro (p. ej. `europe-west1` para `EU`). El proyecto de desarrollo aun no tiene Firestore/Storage habilitados, asi que la ubicacion real hay que comprobarla (`gcloud storage buckets describe gs://<bucket> --format="value(location)"`) ANTES del primer despliegue; un trigger en otra region falla al desplegar. El smoke test del paso 2 incluye comprobar que una subida valida acaba en un doc `ready`.

Orden (detalle en `docs/runbooks/storage-rules-iam.md`):

1. IAM del agente de servicio de Storage y `firebase deploy --only storage`. Consecuencia del orden: desde que estas reglas estan vivas, una subida sin el metadato `recordingConsentEpoch` (o con una epoca que no es la vigente) se deniega, asi que un cliente anterior a B6 no puede subir grabaciones. Es inocuo mientras `RECORDINGS_ENABLED` siga apagado (no hay subidas legitimas); no se activa hasta que el cliente B6 envie el sello.
2. **Puerta de release (obligatoria, no consultiva)**: smoke test en staging ANTES de poner `RECORDINGS_ENABLED=true`. Debe pasar completo y sin excepciones, y su resultado se anota en la PR/release: las dos rutas de lectura (`get` del dueno/profesor vinculado en `users/{uid}/recordings/` y `get` del alumno destinatario en `users/{t}/demos/`) y las dos de creacion (subida de una grabacion y de una demo). El emulador no aplica el tope de 2 accesos a Firestore de las reglas de Storage ni el IAM del agente de servicio, asi que solo este smoke test los valida. Si falla cualquiera de los cuatro, `RECORDINGS_ENABLED` NO se activa.
3. Regla de ciclo de vida del bucket aplicada (`gcloud storage buckets update gs://<bucket> --lifecycle-file=infra/storage-lifecycle.json`: borra `.m4a`/`.mp4` bajo `users/` a los 100 dias): `recordingCount` solo cuenta grabaciones finalizadas, así que las subidas abandonadas solo las acota esa regla (y el barrido de huerfanos). 100 d porque `age` cuenta desde la creacion del objeto y la caducidad de 90 d desde finalize, que nunca escribe `ready` para un objeto de 20 h o mas: queda una semana larga para que la purga diaria (con presupuesto) borre doc y objeto antes que la regla, que es solo la red de seguridad. `matchesPrefix: users/` impide que borre nada fuera de los espacios de usuario.
4. **Prerrequisito duro: B5a** desplegado y verificado: `onRecordingDeleted` (al borrar el doc: borra el objeto de Storage por el `objectPath` guardado, hace la cascada de `reviews` y devuelve la plaza del cupo), `purgeRecordings` (diaria 03:30: caducadas + reconciliacion de `recordingCount`) y `sweepRecordingOrphans` (diaria 04:30: objetos sin doc de mas de 24 h; `teacherReads` de `storage.rules` no exige doc, asi que este barrido y la regla de 100 d son la cota de un huerfano). La purga y el barrido son reanudables: guardan su cursor en `maintenance/{recordingReconcile,sweepRecordingOrphans}` (solo servidor) y, si el presupuesto de 480 s se agota, avisan con `purgeRecordings.budgetExhausted` / `sweepOrphans.budgetExhausted` a nivel WARN (crea una alerta de log sobre esos mensajes). Sin ellos, cuando el alumno borra el documento el objeto sigue legible por el profesor vinculado o el destinatario. Despliega tambien `firestore.indexes.json` (indice COLLECTION_GROUP de `recordings.status` que usa la reconciliacion y la politica TTL de `recordingCountLedger.expireAt`, las marcas de idempotencia del decremento del cupo que deja `onRecordingDeleted`) y `firestore.rules` (deny total de `recordingCountLedger` y `maintenance`), y comprueba el resultado del smoke test del paso 2. `RECORDINGS_ENABLED` NO se activa sin B5a. Anota el resultado en la PR/checklist de release. Operacion: `npm --prefix functions run admin:recount-recordings -- [<uid>] [--yes]` recalcula los contadores a mano (un borrado en vuelo no baja de mas: la reparacion sella `recordingCountReconciledAt` y el trigger de un borrado anterior ya no descuenta). Abandono de eventos envenenados: si `finalizeRecording` sigue fallando por algo que no es el contenedor (Storage/Firestore caidos, un bug) durante 6 h desde el evento, deja de reintentar y escribe `rejected(internal)` borrando el objeto (y si llega un exito tardio con el objeto de 20 h o mas, tambien `rejected(internal)`): tras una caida larga esas subidas se pierden y el usuario debe volver a subirlas; busca `reason: internal` en los logs de `finalizeRecording` despues de un incidente.
5. Solo entonces `RECORDINGS_ENABLED=true` y `firebase deploy --only functions`.

### App Check en prod

Play Integrity solo valida apps instaladas desde Google Play. Mientras la app se reparta por App
Distribution, mantén `ENFORCE_APP_CHECK=false` en `functions/.env.violin-app-dev-f0b55`. Firebase
carga `.env.<project-id>` según el proyecto activo; cambia el nombre cuando exista un proyecto de
producción propio.

### Releases

Cada push a `main` firma el APK y el AAB y publica en App Distribution. El job necesita estos
secretos en el repositorio:

| Secreto / variable | Contenido |
|---|---|
| `GOOGLE_SERVICES_DEV_BASE64`, `GOOGLE_SERVICES_PROD_BASE64` | `google-services.json` en base64 |
| `KEYSTORE_BASE64` | Keystore de subida en base64 |
| `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Credenciales del keystore |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Cuenta de servicio para App Distribution |
| `FIREBASE_APP_ID_PROD` (variable) | App ID de Firebase de prod |
