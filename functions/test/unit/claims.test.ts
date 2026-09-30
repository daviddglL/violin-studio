import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { claimsFromProfile } from "../../src/identity/claims";

const base = { role: "independent", consentStatus: "granted", policyVersion: CURRENT_POLICY_VERSION };

describe("claimsFromProfile", () => {
  test("solo expone role y consentOk", () => {
    expect(claimsFromProfile({ ...base, birthDate: "2015-01-01", displayName: "Ana", guardian: { x: 1 } })).toEqual({
      role: "independent",
      consentOk: true,
    });
  });
  test("granted con la versión actual y sin borrado -> consentOk", () => {
    expect(claimsFromProfile(base).consentOk).toBe(true);
  });
  test.each([["pending"], ["revoked"], ["parental_pending"]])("%s -> consentOk false", (estado) => {
    expect(claimsFromProfile({ ...base, consentStatus: estado }).consentOk).toBe(false);
  });
  test("versión distinta de la actual -> false", () => {
    expect(claimsFromProfile({ ...base, policyVersion: CURRENT_POLICY_VERSION - 1 }).consentOk).toBe(false);
    expect(claimsFromProfile({ ...base, policyVersion: CURRENT_POLICY_VERSION + 1 }).consentOk).toBe(false);
  });
  test("borrado en curso -> false", () => {
    expect(claimsFromProfile({ ...base, deletion: { state: "in_progress" } }).consentOk).toBe(false);
  });
  test("sin rol conocido usa independent y campos ausentes no conceden nada", () => {
    expect(claimsFromProfile({})).toEqual({ role: "independent", consentOk: false });
  });
});
