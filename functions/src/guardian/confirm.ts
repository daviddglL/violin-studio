import { Auth } from "firebase-admin/auth";
import { DocumentReference, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock, systemClock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { safeErrorCode } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { RecordingFlags, recordingFlags } from "../config/features";
import { CURRENT_POLICY_VERSION, GUARDIAN_MAX_CONFIRM_ATTEMPTS, GUARDIAN_REVOKE_LINK_TTL_DAYS, policyUrl, RECORDING_CONSENT_VERSION } from "../config/identity";
import { buildConsentDoc, consentDocId, consentEpochOf, recordingEpochOf } from "../consent/consent-docs";
import { isPurgePending, PurgeRecordingsHook, revokeRecordingByGuardian, runPurgeAfterRevocation } from "../consent/recording-consent";
import { blurRequiredFor } from "../identity/age";
import { isPermanent } from "../erasure/erase-user-data";
import { revokeConsentCore } from "../consent/revoke-consent";
import { syncClaims } from "../identity/claims";
import { guardianLinkBaseUrl } from "./config";
import { buildGuardianRevokeMail } from "./mail";
import { makeNonce, renderDeclarationPage, renderDonePage, renderInvalidPage, renderRecordingRejectedPage, renderRecordingRevokedPage, renderRejectConfirmPage, renderRejectedPage, renderRetryPage, renderRevokedPage, renderRevokePage, renderValidPage, securityHeaders } from "./page";
import { GuardianPurpose, purposeOf } from "./purpose";
import { generateToken, hashToken, verifyToken } from "./token";

export interface GuardianHttpRequest {
  method: string;
  query: Record<string, unknown>;
  /** Cuerpo del formulario ya decodificado (urlencoded). */
  body: Record<string, unknown>;
}
export interface GuardianHttpResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
}
export interface GuardianConsentDeps {
  db: Firestore;
  auth: Auth;
  clock?: Clock;
  currentVersion?: number;
  /** Interruptores de grabación (finalidad `recording_sharing`); por defecto los de `process.env`. */
  flags?: RecordingFlags;
  /** Base del enlace de revocación (Hosting); por defecto `guardianLinkBaseUrl()`. */
  linkBaseUrl?: string;
  /** Borrado completo del menor (cascada eraseUserData con deleteAuth:true); inyectado para probarlo y no acoplar el handler al bucket. */
  erase: (uid: string) => Promise<unknown>;
  /** Borrado efectivo de las grabaciones tras revocar la finalidad de grabación o la base (B5b.3); su fallo no deshace la revocación. */
  purgeRecordings?: PurgeRecordingsHook;
  /** Núcleo de revocación; inyectable solo para probar entrelazados y fallos transitorios. */
  revokeCore?: typeof revokeConsentCore;
  /** Sumidero de logs sin PII (solo uidHash y códigos); por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/** Formato real de los ids (autoId de Firestore, 20 alfanuméricos): se valida antes de cualquier `.doc()`. */
const DAY_MS = 24 * 3600_000;
const REQUEST_ID = /^[A-Za-z0-9]{20}$/;
/** Hash ficticio: el token se compara siempre, exista o no la solicitud, para que el coste de la comparación sea uniforme. */
const DUMMY_HASH = sha256Hex("guardian-dummy-token-hash");

const str = (v: unknown): string | undefined => (typeof v === "string" && v !== "" ? v : undefined);

/** Misma respuesta (código y cuerpo) para cualquier rechazo: no revela si el enlace existió, caducó, se usó o el token falló. */
const respond = (status: number, body: string, extra: Record<string, string> = {}): GuardianHttpResponse => ({
  status,
  headers: { ...securityHeaders(makeNonce()), ...extra },
  body,
});
const invalid = () => respond(404, renderInvalidPage());

/** Solicitud "abierta": sin usar, sin sustituir, vigente (`now < expiresAt`) y con intentos disponibles. */
const isOpen = (r: Record<string, unknown> | undefined, nowMs: number): r is Record<string, unknown> =>
  !!r &&
  !r.usedAt &&
  !r.supersededAt &&
  r.expiresAt instanceof Timestamp &&
  nowMs < r.expiresAt.toMillis() &&
  typeof r.attempts === "number" &&
  r.attempts < GUARDIAN_MAX_CONFIRM_ATTEMPTS;

/** El menor sigue esperando a ESTE tutor: perfil existente, sin borrado en curso y en `parental_pending` con esta solicitud. */
const awaiting = (u: Record<string, unknown> | undefined, requestId: string): u is Record<string, unknown> =>
  !!u && !u.deletion && u.isMinor === true && u.consentStatus === "parental_pending" && (u.guardian as { requestId?: unknown } | undefined)?.requestId === requestId;

/**
 * Enlace de revocación vigente: la solicitud fue ACEPTADA (tiene su propio token `revokeTokenHash`, distinto del de aceptación,
 * y su propio contador `revokeAttempts`), no se revocó ya, no ha caducado (`revokeExpiresAt`) y quedan intentos. No usa
 * `isOpen`: una solicitud aceptada tiene `usedAt`, y eso no debe invalidar la revocación.
 */
const isRevokeOpen = (r: Record<string, unknown> | undefined, nowMs: number): r is Record<string, unknown> =>
  !!r &&
  r.outcome === "accepted" &&
  typeof r.revokeTokenHash === "string" &&
  !r.revokedAt &&
  r.revokeExpiresAt instanceof Timestamp &&
  nowMs < r.revokeExpiresAt.toMillis() &&
  typeof r.revokeAttempts === "number" &&
  r.revokeAttempts < GUARDIAN_MAX_CONFIRM_ATTEMPTS;

/** El consentimiento activo es el de ESTA solicitud: menor `granted` sin borrado en curso y `guardian.requestId == r` (una solicitud nueva del menor invalida el enlace viejo). */
const grantedBy = (u: Record<string, unknown> | undefined, requestId: string): u is Record<string, unknown> =>
  !!u && !u.deletion && u.isMinor === true && u.consentStatus === "granted" && (u.guardian as { requestId?: unknown } | undefined)?.requestId === requestId;

/** El interruptor de grabación de menores y el general deben estar encendidos para que la finalidad de grabación funcione. */
const recordingOn = (deps: GuardianConsentDeps): boolean => {
  const f = deps.flags ?? recordingFlags();
  return f.recordingsEnabled && f.minorRecordingEnabled;
};

/**
 * El menor sigue esperando a ESTE tutor para la grabación: menor (<14) con la base vigente `granted` y `recordingConsent =
 * parental_pending` de esta solicitud. Si cumplió 14 (`isMinor` falso), concedió por sí mismo o la base cambió (revocada,
 * política nueva) el enlace deja de valer: `revokeConsentCore` ya restablece el `parental_pending` de grabación; la comprobación aquí cubre además la política nueva.
 */
const recordingAwaiting = (u: Record<string, unknown> | undefined, requestId: string, version: number): u is Record<string, unknown> =>
  !!u &&
  !u.deletion &&
  u.isMinor === true &&
  u.consentStatus === "granted" &&
  u.policyVersion === version &&
  u.recordingConsent === "parental_pending" &&
  (u.recordingGuardian as { requestId?: unknown } | undefined)?.requestId === requestId;

/** La concesión de grabación activa es la de ESTA solicitud (para su enlace de revocación; no exige `isMinor`). */
const recordingGrantedBy = (u: Record<string, unknown> | undefined, requestId: string): u is Record<string, unknown> =>
  !!u && !u.deletion && u.recordingConsent === "granted" && (u.recordingGuardian as { requestId?: unknown } | undefined)?.requestId === requestId;

/**
 * Página del tutor (`GET`, sin efectos: ni siquiera cuenta intentos, el token viaja en el fragmento y no llega) y
 * confirmación (`POST` accept). Las decisiones se toman sobre los docs de Firestore en UNA transacción.
 */
export async function guardianConsentHandler(deps: GuardianConsentDeps, req: GuardianHttpRequest): Promise<GuardianHttpResponse> {
  if (req.method !== "GET" && req.method !== "HEAD" && req.method !== "POST") return respond(405, renderInvalidPage(), { Allow: "GET, HEAD, POST" });
  try {
    // HEAD = GET (express omite el cuerpo al responder).
    return req.method !== "POST" ? await renderGet(deps, str(req.query.r)) : await confirm(deps, req.body);
  } catch (e) {
    // Ninguna entrada puede producir un 500; solo el código (el mensaje puede contener rutas con uid).
    (deps.log ?? ((m, d) => logger.info(m, d)))("guardianConsent.error", { code: safeErrorCode(e) });
    return invalid();
  }
}

async function renderGet(deps: GuardianConsentDeps, requestId: string | undefined): Promise<GuardianHttpResponse> {
  if (!requestId || !REQUEST_ID.test(requestId)) return invalid();
  const nowMs = (deps.clock ?? systemClock)().getTime();
  const r = (await deps.db.collection(COLLECTIONS.guardianRequests).doc(requestId).get()).data();
  if (typeof r?.uid !== "string") return invalid();
  const user = (await deps.db.collection(COLLECTIONS.users).doc(r.uid).get()).data();
  const purpose = purposeOf(r);
  const recording = purpose === "recording_sharing";
  const version = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  // El servidor decide el modo por el estado (el `a=revoke` del fragmento es solo informativo): pendiente -> aceptar/rechazar; aceptada -> revocar.
  if (isRevokeOpen(r, nowMs) && (recording ? recordingGrantedBy(user, requestId) : grantedBy(user, requestId))) {
    const nonce = makeNonce();
    return {
      status: 200,
      headers: securityHeaders(nonce),
      body: renderRevokePage({ requestId, displayName: String(user?.displayName ?? ""), locale: String(user?.locale ?? "en"), nonce, purpose }),
    };
  }
  if (!isOpen(r, nowMs)) return invalid();
  if (recording ? !(recordingOn(deps) && recordingAwaiting(user, requestId, version)) : !awaiting(user, requestId)) return invalid();
  const nonce = makeNonce();
  return {
    status: 200,
    headers: securityHeaders(nonce),
    body: renderValidPage({
      requestId,
      displayName: String(user?.displayName ?? ""),
      locale: String(user?.locale ?? "en"),
      policyUrl: policyUrl(),
      policyVersion: version,
      nonce,
      purpose,
    }),
  };
}

/** Resultado de la transacción de `confirm`: nada que hacer, reintentable (purga en curso) o el menor y la finalidad aplicada. */
type ConfirmOutcome = null | "retry" | { uid: string; purpose: GuardianPurpose };

async function confirm(deps: GuardianConsentDeps, body: Record<string, unknown>): Promise<GuardianHttpResponse> {
  const requestId = str(body.r);
  const token = str(body.t);
  if (!requestId || !token || !REQUEST_ID.test(requestId) || (body.action !== "accept" && body.action !== "reject" && body.action !== "revoke")) return invalid();
  if (body.action === "revoke") return revoke(deps, requestId, token);
  const rejecting = body.action === "reject";
  if (rejecting && body.confirm !== "yes") return renderRejectConfirm(deps, requestId, token);
  // La declaración (solo al aceptar) se comprueba antes de leer nada: no revela nada del enlace y no consume el token.
  if (!rejecting && body.declaration !== "on" && body.declaration !== "true") return respond(400, renderDeclarationPage());

  const now = (deps.clock ?? systemClock)();
  const nowMs = now.getTime();
  const version = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  const requestRef = deps.db.collection(COLLECTIONS.guardianRequests).doc(requestId);
  // Token de revocación: solo su hash se guarda en la solicitud; el claro viaja únicamente en el segundo `mail/`.
  const revokeToken = generateToken();
  const revokeMailRef = deps.db.collection(COLLECTIONS.mail).doc();
  let revokeBase: string | undefined;
  if (!rejecting) revokeBase = deps.linkBaseUrl ?? guardianLinkBaseUrl();
  let noRevokeLink = false;

  const outcome = await deps.db.runTransaction(async (tx): Promise<ConfirmOutcome> => {
    const r = (await tx.get(requestRef)).data();
    // Comparación siempre (hash ficticio si no hay solicitud). El coste de escritura de `attempts++` no se puede ocultar:
    // es un oráculo débil aceptado (solo distingue "solicitud abierta con token erróneo").
    const tokenOk = verifyToken(token, typeof r?.tokenHash === "string" ? r.tokenHash : DUMMY_HASH);
    if (!isOpen(r, nowMs) || typeof r.uid !== "string") return null;
    const purpose = purposeOf(r);
    const recording = purpose === "recording_sharing";
    const userRef = deps.db.collection(COLLECTIONS.users).doc(r.uid);
    const user = (await tx.get(userRef)).data();
    // Todas las lecturas ya están hechas: a partir de aquí solo escrituras.
    if (!tokenOk) {
      tx.update(requestRef, { attempts: FieldValue.increment(1) });
      return null;
    }
    if (!user || (recording ? !(recordingOn(deps) && recordingAwaiting(user, requestId, version)) : !awaiting(user, requestId))) return null;
    if (rejecting) {
      if (recording) {
        // REQ-GRD-R03: solo se cierra la solicitud y `recordingConsent` vuelve a `none` (campo ausente); ni `eraseUserData` ni `consentStatus`.
        tx.update(requestRef, { usedAt: Timestamp.fromDate(now), outcome: "rejected" });
        tx.update(userRef, { recordingConsent: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp() });
        return { uid: r.uid, purpose };
      }
      // Solicitud usada y perfil con "deletion" EN LA MISMA transacción, antes de borrar: el token no se puede
      // reutilizar y, si la cascada falla, el marcador permite reanudarla (la purga 7b.3 reintenta los in_progress);
      // sin él, un fallo transitorio dejaría al menor sin borrar y sin marcador.
      tx.update(requestRef, { usedAt: Timestamp.fromDate(now), outcome: "rejected" });
      tx.update(userRef, { deletion: { state: "in_progress", startedAt: Timestamp.fromDate(now) } });
      return { uid: r.uid, purpose };
    }
    // Lecturas (antes de cualquier escritura): destinatario del primer correo y, en grabación, el consentimiento base.
    const firstMail = typeof r.mailId === "string" && r.mailId ? (await tx.get(deps.db.collection(COLLECTIONS.mail).doc(r.mailId))).data() : undefined;
    if (recording) {
      // Una purga de una revocación anterior aún en curso mezclaría las grabaciones nuevas con el borrado (igual que `grantRecordingConsent`): reintentable, sin gastar el token.
      if ((user.recordingPurge as { pending?: unknown } | undefined)?.pending === true) return "retry";
      // El tutor sigue siendo el del consentimiento base vigente (podría haberse revocado y vuelto a conceder con otro email).
      const baseId = consentDocId("guardian_privacy_policy", version, "guardian", consentEpochOf(user));
      const base = (await tx.get(userRef.collection(COLLECTIONS.consents).doc(baseId))).data();
      if (typeof base?.guardianEmailHmac !== "string" || base.guardianEmailHmac !== r.guardianEmailHmac) return null;
    }
    const consentRef: DocumentReference = recording
      ? userRef.collection(COLLECTIONS.consents).doc(consentDocId("recording_sharing", RECORDING_CONSENT_VERSION, "guardian", recordingEpochOf(user)))
      : userRef.collection(COLLECTIONS.consents).doc(consentDocId("guardian_privacy_policy", version, "guardian", consentEpochOf(user)));
    tx.create(consentRef, {
      ...(recording ? buildConsentDoc("recording_sharing", RECORDING_CONSENT_VERSION, "guardian") : buildConsentDoc("guardian_privacy_policy", version, "guardian")),
      guardianEmailHmac: r.guardianEmailHmac,
      declaration: "legal_guardian",
    });
    const revokeExpiresAt = Timestamp.fromMillis(nowMs + GUARDIAN_REVOKE_LINK_TTL_DAYS * DAY_MS);
    const to = firstMail?.to;
    // Sin correo origen (solicitud anterior a esta versión, o ya retirado) no hay destinatario: se acepta igualmente, sin revocación por enlace.
    const revoke =
      typeof to === "string" && to !== ""
        ? {
            revokeTokenHash: hashToken(revokeToken),
            revokeExpiresAt,
            revokeAttempts: 0,
            revokedAt: null,
            // La TTL no puede retirar la solicitud antes de que el enlace de revocación deje de valer.
            expireAt: Timestamp.fromMillis(revokeExpiresAt.toMillis() + 7 * DAY_MS),
          }
        : null;
    noRevokeLink = !revoke;
    if (revoke) {
      tx.create(
        revokeMailRef,
        buildGuardianRevokeMail({
          to: to as string,
          locale: String(user.locale ?? "en"),
          link: `${revokeBase}/tutor?r=${requestId}#t=${revokeToken}&a=revoke`,
          displayName: String(user.displayName ?? ""),
          uid: r.uid,
          now,
          purpose,
        }),
      );
    }
    tx.update(requestRef, { usedAt: Timestamp.fromDate(now), outcome: "accepted", ...revoke });
    if (recording) {
      // Atómico con el consent del tutor; no toca `consentStatus` ni los claims. `blurRequired` se recalcula desde la fecha de nacimiento (fail-closed).
      tx.update(userRef, {
        recordingConsent: "granted",
        recordingConsentVersion: RECORDING_CONSENT_VERSION,
        blurRequired: blurRequiredFor(user.birthDate, now),
        updatedAt: FieldValue.serverTimestamp(),
      });
    } else {
      tx.update(userRef, { consentStatus: "granted", policyVersion: version, updatedAt: FieldValue.serverTimestamp() });
    }
    return { uid: r.uid, purpose };
  });
  if (outcome === "retry") return respond(503, renderRetryPage(), { "Retry-After": "60" });
  if (!outcome) return invalid();
  const { uid, purpose } = outcome;

  const log = deps.log ?? ((m, d) => logger.info(m, d));
  const uidHash = sha256Hex(uid).slice(0, 12);
  if (rejecting && purpose === "recording_sharing") {
    log("guardianConsent.recordingRejected", { uidHash });
    return respond(200, renderRecordingRejectedPage());
  }
  if (rejecting) {
    try {
      await deps.erase(uid);
    } catch (e) {
      // Nunca el mensaje (puede llevar rutas con uid) ni un 500: el rechazo ya consta y la purga reanuda el borrado.
      log("guardianConsent.eraseFailed", { uidHash, code: safeErrorCode(e) });
    }
    log("guardianConsent.rejected", { uidHash });
    return respond(200, renderRejectedPage());
  }
  if (purpose === "privacy_policy") {
    try {
      await syncClaims({ db: deps.db, auth: deps.auth, policyVersion: version }, uid);
    } catch (e) {
      // El consentimiento ya está confirmado; `identityConfig` cura los claims en el siguiente arranque del menor.
      log("guardianConsent.syncFailed", { uidHash, code: safeErrorCode(e) });
    }
  }
  if (noRevokeLink) log("guardianConsent.noRevokeLink", { uidHash });
  log(purpose === "privacy_policy" ? "guardianConsent.accepted" : "guardianConsent.recordingAccepted", { uidHash });
  return respond(200, renderDonePage(!noRevokeLink));
}

/**
 * Primer paso del rechazo (irreversible): solo de lectura. Exige enlace abierto y menor pendiente (si no, el mismo 404
 * genérico) pero NO verifica el token ni cuenta intentos: así esta página no es un oráculo de tokens. El token que
 * se reenvía es el que el propio tutor envió; la comprobación real ocurre en el segundo paso (confirm=yes).
 */
async function renderRejectConfirm(deps: GuardianConsentDeps, requestId: string, token: string): Promise<GuardianHttpResponse> {
  const nowMs = (deps.clock ?? systemClock)().getTime();
  const r = (await deps.db.collection(COLLECTIONS.guardianRequests).doc(requestId).get()).data();
  if (!isOpen(r, nowMs) || typeof r.uid !== "string") return invalid();
  const user = (await deps.db.collection(COLLECTIONS.users).doc(r.uid).get()).data();
  if (!awaiting(user, requestId)) return invalid();
  return respond(200, renderRejectConfirmPage({ requestId, token, displayName: String(user.displayName ?? ""), locale: String(user.locale ?? "en") }));
}

/**
 * Revocación del tutor (un solo paso: la página GET ya es la confirmación y el efecto es reversible, el menor puede volver
 * a pedir consentimiento; el rechazo, irreversible, sí va en dos pasos). El token de revocación es DISTINTO del de aceptación
 * y tiene su propio contador (`revokeAttempts`): agotar los intentos de aceptar no bloquea la revocación y viceversa.
 * Bloqueo: 5 tokens erróneos inhabilitan el enlace de forma permanente (aceptado, igual que en aceptar; cualquiera con `r` puede
 * provocarlo). Alternativas del tutor: pedir al menor que revoque desde la app (2b permite a un menor autorrevocar) o que reinicie
 * el ciclo con una solicitud nueva; la página genérica lo indica con una frase estática que no revela nada del enlace.
 * Verificación en una transacción (con `revokeAttempts++` si el token es erróneo); si es válido se llama a
 * `revokeConsentCore(..., "guardian")` fuera de ella (su propia transacción comprueba `granted`: de dos peticiones simultáneas
 * gana una y la otra recibe NO_ACTIVE_CONSENT -> genérico). El token no se quema antes de revocar: si el núcleo falla de forma
 * transitoria el tutor puede reintentar; el enlace queda inservible porque exige `granted` (y `revokedAt` se marca después).
 */
async function revoke(deps: GuardianConsentDeps, requestId: string, token: string): Promise<GuardianHttpResponse> {
  const nowMs = (deps.clock ?? systemClock)().getTime();
  const requestRef = deps.db.collection(COLLECTIONS.guardianRequests).doc(requestId);
  const target = await deps.db.runTransaction(async (tx): Promise<{ uid: string; purpose: GuardianPurpose; active: boolean; purgePending: boolean } | null> => {
    const r = (await tx.get(requestRef)).data();
    const tokenOk = verifyToken(token, typeof r?.revokeTokenHash === "string" ? r.revokeTokenHash : DUMMY_HASH);
    if (!isRevokeOpen(r, nowMs) || typeof r.uid !== "string") return null;
    const user = (await tx.get(deps.db.collection(COLLECTIONS.users).doc(r.uid))).data();
    if (!tokenOk) {
      tx.update(requestRef, { revokeAttempts: FieldValue.increment(1) });
      return null;
    }
    const purpose = purposeOf(r);
    const active = purpose === "recording_sharing" ? recordingGrantedBy(user, requestId) : grantedBy(user, requestId);
    return { uid: r.uid, purpose, active, purgePending: !!user && !user.deletion && isPurgePending(user) };
  });
  if (!target) return invalid();
  const { uid, purpose } = target;
  const log = deps.log ?? ((m, d) => logger.info(m, d));
  if (!target.active) {
    // W1 (revision fresca B5b): reintento tras un 503 posterior al commit (la revocacion ya consta, el enlace sigue abierto
    // porque `revokedAt` no llego a marcarse): quien demostro el token reanuda la purga de grabaciones que siga pendiente.
    if (target.purgePending) await runPurgeAfterRevocation(deps.purgeRecordings, uid, log);
    return invalid();
  }
  const uidHash = sha256Hex(uid).slice(0, 12);
  try {
    if (purpose === "recording_sharing") {
      // Solo la grabación: el consentimiento base y la cuenta no se tocan (REQ-CON-R02).
      await revokeRecordingByGuardian({ db: deps.db, log, purgeRecordings: deps.purgeRecordings }, uid, requestId);
    } else {
      await (deps.revokeCore ?? revokeConsentCore)({ db: deps.db, auth: deps.auth, currentVersion: deps.currentVersion, log, purgeRecordings: deps.purgeRecordings }, uid, "guardian", { expectGuardianRequestId: requestId });
    }
  } catch (e) {
    log("guardianConsent.revokeFailed", { uidHash, code: safeErrorCode(e) });
    // El tutor ya demostró poseer el token: ante un fallo transitorio se le dice que reintente (503) en vez de "enlace no válido".
    // Los permanentes y las precondiciones (p. ej. NO_ACTIVE_CONSENT) siguen siendo el genérico.
    return isPermanent(e) ? invalid() : respond(503, renderRetryPage(), { "Retry-After": "60" });
  }
  try {
    await requestRef.update({ revokedAt: Timestamp.fromDate((deps.clock ?? systemClock)()) });
  } catch (e) {
    log("guardianConsent.revokeMarkFailed", { uidHash, code: safeErrorCode(e) }); // la revocación ya consta; el enlace exige `granted`
  }
  log("guardianConsent.revoked", { uidHash, purpose });
  return respond(200, purpose === "recording_sharing" ? renderRecordingRevokedPage() : renderRevokedPage());
}
