import { createHmac, randomInt } from "node:crypto";
import { MIN_PEPPER_LENGTH } from "../common/hashing";

/** Sin `0 O 1 I L`: 31 simbolos. */
export const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const CODE_LENGTH = 8;

/** Fuente de enteros uniformes en `[0, max)`; inyectable en tests. `randomInt` evita el sesgo del modulo. */
export type RandomInt = (max: number) => number;

export function generateCode(random: RandomInt = (max) => randomInt(max)): string {
  let code = "";
  for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[random(CODE_ALPHABET.length)];
  return code;
}

/** Tolera como lo teclea el alumno: mayusculas, sin espacios ni guiones. */
export const normalizeCode = (code: string): string => code.toUpperCase().replace(/[\s-]/g, "");

/** Tope de la entrada cruda antes de normalizar: acota el trabajo sobre texto hostil. */
const MAX_RAW_CODE_LENGTH = 32;

/** Validador estricto para el canje (A3): devuelve el codigo normalizado o `null` si no es un codigo posible. */
export function parseCodeInput(raw: unknown): string | null {
  if (typeof raw !== "string" || raw.length > MAX_RAW_CODE_LENGTH) return null;
  const code = normalizeCode(raw);
  return new RegExp(`^[${CODE_ALPHABET}]{${CODE_LENGTH}}$`).test(code) ? code : null;
}

/** HMAC-SHA256(pepper, codigo normalizado) en hex: id del doc `teacherCodes`. El codigo en claro no se guarda. */
export function hashCode(pepper: string, code: string): string {
  if (pepper.length < MIN_PEPPER_LENGTH) {
    throw new Error(`El pepper debe tener al menos ${MIN_PEPPER_LENGTH} caracteres`);
  }
  return createHmac("sha256", pepper).update(normalizeCode(code), "utf8").digest("hex");
}
