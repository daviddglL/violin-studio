import { shouldEnforceAppCheck } from "../appcheck";

export const REGION = "europe-west1";

/** Opciones comunes de todas las callables: región, App Check según entorno y techo de coste. */
export function callableOptions(env: NodeJS.ProcessEnv = process.env) {
  return { region: REGION, enforceAppCheck: shouldEnforceAppCheck(env), maxInstances: 10 };
}

/** Opciones de los endpoints HTTPS abiertos al navegador (sin App Check). */
export const httpOptions = { region: REGION, maxInstances: 5 } as const;

/**
 * Region del trigger de Storage (spike Q5): un trigger v2 de Storage DEBE desplegarse en la region del bucket. El bucket
 * real aun no existe en el proyecto (Firestore/Storage sin habilitar), asi que no se puede comprobar; por defecto la
 * region comun y `STORAGE_TRIGGER_REGION` (en `.env.<project>`) la sustituye sin tocar codigo. Valor vacio o invalido -> REGION.
 */
export function storageTriggerRegion(env: NodeJS.ProcessEnv = process.env): string {
  const raw = env.STORAGE_TRIGGER_REGION?.trim();
  return raw && /^[a-z]+-[a-z]+[0-9]+$|^(us|eu|asia)$/.test(raw) ? raw : REGION;
}
