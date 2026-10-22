/* Lista las solicitudes de alta de profesor hechas desde la app (uid y fecha). Requiere `npm run build`
 * (`preadmin:teacher-requests` lo hace). Necesita GCLOUD_PROJECT; sin emuladores exige --yes o CONFIRM_PROJECT=<id>.
 * Se aprueban con `admin:grant-teacher -- <uid>` y se rechazan con `admin:reject-teacher -- <uid>`. */
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { listTeacherRequests } = require("../lib/admin/teacher-role");
const { runTeacherRequestsCli } = require("../lib/admin/teacher-cli");

runTeacherRequestsCli(
  process.argv.slice(2),
  () => {
    const app = initializeApp({ projectId: process.env.GCLOUD_PROJECT });
    return () => listTeacherRequests({ db: getFirestore(app) });
  },
  (line) => console.log(line),
).then((code) => process.exit(code));
