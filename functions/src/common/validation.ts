import { ErrorReason, fail } from "./errors";

type Obj = Record<string, unknown>;

const invalido = (field: string, message?: string) =>
  fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field, ...(message ? { message } : {}) });

/** El payload de una callable debe ser un objeto plano (no null, array ni primitivo). */
export function requireObject(value: unknown): Obj {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw invalido("data", "se esperaba un objeto");
  }
  return value as Obj;
}

export interface StringRules {
  min: number;
  max: number;
  pattern?: RegExp;
}

export function requireString(obj: Obj, key: string, rules: StringRules): string {
  const value = obj[key];
  if (typeof value !== "string") throw invalido(key, "se esperaba texto");
  if (value.length < rules.min || value.length > rules.max) throw invalido(key, "longitud fuera de rango");
  if (rules.pattern && !rules.pattern.test(value)) throw invalido(key, "formato no válido");
  return value;
}

export function requireEnum<T extends string>(obj: Obj, key: string, allowed: readonly T[]): T {
  const value = obj[key];
  if (typeof value !== "string" || !(allowed as readonly string[]).includes(value)) {
    throw invalido(key, "valor no permitido");
  }
  return value as T;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Solo comprueba el formato `YYYY-MM-DD`; la validez de calendario la decide `identity/age`. */
export function requireIsoDate(obj: Obj, key: string): string {
  const value = obj[key];
  if (typeof value !== "string" || !ISO_DATE.test(value)) {
    throw fail("invalid-argument", ErrorReason.INVALID_BIRTH_DATE, { field: key });
  }
  return value;
}
