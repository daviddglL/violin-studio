import { Auth } from "firebase-admin/auth";
import { DocumentReference, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock, systemClock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { CURRENT_POLICY_VERSION, GUARDIAN_MAX_CONFIRM_ATTEMPTS, POLICY_URL } from "../config/identity";
import { buildConsentDoc, consentDocId, consentEpochOf } from "../consent/consent-docs";
import { syncClaims } from "../identity/claims";
import { makeNonce, renderDeclarationPage, renderDonePage, renderInvalidPage, renderRejectedPage, renderValidPage, securityHeaders } from "./page";
import { verifyToken } from "./token";

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
  /** Borrado completo del menor (cascada eraseUserData con deleteAuth:true); inyectado para probarlo y no acoplar el handler al bucket. */
  erase: (uid: string) => Promise<unknown>;
  /** Sumidero de logs sin PII (solo uidHash y códigos); por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/** Formato real de los ids (autoId de Firestore, 20 alfanuméricos): se valida antes de cualquier `.doc()`. */
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
 * Página del tutor (`GET`, sin efectos: ni siquiera cuenta intentos, el token viaja en el fragmento y no llega) y
 * confirmación (`POST` accept). Las decisiones se toman sobre los docs de Firestore en UNA transacción.
 */
export async function guardianConsentHandler(deps: GuardianConsentDeps, req: GuardianHttpRequest): Promise<GuardianHttpResponse> {
  if (req.method !== "GET" && req.method !== "POST") return respond(405, renderInvalidPage(), { Allow: "GET, POST" });
  try {
    return req.method === "GET" ? await renderGet(deps, str(req.query.r)) : await confirm(deps, req.body);
  } catch (e) {
    // Ninguna entrada puede producir un 500; solo el código (el mensaje puede contener rutas con uid).
    (deps.log ?? ((m, d) => logger.info(m, d)))("guardianConsent.error", { code: (e as { code?: unknown }).code ?? "unknown" });
    return invalid();
  }
}

async function renderGet(deps: GuardianConsentDeps, requestId: string | undefined): Promise<GuardianHttpResponse> {
  if (!requestId || !REQUEST_ID.test(requestId)) return invalid();
  const nowMs = (deps.clock ?? systemClock)().getTime();
  const r = (await deps.db.collection(COLLECTIONS.guardianRequests).doc(requestId).get()).data();
  if (!isOpen(r, nowMs) || typeof r.uid !== "string") return invalid();
  const user = (await deps.db.collection(COLLECTIONS.users).doc(r.uid).get()).data();
  if (!awaiting(user, requestId)) return invalid();
  const nonce = makeNonce();
  return {
    status: 200,
    headers: securityHeaders(nonce),
    body: renderValidPage({
      requestId,
      displayName: String(user.displayName ?? ""),
      locale: String(user.locale ?? "en"),
      policyUrl: POLICY_URL,
      policyVersion: deps.currentVersion ?? CURRENT_POLICY_VERSION,
      nonce,
    }),
  };
}

async function confirm(deps: GuardianConsentDeps, body: Record<string, unknown>): Promise<GuardianHttpResponse> {
  const requestId = str(body.r);
  const token = str(body.t);
  if (!requestId || !token || !REQUEST_ID.test(requestId) || (body.action !== "accept" && body.action !== "reject")) return invalid();
  const rejecting = body.action === "reject";
  // La declaración (solo al aceptar) se comprueba antes de leer nada: no revela nada del enlace y no consume el token.
  if (!rejecting && body.declaration !== "on" && body.declaration !== "true") return respond(400, renderDeclarationPage());

  const now = (deps.clock ?? systemClock)();
  const nowMs = now.getTime();
  const version = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  const requestRef = deps.db.collection(COLLECTIONS.guardianRequests).doc(requestId);

  const uid = await deps.db.runTransaction(async (tx): Promise<string | null> => {
    const r = (await tx.get(requestRef)).data();
    // Comparación siempre (hash ficticio si no hay solicitud). El coste de escritura de `attempts++` no se puede ocultar:
    // es un oráculo débil aceptado (solo distingue "solicitud abierta con token erróneo").
    const tokenOk = verifyToken(token, typeof r?.tokenHash === "string" ? r.tokenHash : DUMMY_HASH);
    if (!isOpen(r, nowMs) || typeof r.uid !== "string") return null;
    const userRef = deps.db.collection(COLLECTIONS.users).doc(r.uid);
    const user = (await tx.get(userRef)).data();
    // Todas las lecturas ya están hechas: a partir de aquí solo escrituras.
    if (!tokenOk) {
      tx.update(requestRef, { attempts: FieldValue.increment(1) });
      return null;
    }
    if (!awaiting(user, requestId)) return null;
    if (rejecting) {
      // Solicitud usada y perfil con "deletion" EN LA MISMA transacción, antes de borrar: el token no se puede
      // reutilizar y, si la cascada falla, el marcador permite reanudarla (la purga 7b.3 reintenta los in_progress);
      // sin él, un fallo transitorio dejaría al menor sin borrar y sin marcador.
      tx.update(requestRef, { usedAt: Timestamp.fromDate(now), outcome: "rejected" });
      tx.update(userRef, { deletion: { state: "in_progress", startedAt: Timestamp.fromDate(now) } });
      return r.uid;
    }
    const consentRef: DocumentReference = userRef
      .collection(COLLECTIONS.consents)
      .doc(consentDocId("guardian_privacy_policy", version, "guardian", consentEpochOf(user)));
    tx.create(consentRef, {
      ...buildConsentDoc("guardian_privacy_policy", version, "guardian"),
      guardianEmailHmac: r.guardianEmailHmac,
      declaration: "legal_guardian",
    });
    tx.update(requestRef, { usedAt: Timestamp.fromDate(now), outcome: "accepted" });
    tx.update(userRef, { consentStatus: "granted", policyVersion: version, updatedAt: FieldValue.serverTimestamp() });
    return r.uid;
  });
  if (!uid) return invalid();

  const log = deps.log ?? ((m, d) => logger.info(m, d));
  const uidHash = sha256Hex(uid).slice(0, 12);
  if (rejecting) {
    try {
      await deps.erase(uid);
    } catch (e) {
      // Nunca el mensaje (puede llevar rutas con uid) ni un 500: el rechazo ya consta y la purga reanuda el borrado.
      log("guardianConsent.eraseFailed", { uidHash, code: (e as { code?: unknown }).code ?? "unknown" });
    }
    log("guardianConsent.rejected", { uidHash });
    return respond(200, renderRejectedPage());
  }
  try {
    await syncClaims({ db: deps.db, auth: deps.auth, policyVersion: version }, uid);
  } catch (e) {
    // El consentimiento ya está confirmado; `identityConfig` cura los claims en el siguiente arranque del menor.
    log("guardianConsent.syncFailed", { uidHash, code: (e as { code?: unknown }).code ?? "unknown" });
  }
  log("guardianConsent.accepted", { uidHash });
  return respond(200, renderDonePage());
}
