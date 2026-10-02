import { FunctionsErrorCode, HttpsError } from "firebase-functions/v2/https";

export enum ErrorReason {
  EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED",
  INVALID_BIRTH_DATE = "INVALID_BIRTH_DATE",
  UNDERAGE_NOT_ALLOWED = "UNDERAGE_NOT_ALLOWED",
  NO_PROFILE = "NO_PROFILE",
  NO_ACTIVE_CONSENT = "NO_ACTIVE_CONSENT",
  GUARDIAN_REQUIRED = "GUARDIAN_REQUIRED",
  POLICY_OUTDATED = "POLICY_OUTDATED",
  NOT_MINOR = "NOT_MINOR",
  GUARDIAN_EMAIL_INVALID = "GUARDIAN_EMAIL_INVALID",
  RATE_LIMITED = "RATE_LIMITED",
  REAUTH_REQUIRED = "REAUTH_REQUIRED",
  ERASURE_FAILED = "ERASURE_FAILED",
  INVALID_ARGUMENT = "INVALID_ARGUMENT",
  CONSENT_ALREADY_GRANTED = "CONSENT_ALREADY_GRANTED",
}

/** Error de callable con `details.reason` estable para que el cliente lo mapee sin parsear texto. */
export function fail(
  code: FunctionsErrorCode,
  reason: ErrorReason,
  details: Record<string, unknown> = {},
): HttpsError {
  return new HttpsError(code, reason, { reason, ...details });
}

/** Código de un error apto para logs: solo string o number (nunca el mensaje ni objetos que puedan llevar rutas con uid). */
export const safeErrorCode = (e: unknown): string | number => {
  const code = (e as { code?: unknown } | null | undefined)?.code;
  return typeof code === "string" || typeof code === "number" ? code : "unknown";
};
