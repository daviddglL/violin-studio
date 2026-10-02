import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { onCall } from "firebase-functions/v2/https";
import { requireVerifiedUser } from "./common/auth-guard";
import { systemClock } from "./common/clock";
import { GUARDIAN_FLOW_ENABLED } from "./config/identity";
import { callableOptions } from "./config/runtime";
import { recordConsentHandler } from "./consent/record-consent";
import { revokeConsentCore } from "./consent/revoke-consent";
import { identityConfigHandler } from "./profile/identity-config";
import { registerProfileHandler } from "./profile/register-profile";
import { VERSION } from "./version";

export { REGION } from "./config/runtime";

const admin = () => getApps()[0] ?? initializeApp();

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
