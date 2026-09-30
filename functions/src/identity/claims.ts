import { CURRENT_POLICY_VERSION } from "../config/identity";

export interface Claims {
  role: string;
  consentOk: boolean;
}

/** Sin PII: solo `role` y `consentOk` derivan del documento `users/{uid}` (fuente de verdad). */
export function claimsFromProfile(doc: Record<string, unknown>): Claims {
  const role = typeof doc.role === "string" && doc.role.length > 0 ? doc.role : "independent";
  const consentOk =
    doc.consentStatus === "granted" && doc.policyVersion === CURRENT_POLICY_VERSION && !doc.deletion;
  return { role, consentOk };
}
