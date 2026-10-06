# Declaración de Data safety y política: micrófono (`RECORD_AUDIO`)

Estado: borrador de la fase 3 (slice C1) para Google Play Console > Contenido de la app > Seguridad de los datos y para
el texto definitivo de la política de privacidad (`hosting/public/politica/index.html`, hoy marcador provisional).
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
| Identificadores de dispositivo u otros | Recopilados | Instalación de Firebase (Analytics, Crashlytics, Performance) |
| Actividad en la app (analytics) | Recopilada | Firebase Analytics; nunca recibe frecuencias, notas ni cents (REQ-AUD-03) |

Datos que sí trata la app (fase 2 y registro de práctica; ya cubiertos por el consentimiento): correo, nombre, fecha de
nacimiento (edad), instrumento, historial de práctica (fecha, duración, instrumento y notas opcionales) y registros de
consentimiento. El historial de práctica no contiene audio, frecuencias ni notas tocadas. Se puede borrar por sesión o
junto con la cuenta (borrado completo desde la app).

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
- Sustituir el marcador de la política de privacidad por el texto legal definitivo (y subir `CURRENT_POLICY_VERSION`).
