import { FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock, systemClock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { hmacEmail, normalizeEmail, sha256Hex } from "../common/hashing";
import { requireObject, requireString } from "../common/validation";
import {
  CURRENT_POLICY_VERSION,
  GUARDIAN_LINK_TTL_HOURS,
  GUARDIAN_MAX_SENDS_PER_24H,
} from "../config/identity";
import { buildGuardianMail, maskEmail } from "./mail";
import { requirePepper } from "./pepper";
import { checkRateLimit } from "./rate-limit";
import { generateToken, hashToken } from "./token";

const HOUR_MS = 3600_000;
const DAY_MS = 24 * HOUR_MS;
// Sin separadores de lista/cabecera, comillas, paréntesis, corchetes, espacios ni controles; una sola @ y un punto en el dominio.
const BAD = String.raw`\s@,;<>"\\()\[\]\x00-\x1f\x7f`;
const EMAIL_SHAPE = new RegExp(`^[^${BAD}]+@[^${BAD}.]+(\\.[^${BAD}.]+)+$`);

export interface RequestGuardianDeps {
  db: Firestore;
  /** Valor del secreto GUARDIAN_EMAIL_PEPPER. */
  pepper: string;
  /** Base pública del enlace (Hosting); el token va en el fragmento. */
  linkBaseUrl: string;
  guardianFlowEnabled: boolean;
  clock?: Clock;
  currentVersion?: number;
  /** Sumidero de logs sin PII (solo uidHash y contadores); por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

const toMillis = (v: unknown): number[] =>
  Array.isArray(v) ? v.filter((t): t is Timestamp => t instanceof Timestamp).map((t) => t.toMillis()) : [];

/**
 * Un menor pide consentimiento a un tutor. Transacción única: límites (cuenta y destino), sustitución de la
 * solicitud abierta anterior, nueva solicitud (solo `sha256(token)`), doc `mail/` (único sitio con el token
 * en claro) y `parental_pending`. Todo doc con propietario guarda `uid` para la cascada de borrado.
 */
export async function requestGuardianConsentHandler(
  deps: RequestGuardianDeps,
  uid: string,
  ownEmail: string | undefined,
  data: unknown,
): Promise<{ status: "sent"; emailMasked: string }> {
  if (!deps.guardianFlowEnabled) throw fail("failed-precondition", ErrorReason.UNDERAGE_NOT_ALLOWED);
  const rawEmail = requireObject(data).guardianEmail;
  // El valor validado, el HMAC, el de la comparación y el de `mail.to` son exactamente el mismo string normalizado.
  const guardianEmail = requireString({ guardianEmail: typeof rawEmail === "string" ? normalizeEmail(rawEmail) : rawEmail }, "guardianEmail", {
    min: 3,
    max: 254,
    pattern: EMAIL_SHAPE,
  });
  if (guardianEmail === (ownEmail === undefined ? undefined : normalizeEmail(ownEmail))) throw fail("invalid-argument", ErrorReason.GUARDIAN_EMAIL_INVALID);
  const hmac = hmacEmail(requirePepper(deps.pepper), guardianEmail);

  const now = (deps.clock ?? systemClock)();
  const nowMs = now.getTime();
  const token = generateToken();
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const limitRef = deps.db.collection(COLLECTIONS.guardianEmailLimits).doc(hmac);
  const requestRef = deps.db.collection(COLLECTIONS.guardianRequests).doc();
  const mailRef = deps.db.collection(COLLECTIONS.mail).doc();

  const sendsCount = await deps.db.runTransaction(async (tx) => {
    const [userSnap, limitSnap] = await Promise.all([tx.get(userRef), tx.get(limitRef)]);
    const doc = userSnap.data();
    if (!doc || doc.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
    if (doc.isMinor !== true) throw fail("failed-precondition", ErrorReason.NOT_MINOR);
    if (doc.consentStatus === "granted" && doc.policyVersion === (deps.currentVersion ?? CURRENT_POLICY_VERSION)) {
      throw fail("failed-precondition", ErrorReason.CONSENT_ALREADY_GRANTED);
    }

    // `users.guardian.sends` es el contador por cuenta: ningún otro flujo (3b/3c/7b) debe borrarlo ni reiniciarlo.
    const account = checkRateLimit(toMillis(doc.guardian?.sends), nowMs, GUARDIAN_MAX_SENDS_PER_24H, DAY_MS);
    const target = checkRateLimit(toMillis(limitSnap.data()?.sends), nowMs, GUARDIAN_MAX_SENDS_PER_24H, DAY_MS);
    if (!account.allowed || !target.allowed) {
      const retryAfterSeconds = Math.max(account.retryAfterSeconds ?? 0, target.retryAfterSeconds ?? 0);
      throw fail("resource-exhausted", ErrorReason.RATE_LIMITED, { retryAfterSeconds });
    }

    const previousId = doc.guardian?.requestId;
    const previous = typeof previousId === "string" ? deps.db.collection(COLLECTIONS.guardianRequests).doc(previousId) : null;
    const previousSnap = previous ? await tx.get(previous) : null;

    const expiresAt = new Date(nowMs + GUARDIAN_LINK_TTL_HOURS * HOUR_MS);
    if (previous && previousSnap?.exists && !previousSnap.data()?.usedAt && !previousSnap.data()?.supersededAt) {
      tx.update(previous, { supersededAt: Timestamp.fromDate(now) });
    }
    tx.create(requestRef, {
      uid,
      tokenHash: hashToken(token),
      guardianEmailHmac: hmac,
      createdAt: Timestamp.fromDate(now),
      expiresAt: Timestamp.fromDate(expiresAt),
      expireAt: Timestamp.fromMillis(expiresAt.getTime() + 7 * DAY_MS),
      usedAt: null,
      outcome: null,
      supersededAt: null,
      attempts: 0,
      // Al aceptar, 3c-bis lee el destinatario del primer correo (el email en claro no se guarda en la solicitud, solo su HMAC).
      mailId: mailRef.id,
    });
    tx.create(
      mailRef,
      buildGuardianMail({
        to: guardianEmail,
        locale: String(doc.locale ?? "en"),
        link: `${deps.linkBaseUrl}/tutor?r=${requestRef.id}#t=${token}`,
        displayName: String(doc.displayName ?? ""),
        uid,
        now,
      }),
    );
    tx.set(limitRef, { sends: target.sends.map((t) => Timestamp.fromMillis(t)), expireAt: Timestamp.fromMillis(nowMs + DAY_MS) });
    tx.update(userRef, {
      consentStatus: "parental_pending",
      guardian: {
        emailMasked: maskEmail(guardianEmail),
        requestId: requestRef.id,
        requestedAt: Timestamp.fromDate(now),
        sends: account.sends.map((t) => Timestamp.fromMillis(t)),
      },
      updatedAt: FieldValue.serverTimestamp(),
    });
    return account.sends.length;
  });

  (deps.log ?? ((m, d) => logger.info(m, d)))("requestGuardianConsent", {
    uidHash: sha256Hex(uid).slice(0, 12),
    sends: sendsCount,
  });
  return { status: "sent", emailMasked: maskEmail(guardianEmail) };
}
