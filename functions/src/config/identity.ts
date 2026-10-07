/** Única fuente de verdad de la política de identidad: el cliente no decide nada legal. */
export const DIGITAL_CONSENT_AGE = 14;
export const MAX_PLAUSIBLE_AGE = 120;
export const CURRENT_POLICY_VERSION = 1;
export const POLICY_URL = "https://violin-app-dev-f0b55.web.app/privacy";
export const GUARDIAN_FLOW_ENABLED = true;
export const GUARDIAN_LINK_TTL_HOURS = 72;
export const PENDING_ACCOUNT_TTL_DAYS = 7;
export const GUARDIAN_MAX_SENDS_PER_24H = 3;
export const GUARDIAN_MAX_CONFIRM_ATTEMPTS = 5;
export const REAUTH_MAX_AGE_SECONDS = 300;
export const GUARDIAN_REVOKE_LINK_TTL_DAYS = 30;

/** Umbral de adulto (rol profesor); independiente de DIGITAL_CONSENT_AGE. */
export const ADULT_AGE = 18;

/** Vínculo profesor-alumno (design §3.1). */
export const LINK_NOTICE_VERSION = 1;
export const MAX_STUDENTS_PER_TEACHER = 30;
export const MAX_TEACHERS_PER_STUDENT = 1;
export const TEACHER_CODE_TTL_DAYS = 7;
export const MAX_ACTIVE_CODES = 5;
export const MAX_CODES_PER_24H = 20;
export const REDEEM_MAX_FAILURES_PER_HOUR = 5;
