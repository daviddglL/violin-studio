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
 * region comun y `STORAGE_TRIGGER_REGION` (en `.env.<project>`) la sustituye sin tocar codigo. Vacio -> REGION; un valor
 * no vacio con otra forma LANZA al cargar el entrypoint (desplegar en una region distinta de la pedida seria silencioso).
 * Las multirregiones (`us`, `eu`, `asia`) no son regiones de Functions: con un bucket multirregion, usar una region miembro.
 */
export function storageTriggerRegion(env: NodeJS.ProcessEnv = process.env): string {
  const raw = env.STORAGE_TRIGGER_REGION?.trim();
  if (!raw) return REGION;
  if (!/^[a-z]+-[a-z]+[0-9]+$/.test(raw)) {
    throw new Error(`STORAGE_TRIGGER_REGION invalida: se espera una region de Functions (p. ej. europe-west1)`);
  }
  return raw;
}
