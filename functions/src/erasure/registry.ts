import { CollectionName } from "../common/collections";

export type ErasurePolicy =
  | { kind: "userDoc" }
  | { kind: "subcollectionOf"; parent: CollectionName }
  | { kind: "queryByField"; field: string }
  | { kind: "exempt"; reason: string };

/** Exhaustivo sobre COLLECTIONS: añadir una colección sin política no compila (AD5). */
export const ERASABLE_COLLECTIONS: Record<CollectionName, ErasurePolicy> = {
  users: { kind: "userDoc" },
  consents: { kind: "subcollectionOf", parent: "users" },
  guardianRequests: { kind: "queryByField", field: "uid" },
  mail: { kind: "queryByField", field: "uid" },
  guardianEmailLimits: { kind: "exempt", reason: "clave HMAC del tutor, sin uid; TTL 24 h" },
};

export const STORAGE_PREFIXES: ReadonlyArray<(uid: string) => string> = [(uid) => `users/${uid}/`];
