/**
 * Base pública del enlace del tutor (Hosting). Se lee de `process.env` (en producción lo rellenan los ficheros
 * `.env*` de functions al desplegar). NO se usa `defineString`: sin valor en un fichero `.env`, el emulador
 * pregunta por consola y se cuelga en CI aunque haya `default`.
 *
 * El valor dev por defecto solo se aplica en el emulador o bajo test: en producción sin variable es un error de
 * configuración (nunca se envían enlaces dev a tutores reales). Siempre https; http solo a localhost/127.0.0.1 en emulador/test.
 */
export const DEFAULT_GUARDIAN_LINK_BASE_URL = "https://violin-app-dev-f0b55.web.app";

export function guardianLinkBaseUrl(env: NodeJS.ProcessEnv = process.env): string {
  const local = env.FUNCTIONS_EMULATOR === "true" || env.NODE_ENV === "test";
  const raw = env.GUARDIAN_LINK_BASE_URL?.trim() || (local ? DEFAULT_GUARDIAN_LINK_BASE_URL : "");
  if (!raw) throw new Error("GUARDIAN_LINK_BASE_URL no configurada (obligatoria fuera del emulador)");
  const okLocalHttp = local && /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/|$)/.test(raw);
  if (!raw.startsWith("https://") && !okLocalHttp) throw new Error("GUARDIAN_LINK_BASE_URL debe empezar por https://");
  return raw.replace(/\/+$/, "");
}
