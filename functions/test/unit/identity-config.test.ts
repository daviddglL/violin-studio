import * as identity from "../../src/config/identity";

describe("config/identity", () => {
  test("valores por defecto de la política de identidad", () => {
    expect(identity.DIGITAL_CONSENT_AGE).toBe(14);
    expect(identity.MAX_PLAUSIBLE_AGE).toBe(120);
    expect(identity.GUARDIAN_FLOW_ENABLED).toBe(true);
    expect(identity.GUARDIAN_LINK_TTL_HOURS).toBe(72);
    expect(identity.PENDING_ACCOUNT_TTL_DAYS).toBe(7);
    expect(identity.GUARDIAN_MAX_SENDS_PER_24H).toBe(3);
    expect(identity.GUARDIAN_MAX_CONFIRM_ATTEMPTS).toBe(5);
    expect(identity.REAUTH_MAX_AGE_SECONDS).toBe(300);
  });
  test("la versión de política es un entero >= 1", () => {
    expect(Number.isInteger(identity.CURRENT_POLICY_VERSION)).toBe(true);
    expect(identity.CURRENT_POLICY_VERSION).toBeGreaterThanOrEqual(1);
  });
  test("la URL de la política es https", () => {
    expect(identity.POLICY_URL).toMatch(/^https:\/\//);
  });
  test("policyUrl sigue la base de Hosting del proyecto y cae a la de desarrollo sin ella", () => {
    expect(identity.policyUrl({ GUARDIAN_LINK_BASE_URL: "https://violin-prod.web.app/" })).toBe("https://violin-prod.web.app/privacy");
    expect(identity.policyUrl({})).toBe(identity.POLICY_URL);
    expect(identity.policyUrl({ GUARDIAN_LINK_BASE_URL: "http://127.0.0.1:5000" })).toBe(identity.POLICY_URL);
  });
});
