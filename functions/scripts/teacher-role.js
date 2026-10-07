/* Lanzador comun de grant-teacher / revoke-teacher. Requiere `npm run build` (usa lib/).
 * Credenciales de administrador (GOOGLE_APPLICATION_CREDENTIALS) o emuladores (FIREBASE_AUTH_EMULATOR_HOST,
 * FIRESTORE_EMULATOR_HOST). El proyecto sale de GCLOUD_PROJECT. */
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { grantTeacher, revokeTeacher } = require("../lib/admin/teacher-role");
const { runTeacherCli } = require("../lib/admin/teacher-cli");

module.exports = function main(action) {
  const app = initializeApp(process.env.GCLOUD_PROJECT ? { projectId: process.env.GCLOUD_PROJECT } : undefined);
  const deps = { db: getFirestore(app), auth: getAuth(app), clock: () => new Date() };
  return runTeacherCli(
    action,
    process.argv.slice(2),
    { grant: (uid) => grantTeacher(deps, uid), revoke: (uid) => revokeTeacher(deps, uid) },
    (line) => console.log(line),
  ).then((code) => process.exit(code));
};
