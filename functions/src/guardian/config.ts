/**
 * Base pública del enlace del tutor (Hosting). Se lee de `process.env` (en producción lo rellenan los ficheros
 * `.env*` de functions al desplegar). NO se usa `defineString`: sin valor en un fichero `.env`, el emulador
 * pregunta por consola y se cuelga en CI aunque haya `default`.
 */
export const DEFAULT_GUARDIAN_LINK_BASE_URL = "https://violin-app-dev-f0b55.web.app";

export const guardianLinkBaseUrl = (env: NodeJS.ProcessEnv = process.env): string =>
  (env.GUARDIAN_LINK_BASE_URL || DEFAULT_GUARDIAN_LINK_BASE_URL).replace(/\/+$/, "");
