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
}

const deny = (reason: TeacherDenyReason): TeacherEligibility => ({ eligible: false, reason });

/** `birthDate` ilegible, inexistente o futura nunca cuenta como adulto (fail-closed). */
function adultFromProfile(birthDate: unknown, now: Date): boolean {
  if (typeof birthDate !== "string") return false;
  try {
    return isAdult(parseBirthDate(birthDate, now), now);
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
}: TeacherEligibilityInput): TeacherEligibility {
  if (!profile) return deny("NO_PROFILE");
  if (profile.role === "teacher") return { eligible: true, alreadyTeacher: true };
  if (!adultFromProfile(profile.birthDate, now)) return deny("NOT_ADULT");
  if (!emailVerified) return deny("EMAIL_NOT_VERIFIED");
  if (profile.consentStatus !== "granted" || profile.policyVersion !== policyVersion) {
    return deny("CONSENT_NOT_CURRENT");
  }
  if (profile.deletion) return deny("DELETION_IN_PROGRESS");
  const count = typeof profile.teacherCount === "number" ? profile.teacherCount : 0;
  if (count > 0) return deny("HAS_TEACHER_LINKS");
  return { eligible: true, alreadyTeacher: false };
}
