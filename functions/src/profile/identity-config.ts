import { HttpsError } from "firebase-functions/v2/https";
import {
  CURRENT_POLICY_VERSION,
  DIGITAL_CONSENT_AGE,
  GUARDIAN_FLOW_ENABLED,
  POLICY_URL,
} from "../config/identity";

export interface IdentityConfig {
  policyVersion: number;
  policyUrl: string;
  digitalConsentAge: number;
  guardianFlowEnabled: boolean;
}

/** Política vigente para la UI. Exige sesión pero no email verificado ni consentimiento. */
export function identityConfigHandler(req: { auth?: unknown }): IdentityConfig {
  if (!req.auth) throw new HttpsError("unauthenticated", "Se requiere sesión");
  return {
    policyVersion: CURRENT_POLICY_VERSION,
    policyUrl: POLICY_URL,
    digitalConsentAge: DIGITAL_CONSENT_AGE,
    guardianFlowEnabled: GUARDIAN_FLOW_ENABLED,
  };
}
