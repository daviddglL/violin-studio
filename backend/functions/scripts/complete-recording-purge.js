/* Cierra a mano una purga de revocacion de grabaciones atascada (`revokePurge.stuck`), tras la limpieza manual (runbook en
 * README). Requiere `npm run build` (usa lib/; `preadmin:complete-recording-purge` lo hace). Necesita GCLOUD_PROJECT; sin
 * emulador exige --yes o CONFIRM_PROJECT=<id>. Sin --force rechaza si quedan docs `ready` dentro de la epoca de la purga.
 * Uso: npm run admin:complete-recording-purge -- <uid> [--force] [--yes]
 * Credenciales: GOOGLE_APPLICATION_CREDENTIALS, o FIRESTORE_EMULATOR_HOST. */
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { completeRecordingPurge } = require("../lib/recordings/complete-purge");
const { runCompletePurgeCli } = require("../lib/admin/complete-purge-cli");

runCompletePurgeCli(
  process.argv.slice(2),
  () => {
    const db = getFirestore(initializeApp({ projectId: process.env.GCLOUD_PROJECT }));
    return (uid, opts) => completeRecordingPurge(db, uid, opts);
  },
  (line) => console.log(line),
).then((code) => process.exit(code));
