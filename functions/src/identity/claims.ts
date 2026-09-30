import { Auth } from "firebase-admin/auth";
import { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { CURRENT_POLICY_VERSION } from "../config/identity";

/** Roles asignables por el servidor; cualquier otro valor cae a `independent` (fail-closed y acota el payload). */
const ALLOWED_ROLES: readonly string[] = ["independent", "student", "teacher"];

export interface Claims {
  role: string;
  consentOk: boolean;
}

/** Sin PII: solo `role` y `consentOk` derivan del documento `users/{uid}` (fuente de verdad). */
export function claimsFromProfile(doc: Record<string, unknown>): Claims {
  const role = typeof doc.role === "string" && ALLOWED_ROLES.includes(doc.role) ? doc.role : "independent";
  const consentOk =
    doc.consentStatus === "granted" && doc.policyVersion === CURRENT_POLICY_VERSION && !doc.deletion;
  return { role, consentOk };
}

export interface ClaimsDeps {
  db: Firestore;
  auth: Auth;
}

/**
 * Copia los claims derivados de `users/{uid}` al token. Idempotente: no escribe si ya coinciden y
 * no hace nada si el usuario aún no tiene documento. Conserva claims ajenos a `role`/`consentOk`.
 */
export async function syncClaims({ db, auth }: ClaimsDeps, uid: string): Promise<void> {
  const snap = await db.collection(COLLECTIONS.users).doc(uid).get();
  if (!snap.exists) return;
  const wanted = claimsFromProfile(snap.data() ?? {});
  const current = (await auth.getUser(uid)).customClaims ?? {};
  if (current.role === wanted.role && current.consentOk === wanted.consentOk) return;
  await auth.setCustomUserClaims(uid, { ...current, ...wanted });
}
