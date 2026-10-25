/* Recalcula `users/{uid}.recordingCount` desde los docs `ready` (B5a.4, A21). Requiere `npm run build` (usa lib/;
 * `preadmin:recount-recordings` lo hace). Necesita GCLOUD_PROJECT; sin emulador exige --yes o CONFIRM_PROJECT=<id>.
 * Uso: npm run admin:recount-recordings -- [<uid>] [--yes]
 * Credenciales: GOOGLE_APPLICATION_CREDENTIALS, o FIRESTORE_EMULATOR_HOST. */
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { reconcileAllOwners, reconcileOwner } = require("../lib/recordings/recount");
const { runRecountRecordingsCli } = require("../lib/admin/recount-cli");

runRecountRecordingsCli(
  process.argv.slice(2),
  () => {
    const db = getFirestore(initializeApp({ projectId: process.env.GCLOUD_PROJECT }));
    return async (uid) => {
      if (uid === undefined) return reconcileAllOwners(db);
      const outcome = await reconcileOwner(db, uid);
      return { scanned: 1, repaired: outcome === "repaired" ? 1 : 0, failed: 0, stopped: false };
    };
  },
  (line) => console.log(line),
).then((code) => process.exit(code));
