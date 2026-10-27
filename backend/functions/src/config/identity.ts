/** Única fuente de verdad de la política de identidad: el cliente no decide nada legal. */
export const DIGITAL_CONSENT_AGE = 14;
export const MAX_PLAUSIBLE_AGE = 120;
export const CURRENT_POLICY_VERSION = 2;
/** URL de la politica del proyecto de desarrollo; en otro proyecto manda `policyUrl()` (sale de `GUARDIAN_LINK_BASE_URL`). */
export const POLICY_URL = "https://violin-app-dev-f0b55.web.app/privacy";

/**
 * URL publica de la politica para el proyecto desplegado: la misma base de Hosting que el enlace del tutor
 * (`GUARDIAN_LINK_BASE_URL` en `functions/.env.<project>`) mas la ruta de [POLICY_URL]. Sin esa variable (o con un valor
 * que no es https) se usa [POLICY_URL]: un proyecto de produccion DEBE definirla o mostraria la politica de desarrollo.
 */
export function policyUrl(env: NodeJS.ProcessEnv = process.env): string {
  const base = env.GUARDIAN_LINK_BASE_URL?.trim().replace(/\/+$/, "");
  return base && base.startsWith("https://") ? base + new URL(POLICY_URL).pathname : POLICY_URL;
}
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
export const LINK_NOTICE_VERSION = 2;
/**
 * Versiones del aviso que el canje acepta. Solo la v2 (menciona el progreso del curriculo): la 1 se retiro antes del
 * primer despliegue con la versión mínima, que obliga a actualizar las builds que aún la enviaban. Los vínculos ya
 * creados guardan su `ackVersion` y no se tocan.
 */
export const ACCEPTED_LINK_NOTICE_VERSIONS: readonly number[] = [LINK_NOTICE_VERSION];
export const MAX_STUDENTS_PER_TEACHER = 30;
export const MAX_TEACHERS_PER_STUDENT = 1;
export const TEACHER_CODE_TTL_DAYS = 7;
export const MAX_ACTIVE_CODES = 5;
export const MAX_CODES_PER_24H = 20;
export const REDEEM_MAX_FAILURES_PER_HOUR = 5;

/** Versión del consentimiento de grabación (REQ-CON-R03): independiente de CURRENT_POLICY_VERSION. */
export const RECORDING_CONSENT_VERSION = 1;
