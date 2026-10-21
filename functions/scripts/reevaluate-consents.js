/* Reevaluacion masiva del consentimiento tras subir CURRENT_POLICY_VERSION (ejecutar DESPUES de desplegar
 * Functions con la version nueva). Requiere `npm run build` (usa lib/; `preadmin:reevaluate-consents` lo hace).
 * Necesita GCLOUD_PROJECT; sin emuladores exige --yes o CONFIRM_PROJECT=<id>.
 * Credenciales: GOOGLE_APPLICATION_CREDENTIALS, o FIREBASE_AUTH_EMULATOR_HOST + FIRESTORE_EMULATOR_HOST. */
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { reevaluateAllConsents } = require("../lib/consent/reevaluate-all");
const { runReevaluateAllCli } = require("../lib/admin/reevaluate-cli");

runReevaluateAllCli(
  process.argv.slice(2),
  () => {
    const app = initializeApp({ projectId: process.env.GCLOUD_PROJECT });
    return () => reevaluateAllConsents({ db: getFirestore(app), auth: getAuth(app) });
  },
  (line) => console.log(line),
).then((code) => process.exit(code));
