import { Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { MAX_DEVICES } from "./devices";

/**
 * Avisos push (decision de David, 2026-10-23): mensaje de chat, revision del profesor y tarea nueva. El aviso NO lleva
 * contenido (ni el texto del mensaje ni nombres): solo claves de texto que la app traduce (`push_<kind>_title|body`) y el
 * tipo. Detras de `NOTIFICATIONS_ENABLED`; sin dispositivos registrados no se envia nada.
 */
export type NotificationKind = "chat" | "review" | "assignment";

/** Canal de Android que crea la app (`ViolinStudioApp`); debe coincidir. */
export const ANDROID_CHANNEL_ID = "activity";

export interface PushMessage {
  kind: NotificationKind;
  titleLocKey: string;
  bodyLocKey: string;
  channelId: string;
}

export const pushMessage = (kind: NotificationKind): PushMessage => ({
  kind,
  titleLocKey: `push_${kind}_title`,
  bodyLocKey: `push_${kind}_body`,
  channelId: ANDROID_CHANNEL_ID,
});

/** Resultado por token, en el mismo orden: `invalid` = FCM dice que el token ya no existe (se borra). */
export type SendResult = "ok" | "invalid" | "error";

export interface MessagingPort {
  send(tokens: string[], message: PushMessage): Promise<SendResult[]>;
}

const INVALID_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

export const sendResultOf = (error: { code?: string } | undefined): SendResult =>
  error === undefined ? "ok" : INVALID_TOKEN_CODES.has(error.code ?? "") ? "invalid" : "error";

export type NotifyOutcome = "disabled" | "inactive" | "no_devices" | "sent";

export interface NotifyDeps {
  db: Firestore;
  messaging: MessagingPort;
  enabled: () => Promise<boolean>;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/** Un usuario recibe avisos solo con la cuenta activa (consentimiento vigente y sin borrado en curso). */
export const isActiveUser = (u: Record<string, unknown> | undefined): boolean =>
  u !== undefined && u.consentStatus === "granted" && !("deletion" in u);

export async function notifyUser(deps: NotifyDeps, uid: string, kind: NotificationKind): Promise<NotifyOutcome> {
  const log = deps.log ?? ((m, d) => logger.info(m, d));
  const uidHash = sha256Hex(uid).slice(0, 12);
  if (!(await deps.enabled())) return "disabled";
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const user = await userRef.get();
  if (!isActiveUser(user.data())) {
    log("notify", { uidHash, kind, outcome: "inactive" });
    return "inactive";
  }
  const devices = await userRef.collection(COLLECTIONS.devices).limit(MAX_DEVICES).get();
  const targets = devices.docs
    .map((d) => ({ ref: d.ref, token: d.get("token") as unknown }))
    .filter((t): t is { ref: typeof t.ref; token: string } => typeof t.token === "string" && t.token.length > 0);
  if (targets.length === 0) return "no_devices";

  const results = await deps.messaging.send(targets.map((t) => t.token), pushMessage(kind));
  const invalid = targets.filter((_, i) => results[i] === "invalid");
  if (invalid.length > 0) {
    const batch = deps.db.batch();
    invalid.forEach((t) => batch.delete(t.ref));
    await batch.commit();
  }
  log("notify", {
    uidHash,
    kind,
    outcome: "sent",
    devices: targets.length,
    ok: results.filter((r) => r === "ok").length,
    invalid: invalid.length,
  });
  return "sent";
}

/** A quien avisar de un mensaje de chat: a la otra parte del vinculo (nadie si el autor no es una de las dos). */
export function chatRecipient(data: Record<string, unknown> | undefined): string | undefined {
  const t = data?.teacherUid;
  const s = data?.studentUid;
  const sender = data?.senderUid;
  if (typeof t !== "string" || typeof s !== "string" || t === s) return undefined;
  if (sender === t) return s;
  if (sender === s) return t;
  return undefined;
}

/**
 * Revision nueva en `users/{uid}/recordings/{r}/reviews/{id}`: se avisa al dueno de la grabacion. Las revisiones
 * anonimizadas que crea el servidor al borrar a un profesor (`reviewerUid: null`) no avisan.
 */
export function reviewRecipient(ownerUid: string, data: Record<string, unknown> | undefined): string | undefined {
  const reviewer = data?.reviewerUid;
  return typeof reviewer === "string" && reviewer.length > 0 && reviewer !== ownerUid ? ownerUid : undefined;
}

/** Tarea nueva en `users/{uid}/assignments/{id}`: se avisa al alumno (el profesor no se avisa a si mismo). */
export function assignmentRecipient(ownerUid: string, data: Record<string, unknown> | undefined): string | undefined {
  const teacher = data?.teacherUid;
  return typeof teacher === "string" && teacher.length > 0 && teacher !== ownerUid ? ownerUid : undefined;
}
