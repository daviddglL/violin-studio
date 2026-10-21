# Declaración de Data safety y política: micrófono (`RECORD_AUDIO`)

Estado: borrador de la fase 3 (slice C1), ampliado en la fase 4a (A11) para Google Play Console > Contenido de la app > Seguridad de los datos y para
el texto de la política de privacidad (`hosting/public/politica/index.html`, versión 2, pendiente de revisión legal).
Cubre REQ-AUD-05 de `practice-audio`.

## Resumen

La app usa el micrófono solo para el afinador, con la pantalla visible. El audio se procesa en memoria en el
dispositivo, se descarta al terminar cada ventana de análisis y NUNCA se guarda, se registra ni se envía.

## Declaración en Play Console

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Datos de audio: grabaciones de voz o sonido | **No recopilados, no compartidos** | El audio del micro solo se analiza en memoria (frecuencia, nota y cents para la pantalla); no sale del dispositivo ni se persiste |
| Datos de audio: archivos de música/audio | No recopilados | La app no lee ni guarda archivos de audio |
| ¿Se comparten datos con terceros? (audio) | No | Sin red en el pipeline de audio |
| Permiso `RECORD_AUDIO` | Declarado; solicitado en contexto al abrir el afinador | Nunca al arrancar la app |
| Servicios en primer plano de micrófono | Ninguno | Sin captura en segundo plano |
| Registros de fallos (crash logs) | Recopilados, vinculados a la app (no a datos de audio) | Crashlytics en el flavor prod |
| Diagnósticos y rendimiento de la app | Recopilados | Firebase Performance y Crashlytics |
| Identificadores de dispositivo u otros | Recopilados | Id de instalación de Firebase (Crashlytics, Performance); sin `AD_ID` (el manifiesto lo elimina) |
| Actividad en la app (analytics) | **No recopilada** | La app no incluye Google Analytics (retirado: no había consentimiento y hay menores de 14) |

Datos que sí trata la app (fase 2 y registro de práctica; ya cubiertos por el consentimiento): correo, nombre, fecha de
nacimiento (edad), instrumento, historial de práctica (fecha, duración, instrumento y notas opcionales) y registros de
consentimiento. El historial de práctica no contiene audio, frecuencias ni notas tocadas. Se puede borrar por sesión o
junto con la cuenta (borrado completo desde la app).

## Vínculo profesor-alumno, tareas y visibilidad del registro (fase 4a)

Cubre REQ-CON-R04. Declaración para Play Console de lo que añade el vínculo; el audio y el vídeo de las grabaciones
(fase 4b/4c) NO están incluidos aquí y se declararán cuando existan.

| Pregunta | Respuesta | Motivo |
|---|---|---|
| Datos personales: nombre, correo, fecha de nacimiento | Recopilados y, solo el nombre y el instrumento, **compartidos con el profesor vinculado** | El alumno elige vincularse canjeando el código y acepta un aviso versionado (`LinkNotice`) que lo explica |
| Actividad en la app: historial de práctica (fecha, duración, instrumento) y **notas del alumno** | Recopilada y **compartida con el profesor vinculado** (solo lectura) | Las reglas de Firestore solo dejan leer al profesor con vínculo vigente, consentimiento vigente y sin borrado en curso |
| Contenido generado por el usuario: tareas (título, descripción, fecha límite, estado y fecha de hecha) | Recopiladas; visibles para el profesor autor y el alumno | El profesor las crea y las edita; el alumno solo cambia el estado |
| ¿Se comparten datos con terceros? | No, salvo el profesor elegido por el propio usuario | Sin SDK de terceros para esto |
| Cifrado en tránsito | Sí | Firebase (TLS) |
| Borrado | El usuario desvincula al instante (el profesor pierde la vista y se borran las tareas abiertas) y puede borrar la cuenta completa | `unlinkTeacher` y `deleteAccount`; el borrado de un profesor desvincula a sus alumnos |

Controles técnicos relacionados (verificados por tests de reglas y de integración):

- El profesor solo existe por alta administrativa (`admin:grant-teacher`, adulto con consentimiento vigente); ninguna
  llamada del cliente asigna el rol.
- Un alumno tiene como máximo 1 profesor; un profesor, 30 alumnos y 5 códigos activos (20 en 24 h). Los códigos se
  guardan con HMAC, caducan a los 7 días y el canje tiene límite de 5 fallos por hora y alumno.
- El profesor no puede escribir ni borrar el registro del alumno.
- Un cambio de versión de la política (v2) devuelve a todos a `pending` (`admin:reevaluate-consents` lo aplica en
  bloque): el vínculo se conserva, pero el profesor no tiene acceso hasta que ambos vuelvan a consentir. Ninguna cuenta
  de menor se borra por el cambio.
- Los registros de Functions solo llevan hashes de uid y contadores; nunca nombres, notas ni códigos.

## Texto para la política de privacidad

> El afinador usa el micrófono de tu dispositivo solo mientras la pantalla del afinador está abierta. El sonido se
> analiza al instante en tu dispositivo para mostrar la nota y la afinación; no se graba, no se guarda y no se envía a
> ningún servidor ni a terceros. Puedes revocar el permiso en los ajustes del sistema en cualquier momento.

## Menores (D7)

No se añade ningún aviso específico para menores sobre el micrófono más allá del consentimiento de la fase 2: el audio
no se recopila ni se comparte, así que no altera el tratamiento de datos del menor ni del tutor.

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
  RGPD). **Falta la revisión legal** de ese texto antes de publicar en Play (riesgo Q1 abierto, puerta bloqueante en
  README).
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
