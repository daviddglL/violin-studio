import { shouldEnforceAppCheck } from "../../src/appcheck";

describe("shouldEnforceAppCheck", () => {
  test("exige App Check por defecto", () => {
    expect(shouldEnforceAppCheck({})).toBe(true);
  });
  test("no lo exige en el emulador", () => {
    expect(shouldEnforceAppCheck({ FUNCTIONS_EMULATOR: "true" })).toBe(false);
  });
  test("se puede desactivar con ENFORCE_APP_CHECK=false", () => {
    expect(shouldEnforceAppCheck({ ENFORCE_APP_CHECK: "false" })).toBe(false);
  });
  test("cualquier otro valor lo deja activado", () => {
    expect(shouldEnforceAppCheck({ ENFORCE_APP_CHECK: "no" })).toBe(true);
  });
});
