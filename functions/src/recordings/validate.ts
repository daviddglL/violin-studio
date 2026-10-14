import {
  DEMOS_DIR,
  DURATION_TOLERANCE_SEC,
  MAX_AUDIO_BYTES,
  MAX_STUDENT_DURATION_SEC,
  MAX_TEACHER_DURATION_SEC,
  RECORDINGS_DIR,
} from "../config/recordings";
import { Mp4ErrorCode } from "./mp4-duration";

/** Causas de rechazo con efecto en B4b; `quota` y `blur` las decide el trigger (transaccion / 4c), no esta funcion. */
export type RejectReason = "consent" | "disabled" | "type" | "size" | "duration" | "container" | "assignment" | "sharedWith";

export interface ParsedObjectPath {
  uid: string;
  dir: typeof RECORDINGS_DIR | typeof DEMOS_DIR;
  id: string;
  ext: string;
}

const PATH_RE = new RegExp(`^users/([A-Za-z0-9]{1,128})/(${RECORDINGS_DIR}|${DEMOS_DIR})/([A-Za-z0-9-]{1,64})\.(m4a|mp4)$`);

/** `null` = ruta que finalize ignora o rechaza (fuera de los dos prefijos, nombre o extension no permitidos). */
export function parseObjectPath(path: string): ParsedObjectPath | null {
  const m = PATH_RE.exec(path);
  if (!m) return null;
  return { uid: m[1], dir: m[2] as ParsedObjectPath["dir"], id: m[3], ext: m[4] };
}

export interface RecordingMeta {
  kind?: string;
  assignmentId?: string;
  sharedWith?: string;
}

export type ContainerProbe = { ok: true; durationSec: number } | { ok: false; code: Mp4ErrorCode };

export interface RecordingObject {
  path: string;
  contentType?: string;
  size: number;
  /** Resultado de `readMp4DurationSec` (el parser es asincrono; esta decision es pura). */
  container: ContainerProbe;
}

/** Estado del propietario ya leido de `users/{uid}`. */
export interface RecordingOwner {
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
  minorRecordingEnabled: boolean;
  /** `RECORDING_CONSENT_VERSION` vigente; el consentimiento del usuario debe ser >= (nota B1/W4). */
  requiredConsentVersion: number;
  /** `assignmentId` existe (en el espacio del alumno o, en demos, con `teacherUid == uid`); lo resuelve B4b. */
  assignmentExists?: boolean;
  /** Existe el vinculo profesor->destinatario de la demo; lo resuelve B4b. */
  teacherLinkExists?: boolean;
}

export type ValidationResult =
  | {
      ok: true;
      kind: "audio";
      dir: ParsedObjectPath["dir"];
      durationSec: number;
      sizeBytes: number;
      /** Epoca de consentimiento a estampar en el doc (B1): la purga/rechazo de otra epoca la decide B4b. */
      consentEpoch: number;
      sharedWith?: string;
      assignmentId?: string;
    }
  | { ok: false; reason: RejectReason };

const reject = (reason: RejectReason): ValidationResult => ({ ok: false, reason });

/**
 * Decision pura de `finalizeRecording` sobre un objeto de audio (REQ-REC-01, REQ-REC-05, REQ-REC-10). Orden de causas:
 * disabled, consent, type, size, container, duration, sharedWith, assignment. En 4b solo existe audio `.m4a`.
 */
export function validateRecording(
  meta: RecordingMeta,
  object: RecordingObject,
  owner: RecordingOwner,
  ctx: ValidationContext,
): ValidationResult {
  if (!ctx.recordingsEnabled || (owner.isMinor && !ctx.minorRecordingEnabled)) return reject("disabled");

  if (
    owner.deleting ||
    owner.consentStatus !== "granted" ||
    owner.recordingConsent !== "granted" ||
    (owner.recordingConsentVersion ?? 0) < ctx.requiredConsentVersion
  ) {
    return reject("consent");
  }

  const path = parseObjectPath(object.path);
  if (!path || path.ext !== "m4a" || object.contentType !== "audio/mp4" || (meta.kind !== undefined && meta.kind !== "audio")) {
    return reject("type");
  }

  if (!(object.size > 0) || object.size > MAX_AUDIO_BYTES) return reject("size");

  if (!object.container.ok) return reject("container");
  const isTeacher = owner.role === "teacher";
  const limit = isTeacher ? MAX_TEACHER_DURATION_SEC : MAX_STUDENT_DURATION_SEC;
  if (object.container.durationSec > limit + DURATION_TOLERANCE_SEC) return reject("duration");

  const { sharedWith, assignmentId } = meta;
  if (path.dir === RECORDINGS_DIR) {
    if (sharedWith !== undefined) return reject("sharedWith");
  } else if (!isTeacher || !sharedWith || sharedWith === path.uid || ctx.teacherLinkExists !== true) {
    return reject("sharedWith");
  }

  if (path.dir === DEMOS_DIR && !assignmentId) return reject("assignment");
  if (assignmentId !== undefined && ctx.assignmentExists !== true) return reject("assignment");

  return {
    ok: true,
    kind: "audio",
    dir: path.dir,
    durationSec: Math.round(object.container.durationSec),
    sizeBytes: object.size,
    consentEpoch: owner.recordingConsentEpoch ?? 0,
    ...(sharedWith !== undefined ? { sharedWith } : {}),
    ...(assignmentId !== undefined ? { assignmentId } : {}),
  };
}
