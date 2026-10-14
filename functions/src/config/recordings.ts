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
/** Duracion maxima por rol (REQ-REC-01); el servidor la mide con `mvhd` (A11/R4), no la declarada. */
export const MAX_STUDENT_DURATION_SEC = 60;
export const MAX_TEACHER_DURATION_SEC = 120;
/**
 * Margen sobre el limite para el redondeo del contenedor (`MediaRecorder` con `setMaxDuration` puede pasarse unas
 * decimas). Debe ser < 1 s para que 61 s y 121 s se rechacen (REQ-REC-01, R4). El diseno decia 2 s, que aceptaria 61 s:
 * conflicto registrado en apply-progress B4a; cambiar aqui si producto decide otra cosa.
 */
export const DURATION_TOLERANCE_SEC = 0.5;
/** Duracion minima aceptada (W2): descarta 0 s (p. ej. MP4 fragmentado, cuyo `mvhd` trae duracion 0) y audios vacios. */
export const MIN_DURATION_SEC = 0.5;
/**
 * Tasa de bits maxima aceptada, `size * 8 / max(duracion, 1)` (W1). La duracion del `mvhd` la declara el cliente y es
 * falsificable: esto es defensa en profundidad junto al tope de tamano, no una garantia. AAC de Android = 64-128 kbps;
 * 1 Mbps deja ~8x de margen (VBR, metadatos) y rechaza 25 MB declarando 1 s (200 Mbps).
 */
export const MAX_BITRATE_BPS = 1_000_000;
/** Marcas mayores de `ftyp` aceptadas para audio MP4 (W5): ISO base, MP4 v1/v2 y M4A/M4B de iTunes. */
export const ALLOWED_BRANDS: readonly string[] = [
  "isom",
  "iso2",
  "mp41",
  "mp42",
  "M4A ",
  "M4B ",
];
