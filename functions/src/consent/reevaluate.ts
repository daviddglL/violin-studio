import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { CURRENT_POLICY_VERSION } from "../config/identity";
import { syncClaims } from "../identity/claims";

export interface ReevaluateDeps {
  db: Firestore;
  auth: Auth;
  /** Versión vigente; solo se inyecta en tests. */
  currentVersion?: number;
}

/**
 * Decisión pura (D3): un `granted` cuya versión es anterior a la vigente (o ilegible: fail-closed)
 * vuelve a `pending`; el resto no cambia. Devuelve el nuevo estado o `null` si no hay cambio.
 */
export function reevaluatedStatus(doc: Record<string, unknown>, currentVersion: number): "pending" | null {
  if (doc.consentStatus !== "granted") return null;
  const v = doc.policyVersion;
  if (typeof v === "number" && v >= currentVersion) return null;
  return "pending";
}

/**
 * Reevalúa el consentimiento de `uid` contra la versión vigente (decide el documento, no el claim)
 * y deja los claims alineados. No migra datos y no hace nada si el usuario no tiene perfil.
 */
export async function reevaluateConsent(deps: ReevaluateDeps, uid: string): Promise<void> {
  const current = deps.currentVersion ?? CURRENT_POLICY_VERSION;
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);
  await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return;
    const next = reevaluatedStatus(snap.data() ?? {}, current);
    if (next) tx.update(ref, { consentStatus: next, updatedAt: FieldValue.serverTimestamp() });
  });
  await syncClaims({ db: deps.db, auth: deps.auth, policyVersion: current }, uid);
}
