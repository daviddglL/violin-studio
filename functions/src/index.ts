import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as functionsV1 from "firebase-functions/v1";
import { onCall } from "firebase-functions/v2/https";
import { requireVerifiedUser } from "./common/auth-guard";
import { systemClock } from "./common/clock";
import { GUARDIAN_FLOW_ENABLED } from "./config/identity";
import { callableOptions, REGION } from "./config/runtime";
import { recordConsentHandler } from "./consent/record-consent";
import { revokeConsentCore } from "./consent/revoke-consent";
import { resolveBucketName } from "./erasure/bucket";
import { deleteAccountHandler } from "./erasure/delete-account";
import { eraseUserData } from "./erasure/erase-user-data";
import { onUserDeletedHandler } from "./erasure/on-user-deleted";
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

export const deleteAccount = onCall({ ...callableOptions(), timeoutSeconds: 300 }, (request) =>
  deleteAccountHandler({ erase: (uid) => eraseUserData(erasureDeps(), uid, { deleteAuth: true }) }, request),
);

export const onUserDeleted = functionsV1
  .region(REGION)
  .runWith({ failurePolicy: true })
  .auth.user()
  .onDelete((user) => onUserDeletedHandler(erasureDeps(), user.uid).then(() => undefined));
