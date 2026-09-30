import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";

export function sha256Hex(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

/** HMAC-SHA256 del email normalizado (trim + minúsculas): no guarda el email del tutor en claro. */
export function hmacEmail(pepper: string, email: string): string {
  return createHmac("sha256", pepper).update(email.trim().toLowerCase(), "utf8").digest("hex");
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
