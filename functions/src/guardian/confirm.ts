import { Auth } from "firebase-admin/auth";
import { DocumentReference, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock, systemClock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { CURRENT_POLICY_VERSION, GUARDIAN_MAX_CONFIRM_ATTEMPTS, POLICY_URL } from "../config/identity";
import { buildConsentDoc, consentDocId, consentEpochOf } from "../consent/consent-docs";
import { syncClaims } from "../identity/claims";
import { makeNonce, renderDeclarationPage, renderDonePage, renderInvalidPage, renderValidPage, securityHeaders } from "./page";
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
  /** Sumidero de logs sin PII (solo uidHash y códigos); por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

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
  if (req.method === "GET") return renderGet(deps, str(req.query.r));
  if (req.method !== "POST") return respond(405, renderInvalidPage(), { Allow: "GET, POST" });
  return confirm(deps, req.body);
}

async function renderGet(deps: GuardianConsentDeps, requestId: string | undefined): Promise<GuardianHttpResponse> {
  if (!requestId || requestId.includes("/")) return invalid();
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
  if (!requestId || !token || requestId.includes("/") || body.action !== "accept") return invalid();
  // La declaración se comprueba antes de leer nada: no revela nada del enlace y no consume el token.
  if (!str(body.declaration)) return respond(400, renderDeclarationPage());

  const now = (deps.clock ?? systemClock)();
  const nowMs = now.getTime();
  const version = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  const requestRef = deps.db.collection(COLLECTIONS.guardianRequests).doc(requestId);

  const uid = await deps.db.runTransaction(async (tx): Promise<string | null> => {
    const r = (await tx.get(requestRef)).data();
    if (!isOpen(r, nowMs) || typeof r.uid !== "string") return null;
    const userRef = deps.db.collection(COLLECTIONS.users).doc(r.uid);
    const user = (await tx.get(userRef)).data();
    // Todas las lecturas ya están hechas: a partir de aquí solo escrituras.
    if (typeof r.tokenHash !== "string" || !verifyToken(token, r.tokenHash)) {
      tx.update(requestRef, { attempts: FieldValue.increment(1) });
      return null;
    }
    if (!awaiting(user, requestId)) return null;
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
  try {
    await syncClaims({ db: deps.db, auth: deps.auth, policyVersion: version }, uid);
  } catch (e) {
    // El consentimiento ya está confirmado; `identityConfig` cura los claims en el siguiente arranque del menor.
    log("guardianConsent.syncFailed", { uidHash, code: (e as { code?: unknown }).code ?? "unknown" });
  }
  log("guardianConsent.accepted", { uidHash });
  return respond(200, renderDonePage());
}
