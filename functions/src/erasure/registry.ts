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
  practiceSessions: { kind: "subcollectionOf", parent: "users" },
  guardianRequests: { kind: "queryByField", field: "uid" },
  mail: { kind: "queryByField", field: "uid" },
  guardianEmailLimits: { kind: "exempt", reason: "clave HMAC del tutor, sin uid; TTL 24 h" },
  // Provisional (A2): los ids son HMAC y el doc lleva `teacherUid`/`usedBy`; A5 lo sustituye por
  // `queryByFields [teacherUid, usedBy]`. Mientras, caducan por TTL (`expireAt`) y `revoke-teacher` los revoca.
  teacherCodes: { kind: "exempt", reason: "provisional hasta A5 (queryByFields teacherUid/usedBy); TTL expireAt" },
  // Provisional (A3a): el id es `{t}_{s}` y el doc lleva `teacherUid`/`studentUid`; A5 lo sustituye por
  // `queryByFields [teacherUid, studentUid]` (y `removeLink` en A3b). Mientras, no se borra con la cuenta.
  teacherLinks: { kind: "exempt", reason: "provisional hasta A5 (queryByFields teacherUid/studentUid via removeLink)" },
};

export const STORAGE_PREFIXES: ReadonlyArray<(uid: string) => string> = [(uid) => `users/${uid}/`];
