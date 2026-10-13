import { CURRENT_POLICY_VERSION, LINK_NOTICE_VERSION, MAX_STUDENTS_PER_TEACHER } from "../config/identity";
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
export function adultFromProfile(birthDate: unknown, now: Date, adultAge?: number): boolean {
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

export type RedeemDenyReason =
  | "NOTICE_OUTDATED"
  | "STUDENT_INACTIVE"
  | "NO_CONSENT"
  | "STUDENT_IS_TEACHER"
  | "ALREADY_LINKED"
  | "TEACHER_UNAVAILABLE"
  | "TEACHER_FULL";

export type RedeemEligibility = { ok: true } | { ok: false; reason: RedeemDenyReason };

export interface RedeemInput {
  /** Contenido de `users/{s}` / `users/{t}`; `undefined` si no existe. */
  student: Record<string, unknown> | undefined;
  teacher: Record<string, unknown> | undefined;
  ackVersion: unknown;
  now: Date;
  /** Solo se inyecta en tests. */
  policyVersion?: number;
}

const consentCurrent = (p: Record<string, unknown>, v: number): boolean =>
  p.consentStatus === "granted" && p.policyVersion === v;

/** Profesor utilizable por un canje: doc leido (no el claim), rol vigente, adulto, consentimiento al dia, sin borrado. */
export function teacherUsable(p: Record<string, unknown> | undefined, now: Date, policyVersion: number): boolean {
  return (
    !!p && !p.deletion && p.role === "teacher" && adultFromProfile(p.birthDate, now) && consentCurrent(p, policyVersion)
  );
}

export interface StudentCheckInput {
  student: Record<string, unknown> | undefined;
  ackVersion: unknown;
  /** Solo se inyecta en tests. */
  policyVersion?: number;
}

/** Parte del canje que depende solo del alumno y del aviso (no del codigo): se evalua ANTES de mirar el codigo. */
export function studentDenial({
  student,
  ackVersion,
  policyVersion = CURRENT_POLICY_VERSION,
}: StudentCheckInput): RedeemDenyReason | null {
  if (ackVersion !== LINK_NOTICE_VERSION) return "NOTICE_OUTDATED";
  if (!student || student.deletion) return "STUDENT_INACTIVE";
  if (!consentCurrent(student, policyVersion)) return "NO_CONSENT";
  if (student.role === "teacher") return "STUDENT_IS_TEACHER";
  const tc = student.teacherCount ?? 0;
  if (student.role === "student" || typeof tc !== "number" || tc >= 1) return "ALREADY_LINKED";
  return null;
}

/** Funcion pura (REQ-LNK-03/05/06/07). Los contadores ausentes valen 0; uno corrupto falla cerrado. */
export function canRedeem({
  student,
  teacher,
  ackVersion,
  now,
  policyVersion = CURRENT_POLICY_VERSION,
}: RedeemInput): RedeemEligibility {
  const no = (reason: RedeemDenyReason): RedeemEligibility => ({ ok: false, reason });
  const denied = studentDenial({ student, ackVersion, policyVersion });
  if (denied) return no(denied);
  if (!teacherUsable(teacher, now, policyVersion)) return no("TEACHER_UNAVAILABLE");
  const sc = teacher?.studentCount ?? 0;
  if (typeof sc !== "number" || sc >= MAX_STUDENTS_PER_TEACHER) return no("TEACHER_FULL");
  return { ok: true };
}
