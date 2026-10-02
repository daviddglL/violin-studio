import * as entrypoint from "../../src/index";

test("el entrypoint desplegable solo exporta las funciones previstas", () => {
  expect(Object.keys(entrypoint).sort()).toEqual(["REGION", "deleteAccount", "health", "identityConfig", "onUserDeleted", "recordConsent", "registerProfile", "revokeConsent"]);
});
