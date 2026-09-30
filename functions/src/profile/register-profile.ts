import { Auth } from "firebase-admin/auth";
import { Firestore } from "firebase-admin/firestore";
import { requireEnum, requireIsoDate, requireObject, requireString } from "../common/validation";
import { Clock } from "../common/clock";
import { parseBirthDate } from "../identity/age";

export const INSTRUMENTS = ["violin", "viola", "cello", "double_bass", "other"] as const;
export type Instrument = (typeof INSTRUMENTS)[number];

export const DISPLAY_NAME_MAX = 40;
export const LOCALE_PATTERN = /^[a-z]{2}(-[A-Z]{2})?$/;

export interface RegisterProfileDeps {
  db: Firestore;
  auth: Auth;
  clock: Clock;
  guardianFlowEnabled: boolean;
}

export interface RegisterProfileInput {
  birthDate: string;
  displayName: string;
  instrument: Instrument;
  locale: string;
}

/** Valida el payload; ignora cualquier otro campo (p. ej. `role`), que nunca se acepta del cliente. */
export function parseRegisterProfileInput(data: unknown, today: Date): RegisterProfileInput {
  const obj = requireObject(data);
  const birthDate = requireIsoDate(obj, "birthDate");
  parseBirthDate(birthDate, today);
  return {
    birthDate,
    displayName: requireString(obj, "displayName", { min: 1, max: DISPLAY_NAME_MAX }),
    instrument: requireEnum(obj, "instrument", INSTRUMENTS),
    locale: requireString(obj, "locale", { min: 2, max: 5, pattern: LOCALE_PATTERN }),
  };
}

export async function registerProfileHandler(deps: RegisterProfileDeps, _uid: string, data: unknown): Promise<never> {
  parseRegisterProfileInput(data, deps.clock());
  throw new Error("no implementado");
}
