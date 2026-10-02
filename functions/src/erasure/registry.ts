import { CollectionName } from "../common/collections";

export type ErasurePolicy =
  | { kind: "userDoc" }
  | { kind: "subcollectionOf"; parent: CollectionName }
  | { kind: "queryByField"; field: string }
  | { kind: "exempt"; reason: string };

/**
 * Contrato para quien escriba en estas colecciones (3a):
 * - `guardianRequests` y `mail` DEBEN guardar al propietario en el campo `uid` (los docs de Trigger Email admiten campos extra);
 *   si no, la cascada no los encuentra.
 * - `guardianEmailLimits` está exenta porque depende de una política TTL de Firestore sobre `expireAt` (a configurar en 3a).
 * Exhaustivo sobre COLLECTIONS: añadir una colección sin política no compila (AD5). */
export const ERASABLE_COLLECTIONS: Record<CollectionName, ErasurePolicy> = {
  users: { kind: "userDoc" },
  consents: { kind: "subcollectionOf", parent: "users" },
  guardianRequests: { kind: "queryByField", field: "uid" },
  mail: { kind: "queryByField", field: "uid" },
  guardianEmailLimits: { kind: "exempt", reason: "clave HMAC del tutor, sin uid; TTL 24 h" },
};

export const STORAGE_PREFIXES: ReadonlyArray<(uid: string) => string> = [(uid) => `users/${uid}/`];
