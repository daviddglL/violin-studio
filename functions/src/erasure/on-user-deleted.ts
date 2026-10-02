import { logger } from "firebase-functions/v2";
import { sha256Hex } from "../common/hashing";
import { eraseUserData, ErasureDeps, ErasureResult, isPermanent } from "./erase-user-data";

/**
 * Limpieza de datos cuando una cuenta Auth desaparece por una vía externa (consola, Admin SDK, purga).
 * La cuenta ya no existe, así que no se borra Auth. Si el borrado vino de `deleteAccount`, la cascada
 * ya limpió todo y esto es un no-op idempotente.
 *
 * Con `failurePolicy` la plataforma reintenta hasta 7 días ante CUALQUIER error: los permanentes se
 * registran (solo uidHash y código) y se tragan —la purga 7b.3 reanuda el `deletion` atascado—; los
 * transitorios se relanzan para que se reintente. Nunca se loguea el mensaje del error.
 */
export async function onUserDeletedHandler(deps: ErasureDeps, uid: string): Promise<ErasureResult | undefined> {
  try {
    return await eraseUserData(deps, uid, { deleteAuth: false });
  } catch (e) {
    if (!isPermanent(e)) throw e;
    const code = (e as { code?: unknown } | null)?.code;
    const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.error(m, d));
    log("onUserDeleted.permanentFailure", { uidHash: sha256Hex(uid).slice(0, 12), code });
    return undefined;
  }
}
