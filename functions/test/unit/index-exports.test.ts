process.env.GCLOUD_PROJECT ??= "demo-violin-studio"; // el trigger de Storage resuelve el bucket por defecto al cargar
const entrypoint = require("../../src/index"); // eslint-disable-line @typescript-eslint/no-require-imports

test("el entrypoint desplegable solo exporta las funciones previstas", () => {
  expect(Object.keys(entrypoint).sort()).toEqual(["REGION", "createTeacherCode", "deleteAccount", "finalizeRecording", "grantRecordingConsent", "guardianConsent", "health", "identityConfig", "listTeacherCodes", "onRecordingDeleted", "onUserDeleted", "purgeIdentity", "purgeRecordings", "recordConsent", "redeemTeacherCode", "registerProfile", "requestGuardianConsent", "revokeConsent", "revokeRecordingConsent", "revokeTeacherCode", "sweepRecordingOrphans", "syncLinkNames", "unlinkTeacher"]);
});
