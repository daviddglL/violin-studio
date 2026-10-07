/* Lanzador comun de grant-teacher / revoke-teacher. Requiere `npm run build` (usa lib/; los scripts npm
 * `preadmin:*` lo hacen). Necesita GCLOUD_PROJECT; sin emuladores exige --yes o CONFIRM_PROJECT=<id>.
 * Credenciales: GOOGLE_APPLICATION_CREDENTIALS, o FIREBASE_AUTH_EMULATOR_HOST + FIRESTORE_EMULATOR_HOST. */
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { grantTeacher, revokeTeacher } = require("../lib/admin/teacher-role");
const { runTeacherCli } = require("../lib/admin/teacher-cli");

module.exports = function main(action) {
  return runTeacherCli(
    action,
    process.argv.slice(2),
    () => {
      const app = initializeApp({ projectId: process.env.GCLOUD_PROJECT });
      // Deliberadamente SIN `unlinkAllStudents`: hasta A3b, revocar un profesor con alumnos falla
      // cerrado (HAS_LINKS) en vez de dejar vinculos huerfanos.
      const deps = { db: getFirestore(app), auth: getAuth(app), clock: () => new Date() };
      return { grant: (uid) => grantTeacher(deps, uid), revoke: (uid) => revokeTeacher(deps, uid) };
    },
    (line) => console.log(line),
  ).then((code) => process.exit(code));
};
