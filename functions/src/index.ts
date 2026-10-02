import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as functionsV1 from "firebase-functions/v1";
import { onCall, onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { requireVerifiedUser } from "./common/auth-guard";
import { systemClock } from "./common/clock";
import { GUARDIAN_FLOW_ENABLED } from "./config/identity";
import { callableOptions, httpOptions, REGION } from "./config/runtime";
import { recordConsentHandler } from "./consent/record-consent";
import { revokeConsentCore } from "./consent/revoke-consent";
import { resolveBucketName } from "./erasure/bucket";
import { deleteAccountHandler } from "./erasure/delete-account";
import { eraseUserData } from "./erasure/erase-user-data";
import { onUserDeletedHandler } from "./erasure/on-user-deleted";
import { guardianConsentHandler } from "./guardian/confirm";
import { guardianLinkBaseUrl } from "./guardian/config";
import { guardianHttpAdapter } from "./guardian/http";
import { GUARDIAN_EMAIL_PEPPER } from "./guardian/pepper";
import { requestGuardianConsentHandler } from "./guardian/request";
import { purgeIdentityHandler } from "./maintenance/purge";
import { identityConfigHandler } from "./profile/identity-config";
import { registerProfileHandler } from "./profile/register-profile";
import { VERSION } from "./version";

export { REGION };

const admin = () => getApps()[0] ?? initializeApp();

const erasureDeps = () => {
  const app = admin();
  return { db: getFirestore(app), auth: getAuth(app), bucket: getStorage(app).bucket(resolveBucketName(app.options)) };
};

export const health = onCall(callableOptions(), () => ({ status: "ok", version: VERSION }));

export const registerProfile = onCall(callableOptions(), async (request) => {
  const { uid } = requireVerifiedUser(request);
  const app = admin();
  return registerProfileHandler(
    { db: getFirestore(app), auth: getAuth(app), clock: systemClock, guardianFlowEnabled: GUARDIAN_FLOW_ENABLED },
    uid,
    request.data,
  );
});

export const identityConfig = onCall(callableOptions(), (request) => {
  const app = admin();
  return identityConfigHandler(request, { db: getFirestore(app), auth: getAuth(app) });
});

export const recordConsent = onCall(callableOptions(), async (request) => {
  const { uid } = requireVerifiedUser(request);
  const app = admin();
  return recordConsentHandler({ db: getFirestore(app), auth: getAuth(app) }, uid, request.data);
});

export const revokeConsent = onCall(callableOptions(), async (request) => {
  const { uid } = requireVerifiedUser(request);
  const app = admin();
  return revokeConsentCore({ db: getFirestore(app), auth: getAuth(app) }, uid, "self");
});

export const requestGuardianConsent = onCall(
  { ...callableOptions(), secrets: [GUARDIAN_EMAIL_PEPPER] },
  async (request) => {
    const { uid, token } = requireVerifiedUser(request);
    return requestGuardianConsentHandler(
      {
        db: getFirestore(admin()),
        pepper: GUARDIAN_EMAIL_PEPPER.value(),
        linkBaseUrl: guardianLinkBaseUrl(),
        guardianFlowEnabled: GUARDIAN_FLOW_ENABLED,
      },
      uid,
      token.email,
      request.data,
    );
  },
);

/** Página del tutor (Hosting reescribe /tutor aquí). Sin secretos: el handler no necesita el pepper; sin App Check (lo abre un navegador). */
export const guardianConsent = onRequest({ ...httpOptions, timeoutSeconds: 300 }, (req, res) => {
  const app = admin();
  return guardianHttpAdapter((r) => guardianConsentHandler({ db: getFirestore(app), auth: getAuth(app), erase: (uid) => eraseUserData(erasureDeps(), uid, { deleteAuth: true }) }, r))(req, res);
});

export const deleteAccount = onCall({ ...callableOptions(), timeoutSeconds: 300 }, (request) =>
  deleteAccountHandler({ erase: (uid) => eraseUserData(erasureDeps(), uid, { deleteAuth: true }) }, request),
);

export const onUserDeleted = functionsV1
  .region(REGION)
  .runWith({ failurePolicy: true })
  .auth.user()
  .onDelete((user) => onUserDeletedHandler(erasureDeps(), user.uid).then(() => undefined));

/** Purga diaria (D4, R-a, AD3): caducidad de cuentas pendientes, borrados atascados, perfiles huerfanos y barrido de docs caducados. */
export const purgeIdentity = onSchedule(
  { region: REGION, schedule: "every day 03:00", timeZone: "Europe/Madrid", retryCount: 2, timeoutSeconds: 540 },
  async () => {
    const deps = erasureDeps();
    await purgeIdentityHandler({ db: deps.db, auth: deps.auth, erase: (uid, opts) => eraseUserData(deps, uid, opts) });
  },
);
