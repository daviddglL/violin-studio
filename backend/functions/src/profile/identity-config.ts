import { logger } from "firebase-functions/v2";
import { HttpsError } from "firebase-functions/v2/https";
import { ReevaluateDeps, reevaluateConsent } from "../consent/reevaluate";
import { chatEnabled, minVersionCode, notificationsEnabled, RecordingFlags, recordingFlags } from "../config/features";
import {
  CURRENT_POLICY_VERSION,
  DIGITAL_CONSENT_AGE,
  GUARDIAN_FLOW_ENABLED,
  policyUrl,
  RECORDING_CONSENT_VERSION,
} from "../config/identity";

export interface IdentityConfigDeps extends ReevaluateDeps {
  /** Sumidero de logs sin PII; por defecto `logger.warn`. */
  log?: (message: string, data: Record<string, unknown>) => void;
  /** Interruptores de grabación; solo se inyectan en tests, en producción salen del entorno (`recordingFlags`). */
  flags?: RecordingFlags;
  /** Interruptor del chat; solo se inyecta en tests (en produccion, `chatEnabled()`). */
  chatEnabled?: boolean;
  /** Interruptor de los avisos push; solo se inyecta en tests (en produccion, `notificationsEnabled()`). */
  notificationsEnabled?: boolean;
  /** Version minima de la app; solo se inyecta en tests (en produccion, `minVersionCode()` y `appConfig/features`). */
  minVersionCode?: number;
}

export interface IdentityConfig {
  policyVersion: number;
  policyUrl: string;
  digitalConsentAge: number;
  guardianFlowEnabled: boolean;
  /** Interruptores de 4b/4c (A19): la UI se oculta con ellos, el servidor (grant y finalize) es quien hace cumplir. */
  recordingsEnabled: boolean;
  videoEnabled: boolean;
  minorRecordingEnabled: boolean;
  /** Versión vigente del consentimiento de grabación (independiente de `policyVersion`). */
  recordingConsentVersion: number;
  /** Chat profesor-alumno (4c): la UI lo muestra solo con este interruptor. */
  chatEnabled: boolean;
  /** Avisos push: la app solo registra el token del dispositivo con este interruptor. */
  notificationsEnabled: boolean;
  /** `versionCode` minimo: una app mas antigua solo muestra "Actualiza la app". 0 = sin minimo. */
  minVersionCode: number;
}

/**
 * Política vigente para la UI. Exige sesión pero no email verificado ni consentimiento.
 * Antes de responder reevalúa (sin bloquear la respuesta si falla) el consentimiento del usuario (D3): si subió la versión, vuelve a `pending`.
 */
export async function identityConfigHandler(
  req: { auth?: { uid: string; token?: Record<string, unknown> } },
  deps: IdentityConfigDeps,
): Promise<IdentityConfig> {
  if (!req.auth) throw new HttpsError("unauthenticated", "Se requiere sesión");
  // La reevaluación es un extra: si falla, la política se devuelve igual (solo un código, sin PII).
  try {
    const tokenClaim = req.auth.token ? req.auth.token.consentOk === true : undefined;
    await reevaluateConsent({ ...deps, knownConsentOk: tokenClaim }, req.auth.uid);
  } catch (e) {
    const code = typeof (e as { code?: unknown })?.code === "string" ? (e as { code: string }).code : "unknown";
    (deps.log ?? ((m, d) => logger.warn(m, d)))("identityConfig: reevaluación fallida", { code });
  }
  return {
    policyVersion: deps.currentVersion ?? CURRENT_POLICY_VERSION,
    policyUrl: policyUrl(),
    digitalConsentAge: DIGITAL_CONSENT_AGE,
    guardianFlowEnabled: GUARDIAN_FLOW_ENABLED,
    ...(deps.flags ?? recordingFlags()),
    recordingConsentVersion: deps.currentRecordingVersion ?? RECORDING_CONSENT_VERSION,
    chatEnabled: deps.chatEnabled ?? chatEnabled(),
    notificationsEnabled: deps.notificationsEnabled ?? notificationsEnabled(),
    minVersionCode: deps.minVersionCode ?? minVersionCode(),
  };
}
