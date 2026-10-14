import * as entrypoint from "../../src/index";

test("el entrypoint desplegable solo exporta las funciones previstas", () => {
  expect(Object.keys(entrypoint).sort()).toEqual(["REGION", "createTeacherCode", "deleteAccount", "grantRecordingConsent", "guardianConsent", "health", "identityConfig", "listTeacherCodes", "onUserDeleted", "purgeIdentity", "recordConsent", "redeemTeacherCode", "registerProfile", "requestGuardianConsent", "revokeConsent", "revokeRecordingConsent", "revokeTeacherCode", "syncLinkNames", "unlinkTeacher"]);
});
