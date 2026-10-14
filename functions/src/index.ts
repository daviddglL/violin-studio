import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as functionsV1 from "firebase-functions/v1";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onCall, onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onObjectFinalized } from "firebase-functions/v2/storage";
import { requireVerifiedUser } from "./common/auth-guard";
import { systemClock } from "./common/clock";
import { recordingFlags } from "./config/features";
import { GUARDIAN_FLOW_ENABLED } from "./config/identity";
import { callableOptions, httpOptions, REGION, storageTriggerRegion } from "./config/runtime";
import { recordConsentHandler } from "./consent/record-consent";
import { grantRecordingConsentHandler, revokeRecordingConsentHandler } from "./consent/recording-consent";
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
import { bucketStore, finalizeRecordingHandler } from "./recordings/finalize";
import { identityConfigHandler } from "./profile/identity-config";
import { registerProfileHandler } from "./profile/register-profile";
import {
  createTeacherCodeHandler,
  listTeacherCodesHandler,
  revokeTeacherCodeHandler,
} from "./teacher/code-handlers";
import { TEACHER_CODE_PEPPER } from "./teacher/pepper";
import { redeemTeacherCodeHandler } from "./teacher/redeem";
import { syncLinkNamesHandler } from "./teacher/sync-names";
import { unlinkTeacherHandler } from "./teacher/unlink";
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

/** Consentimiento de grabación del propio usuario (14+); los <14 solo por el tutor. No toca la base ni los claims. */
export const grantRecordingConsent = onCall(callableOptions(), (request) => {
  const { uid } = requireVerifiedUser(request);
  return grantRecordingConsentHandler(
    { db: getFirestore(admin()), clock: systemClock, flags: recordingFlags() },
    uid,
    request.data,
  );
});

/** Revoca solo la grabación (también un menor de 14); la base de consentimiento no cambia. */
export const revokeRecordingConsent = onCall(callableOptions(), (request) => {
  const { uid } = requireVerifiedUser(request);
  return revokeRecordingConsentHandler({ db: getFirestore(admin()) }, uid);
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

/** Codigos de vinculo del profesor (REQ-LNK-01/02): el rol y el consentimiento se leen del doc, no del claim. */
// Solo `createTeacherCode` enlaza el secreto; revocar y listar no lo necesitan ni lo leen.
export const createTeacherCode = onCall({ ...callableOptions(), secrets: [TEACHER_CODE_PEPPER] }, (request) => {
  const { uid } = requireVerifiedUser(request);
  return createTeacherCodeHandler({ db: getFirestore(admin()), pepper: TEACHER_CODE_PEPPER.value() }, uid);
});

export const revokeTeacherCode = onCall(callableOptions(), (request) =>
  revokeTeacherCodeHandler({ db: getFirestore(admin()) }, requireVerifiedUser(request).uid, request.data),
);

export const listTeacherCodes = onCall(callableOptions(), (request) =>
  listTeacherCodesHandler({ db: getFirestore(admin()) }, requireVerifiedUser(request).uid),
);

/** Canje del alumno (REQ-LNK-03): enlaza el secreto porque hashea el codigo; alumno y profesor se releen del doc. */
export const redeemTeacherCode = onCall({ ...callableOptions(), secrets: [TEACHER_CODE_PEPPER] }, (request) => {
  const { uid } = requireVerifiedUser(request);
  const app = admin();
  return redeemTeacherCodeHandler(
    { db: getFirestore(app), auth: getAuth(app), pepper: TEACHER_CODE_PEPPER.value() },
    uid,
    request.data,
  );
});

/** Desvinculacion inmediata por cualquiera de las partes (REQ-LNK-08); idempotente y reanudable. */
export const unlinkTeacher = onCall(callableOptions(), (request) => {
  const { uid } = requireVerifiedUser(request);
  const app = admin();
  return unlinkTeacherHandler({ db: getFirestore(app), auth: getAuth(app) }, uid, request.data);
});

/** Copia de nombre/instrumento en los vinculos (REQ-LNK-09); el handler descarta cualquier otro cambio sin leer. */
export const syncLinkNames = onDocumentUpdated({ document: "users/{uid}", region: REGION, maxInstances: 5, retry: true }, (event) =>
  syncLinkNamesHandler(
    { db: getFirestore(admin()) },
    event.params.uid,
    event.data?.before.data(),
    event.data?.after.data(),
  ),
);

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

/**
 * Valida cada objeto subido a `users/{uid}/recordings/` y `users/{uid}/demos/` y crea el doc `ready`/`rejected` (REQ-REC-05/06/14).
 * Idempotente y con reintentos (entrega al menos una vez): un fallo de Storage/Firestore se relanza; un rechazo no. La region
 * es la del bucket (spike Q5: `STORAGE_TRIGGER_REGION`). Filtra por prefijo dentro del handler (el trigger recibe todo el bucket).
 */
export const finalizeRecording = onObjectFinalized(
  { region: storageTriggerRegion(), retry: true, maxInstances: 10, timeoutSeconds: 120 },
  async (event) => {
    const app = admin();
    await finalizeRecordingHandler(
      {
        db: getFirestore(app),
        store: bucketStore(getStorage(app).bucket(event.data.bucket)),
        clock: systemClock,
        flags: recordingFlags(),
      },
      { name: event.data.name, generation: String(event.data.generation) },
    );
  },
);
