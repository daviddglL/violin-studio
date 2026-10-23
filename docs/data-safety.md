# Declaración de Data safety y política: micrófono (`RECORD_AUDIO`)

Estado: borrador de la fase 3, ampliado en las fases 4a, 4b y 4c y con los avisos push, para Google Play Console >
Contenido de la app > Seguridad de los datos y para el texto de la política de privacidad
(`hosting/public/politica/index.html`, versión 2, pendiente de revisión legal).

## Resumen

Sin las grabaciones (fase 4b), la app usa el micrófono solo para el afinador, con la pantalla visible. El audio se procesa en memoria en el
dispositivo, se descarta al terminar cada ventana de análisis y NUNCA se guarda, se registra ni se envía.

## Declaración en Play Console

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Datos de audio: grabaciones de voz o sonido (afinador) | **No recopilados, no compartidos** | El audio del micro solo se analiza en memoria (frecuencia, nota y cents para la pantalla); no sale del dispositivo ni se persiste |
| Datos de audio: archivos de música/audio | No recopilados | La app no lee ni guarda archivos de audio |
| ¿Se comparten datos con terceros? (audio) | No | Sin red en el pipeline de audio |
| Permiso `RECORD_AUDIO` | Declarado; solicitado en contexto al abrir el afinador (o la pantalla de grabaciones) | Nunca al arrancar la app |
| Servicios en primer plano de micrófono | Ninguno | Sin captura en segundo plano |
| Registros de fallos (crash logs) | Recopilados, vinculados a la app (no a datos de audio) | Crashlytics en el flavor prod |
| Diagnósticos y rendimiento de la app | Recopilados | Firebase Performance y Crashlytics |
| Identificadores de dispositivo u otros | Recopilados | Id de instalación de Firebase (Crashlytics, Performance); sin `AD_ID` (el manifiesto lo elimina) |
| Actividad en la app (analytics) | **No recopilada** | La app no incluye Google Analytics (retirado: no había consentimiento y hay menores de 14) |

Datos que sí trata la app (fase 2 y registro de práctica; ya cubiertos por el consentimiento): correo, nombre, fecha de
nacimiento (edad), instrumento, historial de práctica (fecha, duración, instrumento y notas opcionales), lecciones del
currículo marcadas como hechas (`users/{uid}/progress/curriculum`: solo los ids de lección) y registros de
consentimiento. El historial de práctica no contiene audio, frecuencias ni notas tocadas. Se puede borrar por sesión o
junto con la cuenta (borrado completo desde la app).

## Vínculo profesor-alumno, tareas y visibilidad del registro (fase 4a)

Declaración para Play Console de lo que añade el vínculo; las grabaciones de audio y vídeo tienen sus propias
secciones más abajo.

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Datos personales: nombre, correo, fecha de nacimiento | Recopilados y, solo el nombre y el instrumento, **compartidos con el profesor vinculado** | El alumno elige vincularse canjeando el código y acepta un aviso versionado (`LinkNotice`) que lo explica |
| Actividad en la app: historial de práctica (fecha, duración, instrumento), **notas del alumno** y lecciones del currículo hechas | Recopilada y **compartida con el profesor vinculado** (solo lectura) | Las reglas de Firestore solo dejan leer al profesor con vínculo vigente, consentimiento vigente y sin borrado en curso |
| Contenido generado por el usuario: tareas (título, descripción, fecha límite, estado y fecha de hecha) | Recopiladas; visibles para el profesor autor y el alumno | El profesor las crea y las edita; el alumno solo cambia el estado |
| ¿Se comparten datos con terceros? | No, salvo el profesor elegido por el propio usuario | Sin SDK de terceros para esto |
| Cifrado en tránsito | Sí | Firebase (TLS) |
| Borrado | El usuario desvincula al instante (el profesor pierde la vista y se borran las tareas abiertas) y puede borrar la cuenta completa | `unlinkTeacher` y `deleteAccount`; el borrado de un profesor desvincula a sus alumnos |

Controles técnicos relacionados (verificados por tests de reglas y de integración):

- El profesor solo existe por alta administrativa (`admin:grant-teacher`, adulto con consentimiento vigente); ninguna
  llamada del cliente asigna el rol. Desde la app un adulto solo puede pedirlo (`requestTeacherRole` marca
  `users/{uid}.teacherRequest` con estado y fechas, que las reglas no dejan escribir al cliente); el administrador
  lista las pendientes con `admin:teacher-requests` y las aprueba con `admin:grant-teacher` o las rechaza con
  `admin:reject-teacher` (30 días antes de poder volver a pedirlo).
- Un alumno tiene como máximo 1 profesor; un profesor, 30 alumnos y 5 códigos activos (20 en 24 h). Los códigos se
  guardan con HMAC, caducan a los 7 días y el canje tiene límite de 5 fallos por hora y alumno.
- El profesor no puede escribir ni borrar el registro del alumno.
- Un cambio de versión de la política (v2) devuelve a todos a `pending` (`admin:reevaluate-consents` lo aplica en
  bloque): el vínculo se conserva, pero el profesor no tiene acceso hasta que ambos vuelvan a consentir. Ninguna cuenta
  de menor se borra por el cambio.
