import { shouldEnforceAppCheck } from "../appcheck";

export const REGION = "europe-west1";

/** Opciones comunes de todas las callables: región, App Check según entorno y techo de coste. */
export function callableOptions(env: NodeJS.ProcessEnv = process.env) {
  return { region: REGION, enforceAppCheck: shouldEnforceAppCheck(env), maxInstances: 10 };
}

/** Opciones de los endpoints HTTPS abiertos al navegador (sin App Check). */
export const httpOptions = { region: REGION, maxInstances: 5 } as const;
