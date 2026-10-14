/* Lanzador comun de grant-teacher / revoke-teacher. Requiere `npm run build` (usa lib/; los scripts npm
 * `preadmin:*` lo hacen). Necesita GCLOUD_PROJECT; sin emuladores exige --yes o CONFIRM_PROJECT=<id>.
 * revoke-teacher exige ademas STORAGE_BUCKET (o storageBucket en FIREBASE_CONFIG): borra las demos del profesor.
 * Credenciales: GOOGLE_APPLICATION_CREDENTIALS, o FIREBASE_AUTH_EMULATOR_HOST + FIRESTORE_EMULATOR_HOST
 * (+ FIREBASE_STORAGE_EMULATOR_HOST para revoke). */
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { bucketStore } = require("../lib/recordings/finalize");
const { grantTeacher, revokeTeacher } = require("../lib/admin/teacher-role");
const { runTeacherCli } = require("../lib/admin/teacher-cli");

module.exports = function main(action) {
  return runTeacherCli(
    action,
    process.argv.slice(2),
    ({ bucket }) => {
      const app = initializeApp({ projectId: process.env.GCLOUD_PROJECT });
      // Revocar codigos y desvincular alumnos son los valores por defecto de `revokeTeacher`.
      // `store`: al desvincular se borran las demostraciones del profesor para cada alumno (objeto + doc). El bucket lo
      // valida `runTeacherCli` (explicito, nunca adivinado); grant no lo usa.
      const store = bucket ? bucketStore(getStorage(app).bucket(bucket)) : undefined;
      const deps = { db: getFirestore(app), auth: getAuth(app), store, clock: () => new Date() };
      return { grant: (uid) => grantTeacher(deps, uid), revoke: (uid) => revokeTeacher(deps, uid) };
    },
    (line) => console.log(line),
  ).then((code) => process.exit(code));
};