- Los registros de Functions solo llevan hashes de uid y contadores; nunca nombres, notas ni códigos.

## Grabaciones de audio (fase 4b)

Se activa con `RECORDINGS_ENABLED` (y, para menores de 14 años, además `MINOR_RECORDING_ENABLED`). Lo que añade a la
declaración de Play Console:

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Audio: grabaciones de voz o sonido | Recopiladas y **compartidas solo con el profesor vinculado** (o, en una demo, con el alumno destinatario); opcionales | El usuario graba con su acción, con el consentimiento de grabación (el del tutor si tiene menos de 14 años) |
| ¿Se comparten con terceros? | No | Solo el profesor y el alumno del vínculo las oyen |
| Contenido generado por el usuario: revisiones del profesor | Recopiladas; visibles para el alumno y el profesor | Texto del profesor sobre una grabación |
| Cifrado en tránsito | Sí | Firebase (TLS) |
| Borrado | El usuario borra cada grabación; caducan a los 90 días; se borran al revocar el consentimiento, al desvincular (las demos) o al borrar la cuenta | `onRecordingDeleted`, `purgeRecordings`, `purgeRevokedRecordings` y el borrado de cuenta |

Controles técnicos:

- La toma vive en `noBackupFilesDir` (sin copia de seguridad; la app tiene `allowBackup="false"`) hasta que se sube;
  la cola de subida corre con WorkManager, solo con red y sin servicio en primer plano.
- Las reglas de Storage solo aceptan `.m4a` de hasta 25 MiB sellados con la época de consentimiento vigente;
  `finalizeRecording` mide la duración real (60 s alumno, 120 s profesor) y aplica el cupo de 20 por usuario.
- `ManifestPermissionsTest` limita la petición de `RECORD_AUDIO` a las rutas del afinador y de grabaciones.

## Chat profesor-alumno (fase 4c)

Se activa con `CHAT_ENABLED`. Lo que añade a la declaración de Play Console:

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Mensajes: otros mensajes en la app | Recopilados y **compartidos solo con la otra parte del vínculo**; opcionales | Texto de hasta 1000 caracteres, autor y hora; sin adjuntos |
| ¿Se comparten con terceros? | No | Solo el profesor y el alumno del vínculo los leen |
| Cifrado en tránsito | Sí | Firebase (TLS) |
| Borrado | El autor borra los suyos; caen al desvincular, al borrar la cuenta de cualquiera de los dos y a los 180 días | Paso `deleteChatMessages` de `removeLink`, borrado de cuenta y TTL de `expireAt` |

Controles técnicos: las reglas de Firestore solo dejan leer y escribir a las dos partes de un vínculo vivo, con email
verificado, consentimiento vigente y ambas cuentas activas; el esquema del mensaje es cerrado, sin edición, y
`expireAt` solo se acepta entre 170 y 181 días después de la escritura.

## Avisos push

Se activa con `NOTIFICATIONS_ENABLED` (apagado en producción sin variable; también se puede apagar en caliente con
`appConfig/features.notificationsEnabled`). Lo que añade a la declaración de Play Console:

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Identificadores del dispositivo u otros | Recopilado; **no compartido**; opcional | Token de FCM en `users/{uid}/devices/{sha256(token)}`, solo para enviar los avisos |
| ¿Se comparten con terceros? | No | Lo usa Firebase Cloud Messaging (encargado) para entregar el aviso |
| Contenido del aviso | Sin datos personales | Solo claves de texto (`push_chat_*`, `push_review_*`, `push_assignment_*`) y el tipo |
| Borrado | Al cerrar sesión, al borrar la cuenta y cuando FCM lo invalida | `unregisterDevice`, `recursiveDelete` del usuario y limpieza en `notifyUser` |

Controles técnicos: FCM arranca sin auto-inicio (`firebase_messaging_auto_init_enabled=false`), así que el token solo
existe si el servidor tiene los avisos encendidos y hay sesión; `POST_NOTIFICATIONS` solo se pide al pulsar «Activar
avisos»; las reglas niegan `devices` a cualquier cliente; un token se quita de otra cuenta al registrarlo (dispositivo
compartido) y hay como mucho 10 por usuario. Los avisos no se reintentan (un duplicado molesta más que uno perdido).

## Grabaciones de vídeo (fase 4c)

