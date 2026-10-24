# Violin Studio

App Android para aprender y practicar violín, pensada tanto para quien estudia por su cuenta como para
alumnos que trabajan con un profesor.

## Qué ofrece

- **Práctica diaria**: afinador, metrónomo, registro de sesiones con notas, estadísticas (rachas, semana, últimos
  30 días y gráfico de los últimos 7 días), 10 logros calculados en el dispositivo y un currículo de 12 lecciones en
  3 niveles que enlaza con el afinador y el metrónomo (el progreso se guarda en la nube y sobrevive a un cambio de móvil).
- **Feedback de afinación**: en las lecciones de escalas y posiciones, un ejercicio escucha cada nota, la puntúa en cents,
  dice qué notas tiendes a tocar altas o bajas y guarda tu mejor puntuación en el móvil. Todo se analiza en el
  dispositivo, sin grabar.
- **Profesor y alumno**: el alumno canjea un código de su profesor; el profesor ve su registro de práctica y sus
  lecciones hechas, le asigna tareas que el alumno marca como hechas y ambos pueden escribirse por chat. Un adulto puede
  pedir desde la app que le den de alta como profesor.
- **Avisos**: mensaje de chat, revisión del profesor y tarea nueva llegan como notificación push, sin contenido.
- **Grabaciones**: el alumno graba audio o vídeo de su práctica y el profesor lo revisa; el profesor puede mandar
  demostraciones. En los vídeos las caras se difuminan en el móvil antes de guardarlos. La subida sigue en segundo
  plano cuando hay red.
- **Privacidad desde el diseño**: cuentas con email o Google, consentimiento versionado, autorización del tutor para
  menores de 14 años, borrado completo de la cuenta y sus datos en cualquier momento y sin analítica ni identificador
  de publicidad.

