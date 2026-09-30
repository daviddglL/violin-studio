import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { claimsFromProfile, syncClaims } from "../../src/identity/claims";

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

describe("claimsFromProfile: role acotado", () => {
  test.each(["independent", "student", "teacher"])("rol permitido %s se conserva", (role) => {
    expect(claimsFromProfile({ ...base, role }).role).toBe(role);
  });
  test.each([["admin"], ["x".repeat(2000)], [42], [null], [""]])("rol no permitido %p -> independent", (role) => {
    expect(claimsFromProfile({ ...base, role }).role).toBe("independent");
  });
  test("el payload de claims nunca supera el límite de 1000 bytes", () => {
    const claims = claimsFromProfile({ ...base, role: "x".repeat(5000) });
    expect(Buffer.byteLength(JSON.stringify(claims))).toBeLessThan(1000);
  });
});

describe("syncClaims: fusión de claims", () => {
  test("conserva claims ajenos y solo sobrescribe role y consentOk", async () => {
    const setCustomUserClaims = jest.fn().mockResolvedValue(undefined);
    const db = {
      collection: () => ({ doc: () => ({ get: async () => ({ exists: true, data: () => ({ ...base, role: "student" }) }) }) }),
    };
    const auth = {
      getUser: async () => ({ customClaims: { otro: "x", role: "independent", consentOk: false } }),
      setCustomUserClaims,
    };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await syncClaims({ db, auth } as any, "u1");
    expect(setCustomUserClaims).toHaveBeenCalledWith("u1", { otro: "x", role: "student", consentOk: true });
  });
});
