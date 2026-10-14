/**
 * Limites de las grabaciones (REQ-REC-01, REQ-REC-14). Fuente unica en Functions; `storage.rules` repite 25 MiB, 20 y
 * la version de consentimiento como literales y `test/unit/storage-rules-parity.test.ts` vigila que coincidan.
 */
export const MAX_AUDIO_BYTES = 25 * 1024 * 1024;
export const MAX_RECORDINGS_PER_OWNER = 20;
/**
 * Carpetas de objeto bajo `users/{uid}/`: grabaciones propias y demostraciones del profesor (C1: rutas disjuntas para
 * que cada `allow get` gaste <= 2 accesos a Firestore). El doc de ambas vive en `users/{uid}/recordings/{id}`.
 */
export const RECORDINGS_DIR = "recordings";
export const DEMOS_DIR = "demos";