Se activa con `VIDEO_ENABLED`. Lo que añade el vídeo a la declaración de Play Console:

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Fotos y vídeos: vídeos | Recopilados y **compartidos solo con el profesor vinculado** (o, en una demo, con el alumno destinatario); opcionales | El usuario los graba con su acción, con el consentimiento de grabación (el del tutor si tiene menos de 14 años) |
| Audio: grabaciones de voz o sonido | Recopiladas dentro del vídeo, con las mismas condiciones | La pista de audio del vídeo |
| Permiso `CAMERA` | Declarado; solicitado en contexto al abrir la pantalla de vídeo | Nunca al arrancar la app; `uses-feature` de cámara no obligatorio |
| Diagnósticos de la app (ML Kit) | Recopilados por Google, no compartidos, cifrados en tránsito | Métricas de uso y rendimiento de ML Kit: dispositivo, versión de la app, tiempos, tamaños y códigos de error |
| Identificadores de dispositivo u otros (ML Kit) | Recopilados, no compartidos | Identificador por instalación de ML Kit, que no identifica al usuario ni al dispositivo |
| Cifrado en tránsito | Sí | Firebase (TLS) y ML Kit (HTTPS) |
| Borrado | El usuario borra cada vídeo; caducan a los 90 días; se borran al revocar el consentimiento o la cuenta | Las mismas purgas que el audio (fase 4b) |

Fuente de las métricas de ML Kit: <https://developers.google.com/ml-kit/android-data-disclosure> (consultada el
2026-10-22). Esa página no ofrece forma de desactivarlas.

Controles técnicos:

- Las caras se difuminan en el dispositivo para todas las edades, con un margen del 30 %; un fotograma sin cara
  detectada se difumina entero. Si el difuminado falla, el vídeo no se guarda.
- El original sin difuminar vive en almacenamiento interno sin copia de seguridad y se borra al terminar, al salir de
  la pantalla o en el siguiente arranque de una toma; nunca se sube.
- Las reglas de Storage y `finalizeRecording` solo aceptan `.mp4` de hasta 60 MiB sellados con `blurApplied` y
  `blurPolicyVersion`, y solo con `VIDEO_ENABLED`.
- La reproducción descarga el vídeo a memoria; no se escribe en disco.
- `PracticeAudioSourcesTest` prohíbe los logs en `feature/video` y `ManifestPermissionsTest` limita la petición de
  `CAMERA` a la pantalla de vídeo.

## Texto para la política de privacidad

> El afinador usa el micrófono de tu dispositivo solo mientras la pantalla del afinador está abierta. El sonido se
> analiza al instante en tu dispositivo para mostrar la nota y la afinación; no se graba, no se guarda y no se envía a
> ningún servidor ni a terceros. Puedes revocar el permiso en los ajustes del sistema en cualquier momento.

## Menores

El afinador no añade ningún aviso específico para menores más allá del consentimiento de la fase 2: su audio no se
recopila ni se comparte. Las grabaciones de un menor de 14 años necesitan además la autorización del tutor
(propósito `recording_sharing`) y el interruptor `MINOR_RECORDING_ENABLED`.

## Garantías verificadas por tests (CI)

- `PracticeAudioSourcesTest` (data): ningún fichero que persiste o transmite (Firebase, DataStore, `java.io`) referencia
  tipos de audio; `AudioRecordSource` sin ficheros, `MediaRecorder`, `Log` ni Crashlytics/Analytics; sin logs en el
  pipeline de audio.
- `PracticeAudioSignaturesTest` (data): `PracticeLogRepository` y `TunerConfigRepository` sin tipos de audio.
- `ManifestPermissionsTest` (app): `RECORD_AUDIO` declarado, sin permisos ni servicios de primer plano, y solicitud solo
  desde la ruta del afinador.
- Reglas de Firestore: el documento de práctica solo admite sus campos cerrados (sin campos de audio).

## Pendiente antes de publicar

- Confirmar en Play Console el propósito (análisis, estabilidad) y el cifrado en tránsito de cada fila de diagnósticos.
- La política v2 (`hosting/public/politica/index.html`, `CURRENT_POLICY_VERSION = 2`) es el borrador de la fase 4
  completa (vínculo, tareas, grabaciones de audio y vídeo, difuminado, retención de 90 días, cupo de 20) e incluye ya
  responsable y contacto, base jurídica, plazos de conservación, transferencias internacionales y derechos (art. 13
  RGPD). **Falta la revisión legal** de ese texto antes de publicar en Play (puerta bloqueante en el README).
- Declarar en Play Console las grabaciones de audio y vídeo cuando se publique la fase 4b/4c.
- Para la revisión legal (residuales conocidos del borrado de cuenta y la revocación, fase 4b):
  - Al borrar la cuenta de un profesor sus revisiones se **anonimizan** (sin uid en la ruta ni en los campos), pero el
    texto libre se conserva tal cual y puede nombrar al profesor (o a terceros) hasta que caduque o se borre la grabación
    del alumno (90 días como máximo). Una revisión cuya grabación ya no existe se borra, no se anonimiza.
  - Un objeto de demostración huérfano (sin documento, p. ej. una subida interrumpida) conserva en sus metadatos de
    Storage el uid del alumno destinatario (`sharedWith`) hasta que lo borra el barrido diario de huérfanos (objetos de
    24 h o más) o la regla de ciclo de vida del bucket (100 días); las demos con documento se borran con él.
  - Una revocación de grabación cuya purga no puede completarse (`revokePurge.stuck`, más de 3 días) requiere
    intervención manual según el runbook del README; hasta entonces las grabaciones afectadas siguen almacenadas y
    el usuario no puede volver a conceder la grabación.