Las grabaciones, el vídeo, el chat y los avisos están hechos pero apagados por interruptores del servidor, que además se
pueden apagar en caliente sin desplegar (ver [Interruptores en caliente](#interruptores-en-caliente)). Próximamente:
consejos de práctica según tu registro.

## Estado

El desarrollo va por fases:

| Fase | Contenido | Estado |
|---|---|---|
| 1. Base | Arquitectura, Firebase, CI/CD, pantalla de comprobación del servidor | ✅ |
| 2. Identidad y privacidad | Registro, perfil, consentimiento, flujo del tutor, borrado de cuenta | ✅ |
| 3. Práctica | Afinador, metrónomo, sesiones, estadísticas, currículo | ✅ |
| 4a. Profesor–alumno | Vínculo por código, registro compartido, tareas, solicitud de alta de profesor | ✅ (sin desplegar) |
| 4b. Grabaciones | Consentimiento, subida en segundo plano, revisión, demostraciones, purgas | ✅ (apagadas hasta activar `RECORDINGS_ENABLED`) |
| 4c. Vídeo y chat | Vídeo con difuminado, chat | ✅ (apagados hasta activar `VIDEO_ENABLED` y `CHAT_ENABLED`) |
| 5. Extras | Logros, configuración remota, avisos push, feedback inteligente | Logros, configuración remota y avisos ✅ (avisos apagados hasta activar `NOTIFICATIONS_ENABLED`); feedback de afinación ✅; consejos de práctica pendientes |

## Tecnología

- **Android**: Kotlin, Jetpack Compose con Material 3, Hilt, Navigation Compose, coroutines/Flow y WorkManager
  (cola de subida de grabaciones).
- **Backend**: Firebase (Auth, Firestore, Storage, App Check, Cloud Messaging, Crashlytics, Performance) y Cloud
  Functions en TypeScript para la lógica que debe aplicarse en servidor.
- **Calidad**: JUnit 5, Turbine, MockK, Robolectric, capturas con Roborazzi, JaCoCo (umbral del 80 %),
  ktlint, tests de reglas de seguridad contra los emuladores de Firebase y tests E2E instrumentados (práctica,
  menores, profesor–alumno, chat, subida de grabaciones y borrado de cuenta) que bloquean el CI si fallan.
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

JDK 21, Android SDK 37, Node 22 y Firebase CLI (`npm i -g firebase-tools`).

Los `google-services.json` no están en git: descárgalos de la consola de Firebase a `app/src/dev/` y
`app/src/prod/`. Dev y prod usan de momento el mismo proyecto, `violin-app-dev-f0b55`.

## Comandos

```bash
./gradlew check coverage                           # ktlint, lint, tests y cobertura
./gradlew :ui:recordRoborazziDebug                 # regrabar capturas
./gradlew :ui:verifyRoborazziDebug                 # comparar capturas
npm --prefix functions test                        # tests unitarios de Functions

# Tests de integración de Functions contra los emuladores
firebase emulators:exec --only functions,auth,firestore,storage,hosting \
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

Antes de abrir la app fuera del grupo de testers, sigue [`docs/runbooks/lanzamiento-produccion.md`](docs/runbooks/lanzamiento-produccion.md):
cuenta de servicio de CI con permisos mínimos, proyecto de producción propio, remitente real para el correo del tutor y
App Check.

### Primer despliegue de Functions

1. Confirma que el proyecto (`violin-app-dev-f0b55`) está en el plan Blaze, que Functions requiere.
2. Crea `functions/.env.violin-app-dev-f0b55` con `ENFORCE_APP_CHECK=false`. No se versiona.
3. `npm --prefix functions run build`
4. `firebase deploy --only firestore:indexes --project violin-app-dev-f0b55` y espera a que los indices esten READY (el borrado de cuenta consulta `collectionGroup(assignments)`), luego `firebase deploy --only functions --project violin-app-dev-f0b55`
5. Verifica el despliegue con "Comprobar servidor" en la app.

### Publicar la fase 4a (política v2 y vínculo profesor-alumno)

Orden obligatorio. Cada paso termina antes de empezar el siguiente:

0. **Puerta bloqueante:** revisión legal del texto de la política v2 hecha. Sin ella no se publica nada de lo siguiente.
1. `firebase functions:secrets:set TEACHER_CODE_PEPPER` (>= 32 caracteres, distinto de `GUARDIAN_EMAIL_PEPPER`; guárdalo fuera del repo).
2. `firebase deploy --only firestore:rules,firestore:indexes` y espera a que los índices estén READY.
3. `firebase deploy --only hosting`: publica la política v2 ANTES que las Functions (el texto debe existir cuando alguien vea la v2).
4. `firebase deploy --only functions`: `CURRENT_POLICY_VERSION = 2` empieza a regir; cada usuario `granted` con la v1 pasa a `pending` al abrir la app.
5. Reevaluación masiva (cierra la ventana en la que un alumno vinculado sigue `granted` con la v1 y su profesor aún lee su registro):
   `GCLOUD_PROJECT=<id> CONFIRM_PROJECT=<id> npm --prefix functions run admin:reevaluate-consents` con credenciales de administración. Es idempotente; cada fallo se reintenta una vez en la pasada y devuelve 1 si algún usuario sigue fallando. Repetirla solo reintenta a quienes siguen `granted` con la v1: si el fallo fue de `syncClaims` tras el commit, el claim `consentOk` obsoleto se cura en el siguiente `identityConfig` de ese usuario, no al repetir la pasada.
6. Distribuye la app y prueba en un dispositivo el recorrido completo: el profesor crea un código, el alumno lo canjea
   y acepta el aviso, el profesor ve su registro y le asigna una tarea, el alumno la marca como hecha y desvincula.

Ninguna cuenta de menor se borra por el cambio de versión: vuelven a `pending` y el tutor debe confirmar de nuevo.

### Publicar la fase 4b (grabaciones)

Antes de `firebase deploy --only functions`, `functions/.env.<project-id>` (no versionado) debe definir los tres interruptores: `RECORDINGS_ENABLED=true`, `VIDEO_ENABLED=...` y `MINOR_RECORDING_ENABLED=...`. Sin ellos (o con cualquier valor distinto de `true`) las grabaciones quedan apagadas en producción.

`finalizeRecording` es un trigger de Storage y debe desplegarse en la region del bucket: por defecto `europe-west1` (la de las demas funciones); si el bucket esta en otra, define `STORAGE_TRIGGER_REGION=<region>` en el mismo `.env.<project-id>`. Debe ser una region de Functions (`europe-west3`, `us-central1`...): un valor con otra forma hace FALLAR la carga del codigo (no hay caida silenciosa a `europe-west1`), y las multirregiones (`EU`, `US`, `ASIA`) no valen: con un bucket multirregion usa una region miembro (p. ej. `europe-west1` para `EU`). Comprueba la ubicacion real del bucket (`gcloud storage buckets describe gs://<bucket> --format="value(location)"`) ANTES del primer despliegue en cada proyecto; un trigger en otra region falla al desplegar. El smoke test del paso 2 incluye comprobar que una subida valida acaba en un doc `ready`.

Orden (detalle en `docs/runbooks/storage-rules-iam.md`):

1. IAM del agente de servicio de Storage y `firebase deploy --only storage`. Consecuencia del orden: desde que estas reglas estan vivas, una subida sin el metadato `recordingConsentEpoch` (o con una epoca que no es la vigente) se deniega, asi que una version de la app anterior a las grabaciones no puede subir. Es inocuo mientras `RECORDINGS_ENABLED` siga apagado (no hay subidas legitimas); la app actual ya envia el sello.
2. **Puerta de release (obligatoria, no consultiva)**: smoke test en staging ANTES de poner `RECORDINGS_ENABLED=true`. Debe pasar completo y sin excepciones, y su resultado se anota en la PR/release: las dos rutas de lectura (`get` del dueno/profesor vinculado en `users/{uid}/recordings/` y `get` del alumno destinatario en `users/{t}/demos/`) y las dos de creacion (subida de una grabacion y de una demo). El emulador no aplica el tope de 2 accesos a Firestore de las reglas de Storage ni el IAM del agente de servicio, asi que solo este smoke test los valida. Si falla cualquiera de los cuatro, `RECORDINGS_ENABLED` NO se activa.
3. Regla de ciclo de vida del bucket aplicada (`gcloud storage buckets update gs://<bucket> --lifecycle-file=infra/storage-lifecycle.json`: borra `.m4a`/`.mp4` bajo `users/` a los 100 dias): `recordingCount` solo cuenta grabaciones finalizadas, así que las subidas abandonadas solo las acota esa regla (y el barrido de huerfanos). 100 d porque `age` cuenta desde la creacion del objeto y la caducidad de 90 d desde finalize, que nunca escribe `ready` para un objeto de 20 h o mas: queda una semana larga para que la purga diaria (con presupuesto) borre doc y objeto antes que la regla, que es solo la red de seguridad. `matchesPrefix: users/` impide que borre nada fuera de los espacios de usuario.
4. **Prerrequisito duro: purgas y borrado en cascada** desplegados y verificados: `onRecordingDeleted` (al borrar el doc: borra el objeto de Storage por el `objectPath` guardado, hace la cascada de `reviews` y devuelve la plaza del cupo), `purgeRecordings` (diaria 03:30: caducadas + reconciliacion de `recordingCount`) y `sweepRecordingOrphans` (diaria 04:30: objetos sin doc de mas de 24 h; `teacherReads` de `storage.rules` no exige doc, asi que este barrido y la regla de 100 d son la cota de un huerfano). La purga y el barrido son reanudables: guardan su cursor en `maintenance/{recordingReconcile,sweepRecordingOrphans}` (solo servidor) y, si el presupuesto de 480 s se agota, avisan con `purgeRecordings.budgetExhausted` / `sweepOrphans.budgetExhausted` a nivel WARN (crea una alerta de log sobre esos mensajes). Sin ellos, cuando el alumno borra el documento el objeto sigue legible por el profesor vinculado o el destinatario. Despliega tambien `firestore.indexes.json` (indice COLLECTION_GROUP de `recordings.status` que usa la reconciliacion y la politica TTL de `recordingCountLedger.expireAt`, las marcas de idempotencia del decremento del cupo que deja `onRecordingDeleted`) y `firestore.rules` (deny total de `recordingCountLedger` y `maintenance`), y comprueba el resultado del smoke test del paso 2. `RECORDINGS_ENABLED` NO se activa sin ellos. Anota el resultado en la PR/checklist de release. Operacion: `npm --prefix functions run admin:recount-recordings -- [<uid>] [--yes]` recalcula los contadores a mano (un borrado en vuelo no baja de mas: la reparacion sella `recordingCountReconciledAt` y el trigger de un borrado anterior ya no descuenta). Abandono de eventos envenenados: si `finalizeRecording` sigue fallando por algo que no es el contenedor (Storage/Firestore caidos, un bug) durante 6 h desde el evento, deja de reintentar y escribe `rejected(internal)` borrando el objeto (y si llega un exito tardio con el objeto de 20 h o mas, tambien `rejected(internal)`): tras una caida larga esas subidas se pierden y el usuario debe volver a subirlas; busca `reason: internal` en los logs de `finalizeRecording` despues de un incidente.
5. **Prerrequisito duro: revocacion completa y borrado de cuenta con grabaciones**: `revokeRecordingConsent`, `revokeConsent` (politica) y la revocacion del tutor borran de inmediato objeto, doc y revisiones de todas las grabaciones del usuario (y las demos del profesor) con `purgeRevokedRecordings`, acotada por epoca y reanudable (un fallo no deshace la revocacion: `recordingPurge.pending` sigue y lo completa la propia llamada repetida, incluido el reintento del callable o del enlace del tutor tras un error posterior al commit, o el paso 2 de `purgeRecordings`, diaria 03:30, que corre ANTES de las caducadas, cursor `maintenance/recordingPurgePending`; en los callables la purga tiene un presupuesto de 45 s y, si se agota, la revocacion responde normal y el resto queda para la diaria; alerta de log sobre `purgeRecordings.budgetExhausted`, `revokePurge.itemFailed`, `revokePurge.anomaly` (WARN: grabaciones de una epoca posterior a la revocada mientras la purga sigue pendiente) y, sobre todo, `revokePurge.stuck` (ERROR)). Mientras la purga siga pendiente, volver a conceder la grabacion (`grantRecordingConsent`) falla con `TEMPORARILY_UNAVAILABLE` (reintentable) y la aceptacion del tutor de una solicitud de grabacion responde 503 con `Retry-After`: no se puede re-consentir hasta que el borrado termine. **Runbook `revokePurge.stuck`** (purga de revocacion incompleta con `recordingPurge.since` de hace mas de 3 dias, `STUCK_PURGE_AFTER_MS`): con el `uidHash` del log, localiza el fallo en los `revokePurge.itemFailed` (categoria y codigo: objeto, listado, doc), corrige la causa (permisos o bucket de la cuenta de servicio, objeto con retencion, doc corrupto) y deja que la purga diaria lo complete; si no puede completarse sola, borra a mano los objetos de `users/<uid>/recordings/` y `users/<uid>/demos/` y los docs `ready` de la epoca revocada y cierra con `GCLOUD_PROJECT=<id> npm --prefix functions run admin:complete-recording-purge -- <uid> [--yes]`, que comprueba en una transaccion que no quedan docs `ready` dentro de la epoca de la purga (si quedan responde `RECORDINGS_REMAIN` y no escribe; `--force` cierra igual y deja `recordingPurge.forced: true` como rastro) y sella `completedBy: "admin"`; no comprueba Storage, asi que verifica a mano que no quedan objetos bajo esos prefijos. Hasta ese cierre el usuario no puede volver a conceder la grabacion. El borrado de cuenta borra las demos dirigidas al alumno (con su objeto de `demos/`), anonimiza las revisiones del profesor (id aleatorio, `reviewerUid: null`, texto conservado) y desvincular (o `revoke-teacher`) borra las demos del profesor para ese alumno: `npm run admin:revoke-teacher` ahora necesita credenciales con acceso al bucket de Storage ademas de Firestore/Auth, y exige `STORAGE_BUCKET=<bucket>` (o `storageBucket` en `FIREBASE_CONFIG`; nunca se adivina `<proyecto>.appspot.com`): sin el responde 2 sin actuar. Un borrado de objeto contra un bucket inexistente ya no cuenta como "ya borrado" (falla con `bucket-not-found`). Despliega tambien `firestore.indexes.json` (indices COLLECTION_GROUP de `recordings.sharedWith` y `reviews.reviewerUid`: sin ellos la cascada del borrado de cuenta falla con FAILED_PRECONDITION) y comprueba en staging que la consulta de pendientes del paso 2 (`users` con `recordingPurge.pending == true` por rango de id de documento) no pide un indice compuesto nuevo; si lo pide, la consola da el enlace de creacion.
6. Solo entonces `RECORDINGS_ENABLED=true` y `firebase deploy --only functions`.

En el móvil, la cola de subida corre con WorkManager (`UploadQueueWorker`, trabajo único con red como requisito): sigue
al salir de la app y tras reiniciar el dispositivo, sin servicio en primer plano.


### Vídeo con difuminado (fase 4c)

El alumno graba su práctica (y el profesor, demostraciones para un alumno) con la cámara frontal. Las caras se difuminan
en el móvil con ML Kit y Media3 antes de guardar, para todas las edades y con un margen del 30 %; un fotograma sin cara
detectada se difumina entero. El original sin difuminar se borra y nunca se sube: el servidor solo acepta `.mp4`
sellados con `blurApplied` y `blurPolicyVersion`. Límites: 60 s para alumnos y 120 s para profesores, 720p y 60 MB.
Los vídeos se reproducen desde memoria, sin escribirse en disco.

El botón de vídeo solo aparece con `VIDEO_ENABLED=true` en `functions/.env.<project-id>`, que además requiere
`RECORDINGS_ENABLED=true` (fase 4b). Antes de activarlo en producción:

1. Publica (`firebase deploy --only hosting`) la política que describe el difuminado y las métricas de ML Kit.
2. Declara en Play Console el vídeo y las métricas de ML Kit según [`docs/data-safety.md`](docs/data-safety.md).
3. Prueba en un dispositivo real que la grabación, el difuminado, la subida y la reproducción funcionan con
   `VIDEO_ENABLED=true` en el proyecto de desarrollo.
4. Solo entonces `VIDEO_ENABLED=true` y `firebase deploy --only functions`.

### Alta de profesor

Un adulto con el email verificado y el consentimiento al día pide ser profesor desde inicio, «Quiero dar clase» (`requestTeacherRole`):
solo se marca `users/{uid}.teacherRequest` como `pending`, nunca el rol. El administrador decide:

```bash
GCLOUD_PROJECT=<id> npm --prefix functions run admin:teacher-requests -- [--yes]        # uid y fecha de las pendientes
GCLOUD_PROJECT=<id> npm --prefix functions run admin:grant-teacher -- <uid> [--yes]     # aprueba (mismo alta de siempre)
GCLOUD_PROJECT=<id> npm --prefix functions run admin:reject-teacher -- <uid> [--yes]    # rechaza; 30 días para volver a pedirlo
```

La app ve el cambio de rol al refrescar la sesión (al volver a primer plano).

### Avisos push

Mensaje de chat, revisión del profesor y tarea nueva avisan al destinatario (triggers `notifyChatMessage`,
`notifyReview`, `notifyAssignment`) con FCM. El aviso no lleva contenido: solo claves de texto que traduce la app.
Interruptor `NOTIFICATIONS_ENABLED` en `functions/.env.<project>` (techo) y `appConfig/features.notificationsEnabled`
para apagarlo en caliente. Encenderlo en producción solo cuando la política publicada incluya la sección «Avisos en el
móvil». La app registra el token (`registerDevice`) al entrar con el interruptor encendido, lo da de baja al cerrar
sesión (`unregisterDevice`) y pide `POST_NOTIFICATIONS` (Android 13+) solo con el botón «Activar avisos» de inicio.

### Chat profesor–alumno (fase 4c)

Los mensajes viven en `chatMessages` (reglas, índice compuesto y TTL de `expireAt` en `firestore.rules` y `firestore.indexes.json`): solo las dos partes de un vínculo vivo los leen y escriben, y se borran al desvincular, al borrar la cuenta de cualquiera de las dos y a los 180 días. La app solo muestra el chat con `CHAT_ENABLED=true` en `functions/.env.<project-id>`. Antes de activarlo en producción, publica (`firebase deploy --only hosting`) la política que describe el chat, declara los mensajes en Play Console según [`docs/data-safety.md`](docs/data-safety.md) y despliega `firestore.rules` y `firestore.indexes.json` (con la política TTL de `chatMessages.expireAt`).

### Interruptores en caliente

`RECORDINGS_ENABLED`, `VIDEO_ENABLED`, `MINOR_RECORDING_ENABLED`, `CHAT_ENABLED` y `NOTIFICATIONS_ENABLED` del
`.env.<project-id>` son el techo: lo que está apagado ahí sigue apagado. Para apagar algo encendido sin redesplegar, crea
en la consola de Firestore el documento `appConfig/features` con el campo a `false` (`recordingsEnabled`,
`videoEnabled`, `minorRecordingEnabled`, `chatEnabled` o `notificationsEnabled`).
Un campo ausente o a `true` deja el valor del `.env`; cualquier otro valor apaga. Cada instancia de Functions relee el
documento como mucho una vez por minuto, así que el cambio tarda hasta un minuto en llegar. Los clientes no pueden leerlo:
la app recibe los interruptores ya combinados por `identityConfig`.

Para activar una función de forma escalonada: crea antes el campo a `false`, despliega con la variable a `true` en el
`.env` y, cuando quieras abrirla, borra el campo o ponlo a `true`.

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
