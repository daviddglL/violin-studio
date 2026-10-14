/**
 * Interruptores de las grabaciones (A19). Como `GUARDIAN_LINK_BASE_URL`, se leen de `process.env` (en producción los
 * rellenan los ficheros `.env*` de functions al desplegar) y NO con `defineBoolean`: sin valor en un `.env`, el
 * emulador pregunta por consola y se cuelga en CI.
 *
 * Sin variable: apagados en producción (fail-closed; el despliegue de 4b los enciende por proyecto) y encendidos en el
 * emulador o bajo test. Cualquier valor distinto de `true` cuenta como apagado.
 */
export interface RecordingFlags {
  recordingsEnabled: boolean;
  videoEnabled: boolean;
  minorRecordingEnabled: boolean;
}

function flag(env: NodeJS.ProcessEnv, name: string, localDefault: boolean): boolean {
  const raw = env[name];
  if (raw === undefined) return localDefault;
  return raw.trim().toLowerCase() === "true";
}

export function recordingFlags(env: NodeJS.ProcessEnv = process.env): RecordingFlags {
  const local = env.FUNCTIONS_EMULATOR === "true" || env.NODE_ENV === "test";
  return {
    recordingsEnabled: flag(env, "RECORDINGS_ENABLED", local),
    videoEnabled: flag(env, "VIDEO_ENABLED", local),
    minorRecordingEnabled: flag(env, "MINOR_RECORDING_ENABLED", local),
  };
}
