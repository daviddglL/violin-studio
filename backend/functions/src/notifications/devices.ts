import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { sha256Hex } from "../common/hashing";

/**
 * Tokens de FCM de los dispositivos de un usuario (`users/{uid}/devices/{sha256(token)}`; solo Functions escribe). El id es
 * el hash para no poner el token en una ruta; el token va en el doc porque hace falta para enviar. Caen con la cuenta
 * (`recursiveDelete` del usuario), al cerrar sesion (`unregisterDevice`) y cuando FCM dice que el token ya no vale.
 */
export const MAX_DEVICES = 10;
/** Los tokens de FCM rondan los 160 caracteres; el limite solo corta basura. */
export const MAX_TOKEN_LENGTH = 4096;

export function validToken(raw: unknown): string {
  if (typeof raw !== "string") throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "token" });
  const token = raw.trim();
  if (token.length === 0 || token.length > MAX_TOKEN_LENGTH || /\s/.test(token)) {
    throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "token" });
  }
  return token;
}

export const deviceId = (token: string) => sha256Hex(token);

export interface DevicesDeps {
  db: Firestore;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

const devicesOf = (db: Firestore, uid: string) =>
  db.collection(COLLECTIONS.users).doc(uid).collection(COLLECTIONS.devices);

/**
 * `registerDevice`: idempotente. Un dispositivo compartido no debe recibir los avisos de la cuenta anterior, asi que el
 * mismo token se quita de cualquier otro usuario. Se guardan como mucho [MAX_DEVICES] (se retiran los mas antiguos).
 */
export async function registerDeviceHandler(deps: DevicesDeps, uid: string, rawToken: unknown): Promise<{ ok: true }> {
  const token = validToken(rawToken);
  const id = deviceId(token);
  const user = await deps.db.collection(COLLECTIONS.users).doc(uid).get();
  if (!user.exists || "deletion" in (user.data() ?? {})) throw fail("failed-precondition", ErrorReason.NO_PROFILE);

  const others = await deps.db.collectionGroup(COLLECTIONS.devices).where("token", "==", token).get();
  const batch = deps.db.batch();
  for (const doc of others.docs) {
    if (doc.ref.parent.parent?.id !== uid) batch.delete(doc.ref);
  }
  batch.set(devicesOf(deps.db, uid).doc(id), { token, platform: "android", updatedAt: FieldValue.serverTimestamp() });
  await batch.commit();

  const extra = await devicesOf(deps.db, uid).orderBy("updatedAt", "desc").offset(MAX_DEVICES).get();
  if (!extra.empty) {
    const trim = deps.db.batch();
    extra.docs.forEach((d) => trim.delete(d.ref));
    await trim.commit();
  }
  (deps.log ?? ((m, d) => logger.info(m, d)))("registerDevice", {
    uidHash: sha256Hex(uid).slice(0, 12),
    movedFromOthers: others.docs.filter((d) => d.ref.parent.parent?.id !== uid).length,
    trimmed: extra.size,
  });
  return { ok: true };
}

/** `unregisterDevice`: al cerrar sesion; borrar un token que no esta no es un error. */
export async function unregisterDeviceHandler(deps: DevicesDeps, uid: string, rawToken: unknown): Promise<{ ok: true }> {
  const token = validToken(rawToken);
  await devicesOf(deps.db, uid).doc(deviceId(token)).delete();
  return { ok: true };
}
