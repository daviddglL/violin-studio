import {
  ALLOWED_BRANDS,
  ALLOWED_VIDEO_BRANDS,
  BLUR_POLICY_VERSION,
  DEMOS_DIR,
  DURATION_TOLERANCE_SEC,
  MAX_AUDIO_BYTES,
  MAX_BITRATE_BPS,
  MAX_STUDENT_DURATION_SEC,
  MAX_TEACHER_DURATION_SEC,
  MAX_VIDEO_BITRATE_BPS,
  MAX_VIDEO_BYTES,
  MIN_DURATION_SEC,
  RECORDINGS_DIR,
} from "../config/recordings";
import { Mp4ErrorCode } from "./mp4-duration";

/** Causas de rechazo de esta funcion; `quota` la decide la transaccion del trigger. */
export type RejectReason =
  | "consent"
  | "owner"
  | "disabled"
  | "type"
  | "size"
  | "duration"
  | "container"
  | "assignment"
  | "sharedWith"
  | "blur";

export interface ParsedObjectPath {
  uid: string;
  dir: typeof RECORDINGS_DIR | typeof DEMOS_DIR;
  id: string;
  ext: string;
}

// `String.raw`: en una plantilla normal `\.` pierde la barra y el punto casaria con cualquier caracter (C1).
const PATH_RE = new RegExp(
  String.raw`^users/([A-Za-z0-9]{1,128})/(${RECORDINGS_DIR}|${DEMOS_DIR})/([A-Za-z0-9-]{1,64})\.(m4a|mp4)$`,
);

/** `null` = ruta que finalize ignora o rechaza (fuera de los dos prefijos, nombre o extension no permitidos). */
export function parseObjectPath(path: string): ParsedObjectPath | null {
  const m = PATH_RE.exec(path);
  if (!m) return null;
  return {
    uid: m[1],
    dir: m[2] as ParsedObjectPath["dir"],
    id: m[3],
    ext: m[4],
  };
}

export interface RecordingMeta {
  kind?: string;
  assignmentId?: string;
  sharedWith?: string;
  /** Video (4c): sello del movil, `"true"` y la version de `BLUR_POLICY_VERSION`. */
  blurApplied?: string;
  blurPolicyVersion?: string;
}

export type ContainerProbe =
  | { ok: true; durationSec: number; brand: string }
  | { ok: false; code: Mp4ErrorCode };

export interface RecordingObject {
  path: string;
  contentType?: string;
  size: number;
  /** Resultado de `readMp4DurationSec` (el parser es asincrono; esta decision es pura). */
  container: ContainerProbe;
}

/** Estado del propietario ya leido de `users/{uid}`. */
export interface RecordingOwner {
  /** uid del documento `users/{uid}` leido; debe coincidir con el uid de la ruta del objeto (W7). */
  ownerUid: string;
  role?: string;
  isMinor: boolean;
  /** Cuenta en proceso de borrado (`deletion` presente). */
  deleting: boolean;
  consentStatus?: string;
  recordingConsent?: string;
  recordingConsentVersion?: number;
  recordingConsentEpoch?: number;
}

export interface ValidationContext {
  recordingsEnabled: boolean;
  /** `VIDEO_ENABLED` (4c): sin el, un `.mp4` se rechaza como `disabled`. Ausente = apagado. */
  videoEnabled?: boolean;
  minorRecordingEnabled: boolean;
  /** `RECORDING_CONSENT_VERSION` vigente; el consentimiento del usuario debe ser >= (nota B1/W4). */
  requiredConsentVersion: number;
  /**
   * `assignmentId` existe y es utilizable por esta subida; lo resuelve B4b con una lectura de Firestore:
   * - grabacion propia (`recordings/`): `users/{uid}/assignments/{assignmentId}` existe;
   * - demo (`demos/`): `users/{sharedWith}/assignments/{assignmentId}` existe y su `teacherUid` es el uid del autor.
   * Se lee del espacio del destinatario, nunca del autor.
   */
  assignmentExists?: boolean;
  /** Existe el vinculo activo profesor (autor) -> destinatario (`sharedWith`) de la demo; lo resuelve B4b. */
  teacherLinkExists?: boolean;
}

