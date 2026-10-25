import { Auth } from "firebase-admin/auth";
import { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { CURRENT_POLICY_VERSION } from "../config/identity";
import { ROLES } from "../teacher/role-transitions";

/** Roles asignables por el servidor; cualquier otro valor cae a `independent` (fail-closed y acota el payload). */
export const ALLOWED_ROLES: readonly string[] = ROLES;

export interface Claims {
  role: string;
  consentOk: boolean;
}

/** Sin PII: solo `role` y `consentOk` derivan del documento `users/{uid}` (fuente de verdad). */
export function claimsFromProfile(
  doc: Record<string, unknown>,
  currentVersion: number = CURRENT_POLICY_VERSION,
): Claims {
  const role = typeof doc.role === "string" && ALLOWED_ROLES.includes(doc.role) ? doc.role : "independent";
  const consentOk =
    doc.consentStatus === "granted" && doc.policyVersion === currentVersion && !doc.deletion;
  return { role, consentOk };
}

export interface ClaimsDeps {
  db: Firestore;
  auth: Auth;
  /** Versión vigente de la política; solo se inyecta en tests. */
  policyVersion?: number;
}

/**
 * Copia los claims derivados de `users/{uid}` al token. Idempotente: no escribe si ya coinciden y
 * no hace nada si el usuario aún no tiene documento. Conserva claims ajenos a `role`/`consentOk`.
 */
export async function syncClaims({ db, auth, policyVersion }: ClaimsDeps, uid: string): Promise<void> {
  const snap = await db.collection(COLLECTIONS.users).doc(uid).get();
  if (!snap.exists) return;
  const wanted = claimsFromProfile(snap.data() ?? {}, policyVersion);
  const current = (await auth.getUser(uid)).customClaims ?? {};
  if (current.role === wanted.role && current.consentOk === wanted.consentOk) return;
  await auth.setCustomUserClaims(uid, { ...current, ...wanted });
}
