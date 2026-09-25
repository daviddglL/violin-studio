/**
 * App Check se exige salvo en el emulador (no hay tokens reales) o si ENFORCE_APP_CHECK=false.
 * Play Integrity solo valida apps instaladas desde Google Play: mientras la app se reparta por
 * App Distribution, prod necesita ENFORCE_APP_CHECK=false en functions/.env.violin-app-795ee.
 */
export function shouldEnforceAppCheck(env: NodeJS.ProcessEnv): boolean {
  if (env.FUNCTIONS_EMULATOR === "true") return false;
  return env.ENFORCE_APP_CHECK !== "false";
}
