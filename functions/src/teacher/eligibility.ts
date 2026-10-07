import { CURRENT_POLICY_VERSION } from "../config/identity";
import { isAdult, parseBirthDate } from "../identity/age";

export type TeacherDenyReason =
  | "NO_PROFILE"
  | "NOT_ADULT"
  | "EMAIL_NOT_VERIFIED"
  | "CONSENT_NOT_CURRENT"
  | "DELETION_IN_PROGRESS"
  | "HAS_TEACHER_LINKS";

export type TeacherEligibility =
  | { eligible: true; alreadyTeacher: boolean }
  | { eligible: false; reason: TeacherDenyReason };

export interface TeacherEligibilityInput {
  /** Contenido de `users/{uid}`; `undefined` si no existe. */
  profile: Record<string, unknown> | undefined;
  emailVerified: boolean;
  now: Date;
  /** Versión vigente de la política; solo se inyecta en tests. */
  policyVersion?: number;
  /** Umbral de adulto; solo se inyecta en tests (por defecto `ADULT_AGE`). */
  adultAge?: number;
  /** El llamador encontro filas `teacherLinks` con `studentUid = uid` (fuente de verdad frente al contador). */
  hasStudentLinks?: boolean;
}

const deny = (reason: TeacherDenyReason): TeacherEligibility => ({ eligible: false, reason });

/** La edad se calcula en UTC (`ageOn`). `birthDate` ilegible, inexistente o futura nunca cuenta como adulto (fail-closed). */
function adultFromProfile(birthDate: unknown, now: Date, adultAge?: number): boolean {
  if (typeof birthDate !== "string") return false;
  try {
    return isAdult(parseBirthDate(birthDate, now), now, adultAge);
  } catch {
    return false;
  }
}

/** Función pura (REQ-TRL-01/05). Un profesor ya existente es elegible e idempotente. */
export function canGrantTeacher({
  profile,
  emailVerified,
  now,
  policyVersion = CURRENT_POLICY_VERSION,
  adultAge,
  hasStudentLinks = false,
}: TeacherEligibilityInput): TeacherEligibility {
  if (!profile) return deny("NO_PROFILE");
  // Denegaciones duras antes de la rama idempotente: un profesor en borrado no se "reconfirma".
  if (profile.deletion) return deny("DELETION_IN_PROGRESS");
  if (profile.role === "teacher") return { eligible: true, alreadyTeacher: true };
  if (profile.role === "student" || hasStudentLinks) return deny("HAS_TEACHER_LINKS");
  if (!adultFromProfile(profile.birthDate, now, adultAge)) return deny("NOT_ADULT");
  if (!emailVerified) return deny("EMAIL_NOT_VERIFIED");
  if (profile.consentStatus !== "granted" || profile.policyVersion !== policyVersion) {
    return deny("CONSENT_NOT_CURRENT");
  }
  const raw = profile.teacherCount;
  if (raw !== undefined && (typeof raw !== "number" || !Number.isInteger(raw) || raw !== 0)) {
    return deny("HAS_TEACHER_LINKS");
  }
  return { eligible: true, alreadyTeacher: false };
}
