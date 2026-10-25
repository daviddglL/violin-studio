import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";

export function sha256Hex(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export const MIN_PEPPER_LENGTH = 32;

/**
 * Única normalización de emails (HMAC y comparación con el email propio): NFKC + trim + minúsculas.
 * Sin alias de proveedor (puntos de Gmail, +etiquetas): el límite por cuenta (3 / 24 h) acota el abuso
 * aunque alguien eluda el límite por destino con alias.
 */
export const normalizeEmail = (email: string): string => email.normalize("NFKC").trim().toLowerCase();

/** HMAC-SHA256 del email normalizado: no guarda el email del tutor en claro. */
export function hmacEmail(pepper: string, email: string): string {
  if (pepper.length < MIN_PEPPER_LENGTH) {
    throw new Error(`El pepper debe tener al menos ${MIN_PEPPER_LENGTH} caracteres`);
  }
  return createHmac("sha256", pepper).update(normalizeEmail(email), "utf8").digest("hex");
}

/** Token de un solo uso: 32 bytes aleatorios en base64url (43 caracteres). */
export function randomToken(): string {
  return randomBytes(32).toString("base64url");
}

/** Comparación en tiempo constante de dos hex; longitudes distintas o no hex -> false sin lanzar. */
export function safeEqualHex(a: string, b: string): boolean {
  const hex = /^(?:[0-9a-f]{2})+$/i;
  if (!hex.test(a) || !hex.test(b) || a.length !== b.length) return false;
  return timingSafeEqual(Buffer.from(a, "hex"), Buffer.from(b, "hex"));
}
