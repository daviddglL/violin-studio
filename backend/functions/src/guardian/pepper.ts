import { defineSecret } from "firebase-functions/params";
import { MIN_PEPPER_LENGTH } from "../common/hashing";

/** Pepper del HMAC del email del tutor: Secret Manager en producción, `functions/.secret.local` en el emulador. */
export const GUARDIAN_EMAIL_PEPPER = defineSecret("GUARDIAN_EMAIL_PEPPER");

/** Falla claro (sin revelar el valor) si el secreto no está configurado o es demasiado corto. */
export function requirePepper(value: string | undefined): string {
  if (!value || value.length < MIN_PEPPER_LENGTH) {
    throw new Error(`GUARDIAN_EMAIL_PEPPER no configurado o con menos de ${MIN_PEPPER_LENGTH} caracteres`);
  }
  return value;
}
