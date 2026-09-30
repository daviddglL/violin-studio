import { Auth } from "firebase-admin/auth";
import { Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { requireEnum, requireIsoDate, requireObject, requireString } from "../common/validation";
import { CURRENT_POLICY_VERSION } from "../config/identity";
import { isMinor, parseBirthDate } from "../identity/age";
import { syncClaims } from "../identity/claims";

export const INSTRUMENTS = ["violin", "viola", "cello", "double_bass", "other"] as const;
export type Instrument = (typeof INSTRUMENTS)[number];

export const DISPLAY_NAME_MAX = 40;
export const LOCALE_PATTERN = /^[a-z]{2}(-[A-Z]{2})?$/;

export interface RegisterProfileDeps {
  db: Firestore;
  auth: Auth;
  clock: Clock;
  guardianFlowEnabled: boolean;
  /** Sumidero de logs sin PII; por defecto `logger.info` de Functions. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

export interface RegisterProfileInput {
  birthDate: string;
  displayName: string;
  instrument: Instrument;
  locale: string;
}

/** Sin caracteres de control ni de formato (categoría Unicode C: NUL, saltos de línea, U+200B); los espacios normales se permiten. */
// Lista blanca idéntica a validEditable en firestore.rules: si difieren, un perfil creado aquí
// podría no volver a pasar las reglas al editar otro campo.
const DISPLAY_NAME_PATTERN = /^[\p{L}\p{M}\p{N}\p{P}\p{S} ]+$/u;

/** Recorta, exige 1–40 puntos de código (no unidades UTF-16) y solo admite la lista blanca; devuelve el valor recortado. */
function requireDisplayName(obj: Record<string, unknown>): string {
  const raw = obj.displayName;
  if (typeof raw !== "string") return requireString(obj, "displayName", { min: 1, max: DISPLAY_NAME_MAX });
  const trimmed = raw.trim();
  const length = [...trimmed].length;
  if (length < 1 || length > DISPLAY_NAME_MAX || !DISPLAY_NAME_PATTERN.test(trimmed)) {
    throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "displayName" });
  }
  return trimmed;
}

/** Valida el payload; ignora cualquier otro campo (p. ej. `role`), que nunca se acepta del cliente. */
export function parseRegisterProfileInput(data: unknown, today: Date): RegisterProfileInput {
  const obj = requireObject(data);
  const birthDate = requireIsoDate(obj, "birthDate");
  parseBirthDate(birthDate, today);
  return {
    birthDate,
    displayName: requireDisplayName(obj),
    instrument: requireEnum(obj, "instrument", INSTRUMENTS),
    locale: requireString(obj, "locale", { min: 2, max: 5, pattern: LOCALE_PATTERN }),
  };
}

export interface RegisterProfileResult {
  isMinor: boolean;
  consentStatus: string;
  requiredPolicyVersion: number;
}

/**
 * Crea `users/{uid}` de forma idempotente. Si el perfil ya existe (aunque llegue otra `birthDate`)
 * devuelve el existente sin modificar nada, para que reenviar la petición no permita eludir la edad.
 * El `role` nunca sale del cliente. Los logs no llevan datos personales.
 */
export async function registerProfileHandler(
  deps: RegisterProfileDeps,
  uid: string,
  data: unknown,
): Promise<RegisterProfileResult> {
  const now = deps.clock();
  const input = parseRegisterProfileInput(data, now);
  const minor = isMinor(parseBirthDate(input.birthDate, now), now);
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);

  const { result, created } = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) {
      const d = snap.data() ?? {};
      return {
        created: false,
        result: {
          isMinor: d.isMinor === true,
          consentStatus: String(d.consentStatus),
          requiredPolicyVersion: CURRENT_POLICY_VERSION,
        },
      };
    }
    if (minor && !deps.guardianFlowEnabled) {
      throw fail("failed-precondition", ErrorReason.UNDERAGE_NOT_ALLOWED);
    }
    const stamp = Timestamp.fromDate(now);
    tx.create(ref, {
      displayName: input.displayName,
      instrument: input.instrument,
      locale: input.locale,
      role: "independent",
      birthDate: input.birthDate,
      isMinor: minor,
      consentStatus: "pending",
      policyVersion: null,
      createdAt: stamp,
      updatedAt: stamp,
    });
    return {
      created: true,
      result: { isMinor: minor, consentStatus: "pending", requiredPolicyVersion: CURRENT_POLICY_VERSION },
    };
  });

  await syncClaims({ db: deps.db, auth: deps.auth }, uid);
  (deps.log ?? ((m, d) => logger.info(m, d)))("registerProfile", { created, isMinor: result.isMinor });
  return result;
}
