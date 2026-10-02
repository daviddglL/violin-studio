import { CallableRequest, HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { requireRecentAuth } from "../common/auth-guard";
import { Clock, systemClock } from "../common/clock";
import { ErrorReason, fail } from "../common/errors";
import { sha256Hex } from "../common/hashing";

export interface DeleteAccountDeps {
  /** Ejecuta la cascada completa (con borrado de Auth) para el uid. */
  erase: (uid: string) => Promise<unknown>;
  clock?: Clock;
  log?: (message: string, data: Record<string, unknown>) => void;
}

/**
 * Borrado de cuenta iniciado por el usuario. Solo exige sesión y reautenticación reciente (D1): no
 * `email_verified` ni consentimiento, para que cualquier estado pueda borrar su cuenta.
 */
export async function deleteAccountHandler(
  deps: DeleteAccountDeps,
  request: Pick<CallableRequest, "auth">,
): Promise<{ deleted: true }> {
  if (!request.auth) throw new HttpsError("unauthenticated", "Se requiere sesión");
  const { uid, token } = request.auth;
  requireRecentAuth(token, (deps.clock ?? systemClock)());
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.warn(m, d));
  try {
    await deps.erase(uid);
  } catch {
    // El error original puede contener rutas con el uid: no se loguea ni se propaga.
    log("deleteAccount.failed", { uidHash: sha256Hex(uid).slice(0, 12) });
    throw fail("internal", ErrorReason.ERASURE_FAILED);
  }
  return { deleted: true };
}