export type ValidationResult =
  | {
      ok: true;
      kind: "audio" | "video";
      dir: ParsedObjectPath["dir"];
      durationSec: number;
      sizeBytes: number;
      /** Epoca de consentimiento a estampar en el doc (B1): la purga/rechazo de otra epoca la decide B4b. */
      consentEpoch: number;
      sharedWith?: string;
      assignmentId?: string;
    }
  | { ok: false; reason: RejectReason };

const reject = (reason: RejectReason): ValidationResult => ({
  ok: false,
  reason,
});

/**
 * Decision pura de `finalizeRecording` sobre un objeto de audio (`.m4a`) o de video (`.mp4`, 4c) (REQ-REC-01, REQ-REC-05,
 * REQ-REC-10). Orden de causas: disabled, consent, type, blur (solo video), owner, size, container (parser o marca),
 * duration (minima, maxima, tasa de bits), sharedWith, assignment. La duracion es del `mvhd`, que el cliente puede
 * falsificar: es defensa en profundidad acotada por el tope de tamano y la tasa de bits maxima, no una garantia. El
 * difuminado tampoco se puede comprobar sin mirar las caras: el sello es una promesa del movil.
 */
export function validateRecording(
  meta: RecordingMeta,
  object: RecordingObject,
  owner: RecordingOwner,
  ctx: ValidationContext,
): ValidationResult {
  const path = parseObjectPath(object.path);
  const isVideo = path?.ext === "mp4";
  if (
    !ctx.recordingsEnabled ||
    (owner.isMinor && !ctx.minorRecordingEnabled) ||
    (isVideo && ctx.videoEnabled !== true)
  )
    return reject("disabled");

  if (
    owner.deleting ||
    owner.consentStatus !== "granted" ||
    owner.recordingConsent !== "granted" ||
    // Forma negada: NaN / no finito / ausente (0) fallan cerrado (W9).
    !((owner.recordingConsentVersion ?? 0) >= ctx.requiredConsentVersion)
  ) {
    return reject("consent");
  }

  const typeOk = isVideo
    ? object.contentType === "video/mp4" && meta.kind === "video"
    : object.contentType === "audio/mp4" &&
      (meta.kind === undefined || meta.kind === "audio");
  if (!path || !typeOk) return reject("type");

  if (
    isVideo &&
    (meta.blurApplied !== "true" ||
      meta.blurPolicyVersion !== String(BLUR_POLICY_VERSION))
  )
    return reject("blur");

  if (path.uid !== owner.ownerUid) return reject("owner");

  if (
    !(object.size > 0) ||
    object.size > (isVideo ? MAX_VIDEO_BYTES : MAX_AUDIO_BYTES)
  )
    return reject("size");

  const brands = isVideo ? ALLOWED_VIDEO_BRANDS : ALLOWED_BRANDS;
  if (!object.container.ok || !brands.includes(object.container.brand))
    return reject("container");
  const isTeacher = owner.role === "teacher";
  const limit = isTeacher ? MAX_TEACHER_DURATION_SEC : MAX_STUDENT_DURATION_SEC;
  const { durationSec } = object.container;
  // Formas negadas: NaN falla cerrado. 0 s = MP4 fragmentado (no soportado) o vacio (W2).
  if (
    !(durationSec >= MIN_DURATION_SEC) ||
    !(durationSec <= limit + DURATION_TOLERANCE_SEC)
  )
    return reject("duration");
  const maxBitrate = isVideo ? MAX_VIDEO_BITRATE_BPS : MAX_BITRATE_BPS;
  if (!((object.size * 8) / Math.max(durationSec, 1) <= maxBitrate))
    return reject("duration");

  const { sharedWith, assignmentId } = meta;
  if (path.dir === RECORDINGS_DIR) {
    if (sharedWith !== undefined) return reject("sharedWith");
  } else if (
    !isTeacher ||
    !sharedWith ||
    sharedWith === path.uid ||
    ctx.teacherLinkExists !== true
  ) {
    return reject("sharedWith");
  }

  if (path.dir === DEMOS_DIR && !assignmentId) return reject("assignment");
  if (assignmentId !== undefined && ctx.assignmentExists !== true)
    return reject("assignment");

  return {
    ok: true,
    kind: isVideo ? "video" : "audio",
    dir: path.dir,
    durationSec: Math.min(Math.round(durationSec), limit),
    sizeBytes: object.size,
    consentEpoch: owner.recordingConsentEpoch ?? 0,
    ...(sharedWith !== undefined ? { sharedWith } : {}),
    ...(assignmentId !== undefined ? { assignmentId } : {}),
  };
}
