import { logger } from "firebase-functions/v2";
import { HttpsError } from "firebase-functions/v2/https";
import { ReevaluateDeps, reevaluateConsent } from "../consent/reevaluate";
import {
  CURRENT_POLICY_VERSION,
  DIGITAL_CONSENT_AGE,
  GUARDIAN_FLOW_ENABLED,
  POLICY_URL,
} from "../config/identity";

export interface IdentityConfigDeps extends ReevaluateDeps {
  /** Sumidero de logs sin PII; por defecto `logger.warn`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface IdentityConfig {
  policyVersion: number;
  policyUrl: string;
  digitalConsentAge: number;
  guardianFlowEnabled: boolean;
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
    policyUrl: POLICY_URL,
    digitalConsentAge: DIGITAL_CONSENT_AGE,
    guardianFlowEnabled: GUARDIAN_FLOW_ENABLED,
  };
}
