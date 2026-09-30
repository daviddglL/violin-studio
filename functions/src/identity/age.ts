import { DIGITAL_CONSENT_AGE, MAX_PLAUSIBLE_AGE } from "../config/identity";
import { ErrorReason, fail } from "../common/errors";

export interface BirthDate {
  year: number;
  month: number;
  day: number;
}

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

function isRealDate({ year, month, day }: BirthDate): boolean {
  if (![year, month, day].every(Number.isInteger)) return false;
  const d = new Date(Date.UTC(year, month - 1, day));
  return d.getUTCFullYear() === year && d.getUTCMonth() === month - 1 && d.getUTCDate() === day;
}

/** Años cumplidos en `today` (UTC). Quien nace un 29-feb cumple el 1 de marzo en años no bisiestos. */
export function ageOn(birth: BirthDate, today: Date): number {
  const y = today.getUTCFullYear();
  const m = today.getUTCMonth() + 1;
  const d = today.getUTCDate();
  let age = y - birth.year;
  const isLeapDay = birth.month === 2 && birth.day === 29;
  // Comparación (mes, día) con el 29-feb desplazado al 1-mar.
  const bm = isLeapDay ? 3 : birth.month;
  const bd = isLeapDay ? 1 : birth.day;
  const isLeapYear = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  const birthdayPassed = isLeapDay && isLeapYear ? m > 2 || (m === 2 && d >= 29) : m > bm || (m === bm && d >= bd);
  if (!birthdayPassed) age -= 1;
  return age;
}

/** Valida `YYYY-MM-DD`: existente, no futura y no más antigua que MAX_PLAUSIBLE_AGE años. */
export function parseBirthDate(text: string, today: Date, maxAge: number = MAX_PLAUSIBLE_AGE): BirthDate {
  const match = ISO_DATE.exec(text);
  const birth: BirthDate | null = match ? { year: +match[1], month: +match[2], day: +match[3] } : null;
  if (!birth || !isRealDate(birth)) throw fail("invalid-argument", ErrorReason.INVALID_BIRTH_DATE);
  const age = ageOn(birth, today);
  const future = Date.UTC(birth.year, birth.month - 1, birth.day) > today.getTime();
  if (Number.isNaN(age) || future || age > maxAge) {
    throw fail("invalid-argument", ErrorReason.INVALID_BIRTH_DATE);
  }
  return birth;
}

function isBirthDate(value: unknown): value is BirthDate {
  if (typeof value !== "object" || value === null) return false;
  const { year, month, day } = value as Record<string, unknown>;
  return (
    typeof year === "number" && typeof month === "number" && typeof day === "number" &&
    isRealDate({ year, month, day })
  );
}

/**
 * Fail-closed: cualquier entrada inválida (fecha inexistente, futura, `today` inválido) cuenta como
 * menor; nunca se concede la condición de adulto por un dato que no se puede interpretar.
 */
export function isMinor(birth: unknown, today: Date, threshold: number = DIGITAL_CONSENT_AGE): boolean {
  if (!isBirthDate(birth) || Number.isNaN(today.getTime())) return true;
  if (Date.UTC(birth.year, birth.month - 1, birth.day) > today.getTime()) return true;
  return ageOn(birth, today) < threshold;
}
