/**
 * Limites de las grabaciones (REQ-REC-01, REQ-REC-14). Fuente unica en Functions; `storage.rules` repite 25 MiB, 20 y
 * la version de consentimiento como literales y `test/unit/storage-rules-parity.test.ts` vigila que coincidan.
 */
export const MAX_AUDIO_BYTES = 25 * 1024 * 1024;
export const MAX_RECORDINGS_PER_OWNER = 20;
