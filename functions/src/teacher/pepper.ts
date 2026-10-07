import { defineSecret } from "firebase-functions/params";
import { MIN_PEPPER_LENGTH } from "../common/hashing";

/** Pepper del HMAC de los codigos de vinculo: Secret Manager en produccion, `functions/.secret.local` en el emulador. */
export const TEACHER_CODE_PEPPER = defineSecret("TEACHER_CODE_PEPPER");

/** Falla claro (sin revelar el valor) si el secreto no esta configurado o es demasiado corto. */
export function requireCodePepper(value: string | undefined): string {
  if (!value || value.length < MIN_PEPPER_LENGTH) {
    throw new Error(`TEACHER_CODE_PEPPER no configurado o con menos de ${MIN_PEPPER_LENGTH} caracteres`);
  }
  return value;
}
