import { GUARDIAN_LINK_TTL_HOURS, GUARDIAN_REVOKE_LINK_TTL_DAYS } from "../config/identity";

const LINK_TTL_MS = GUARDIAN_LINK_TTL_HOURS * 3600_000;

export const escapeHtml = (s: string) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");

export interface GuardianMailInput {
  to: string;
  locale: string;
  link: string;
  displayName: string;
  uid: string;
  now: Date;
}

/**
 * Doc de `mail/` (Trigger Email). `uid` es el propietario para la cascada de borrado (que borra también el token
 * en claro). El enlace lleva el token en el fragmento y vive en `mail/` hasta `expireAt`, alineado con la validez
 * del enlace (72 h) para que la TTL no retire un envío pendiente o en reintento antes de que el enlace caduque.
 */
export function buildGuardianMail({ to, locale, link, displayName, uid, now }: GuardianMailInput) {
  const es = locale.toLowerCase().startsWith("es");
  const subject = es
    ? `${displayName} solicita tu consentimiento como tutor legal`
    : `${displayName} is asking for your consent as legal guardian`;
  const intro = es
    ? `${displayName} quiere usar Violin Studio y necesita el consentimiento de su madre, padre o tutor legal. Abre este enlace (válido 72 horas) para revisarlo:`
    : `${displayName} wants to use Violin Studio and needs the consent of a parent or legal guardian. Open this link (valid for 72 hours) to review it:`;
  const ignore = es ? "Si no esperabas este correo, ignóralo." : "If you were not expecting this email, ignore it.";
  return {
    to,
    uid,
    kind: "guardian_consent" as const,
    expireAt: new Date(now.getTime() + LINK_TTL_MS),
    message: {
      subject,
      text: `${intro}\n${link}\n\n${ignore}`,
      html: `<p>${escapeHtml(intro)}</p><p><a href="${escapeHtml(link)}">${escapeHtml(link)}</a></p><p>${escapeHtml(ignore)}</p>`,
    },
  };
}

/** `pepe@gmail.com` -> `p***@g***.com`; el dominio sin punto queda `l***`. */
export function maskEmail(email: string): string {
  const [local, domain = ""] = email.trim().split("@");
  const dot = domain.lastIndexOf(".");
  const host = dot > 0 ? `${domain[0]}***.${domain.slice(dot + 1)}` : `${domain[0] ?? ""}***`;
  return `${local[0] ?? ""}***@${host}`;
}

/**
 * Segundo correo al tutor (tras aceptar): enlace para revocar el consentimiento más adelante. El ENLACE vale
 * `GUARDIAN_REVOKE_LINK_TTL_DAYS` días (vive en la bandeja del tutor), pero el DOC `mail/` solo existe para entregar y
 * reintentar el envío: se retira a las 72 h (como el primero) para no conservar en Firestore 30 días un token en claro
 * que ya no hace falta. La cascada por `uid` lo borra antes si el menor elimina la cuenta.
 */
export function buildGuardianRevokeMail({ to, locale, link, displayName, uid, now }: GuardianMailInput) {
  const es = locale.toLowerCase().startsWith("es");
  const days = GUARDIAN_REVOKE_LINK_TTL_DAYS;
  const subject = es ? `Has dado tu consentimiento para ${displayName}` : `You gave your consent for ${displayName}`;
  const intro = es
    ? `Has aceptado que ${displayName} use Violin Studio. Si más adelante quieres retirar tu consentimiento, abre este enlace (válido ${days} días); la cuenta del menor dejará de tener acceso:`
    : `You agreed that ${displayName} may use Violin Studio. If you later want to revoke your consent, open this link (valid for ${days} days); the minor's account will lose access:`;
  const keep = es ? "Guarda este correo: solo tú recibes este enlace." : "Keep this email: only you receive this link.";
  return {
    to,
    uid,
    kind: "guardian_revoke" as const,
    expireAt: new Date(now.getTime() + LINK_TTL_MS),
    message: {
      subject,
      text: `${intro}\n${link}\n\n${keep}`,
      html: `<p>${escapeHtml(intro)}</p><p><a href="${escapeHtml(link)}">${escapeHtml(link)}</a></p><p>${escapeHtml(keep)}</p>`,
    },
  };
}
