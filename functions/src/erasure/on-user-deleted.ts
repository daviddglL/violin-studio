import { eraseUserData, ErasureDeps, ErasureResult } from "./erase-user-data";

/**
 * Limpieza de datos cuando una cuenta Auth desaparece por una vía externa (consola, Admin SDK, purga).
 * La cuenta ya no existe, así que no se borra Auth. Si el borrado vino de `deleteAccount`, la cascada
 * ya limpió todo y esto es un no-op idempotente.
 */
export function onUserDeletedHandler(deps: ErasureDeps, uid: string): Promise<ErasureResult> {
  return eraseUserData(deps, uid, { deleteAuth: false });
}
