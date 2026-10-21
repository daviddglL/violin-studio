import { CollectionName } from "../common/collections";

/** Documentos de una subcolección que llevan el uid de un tercero (no su dueño): se barren con `collectionGroup`. */
export type ForeignRef = { field: string; action: "delete" };

export type ErasurePolicy =
  | { kind: "userDoc" }
  /** `parent` = colección que la contiene INMEDIATAMENTE; `foreign` añade el barrido por `collectionGroup` (paso `shared`). */
  | { kind: "subcollectionOf"; parent: CollectionName; foreign?: ForeignRef }
  /** Borra los docs cuyo cualquiera de estos campos sea el uid (una consulta por campo). */
  | { kind: "queryByFields"; fields: readonly string[] }
  /** Vinculo profesor-alumno: se desvincula con `removeLink` (contadores, rol, claims), no se borra a pelo. */
  | { kind: "link"; fields: readonly ["teacherUid", "studentUid"] }
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
  guardianRequests: { kind: "queryByFields", fields: ["uid"] },
  mail: { kind: "queryByFields", fields: ["uid"] },
  guardianEmailLimits: { kind: "exempt", reason: "clave HMAC del tutor, sin uid; TTL 24 h" },
  // Los ids son HMAC; el doc lleva `teacherUid` (creador) y `usedBy` (canjeador).
  teacherCodes: { kind: "queryByFields", fields: ["teacherUid", "usedBy"] },
  // Primero en la cascada: corta el acceso cruzado y recalcula contadores/rol/claims de la otra parte.
  teacherLinks: { kind: "link", fields: ["teacherUid", "studentUid"] },
  // Tareas: viven en el espacio del alumno (caen con su `recursiveDelete`) y las del profesor, tambien las hechas, se barren aparte.
  assignments: { kind: "subcollectionOf", parent: "users", foreign: { field: "teacherUid", action: "delete" } },
  // Grabaciones y demos (objeto en `users/{uid}/recordings|demos/`, doc siempre en `users/{uid}/recordings`): caen con
  // el `recursiveDelete` del usuario y el barrido de `users/{uid}/`. La cascada `foreign` (demos dirigidas al alumno
  // borrado, con su objeto) y `reviews` (anonimizar) las anade B5b: aqui solo la politica simple.
  recordings: { kind: "subcollectionOf", parent: "users" },
  // Cuelgan de `recordings`: caen con su `recursiveDelete`. Las del profesor borrado en espacios ajenos se anonimizan en B5b.
  reviews: { kind: "subcollectionOf", parent: "recordings" },
  // Marcas de idempotencia del decremento de `recordingCount` (una por instancia de doc borrada): caen con el
  // `recursiveDelete` del usuario y, mientras tanto, las retira la politica TTL de `expireAt` (30 d).
  recordingCountLedger: { kind: "subcollectionOf", parent: "users" },
  maintenance: {
    kind: "exempt",
    reason: "cursores de reanudacion de los trabajos diarios; solo un prefijo truncado de uid (no identifica a nadie), se sobrescribe en cada ejecucion",
  },
};

/**
 * Raiz de la cadena de `subcollectionOf` de `name` (el `userDoc` al que llega siguiendo los padres), o
 * `undefined` si la cadena se corta (padre sin politica, politica que no es subcoleccion) o forma un ciclo.
 * Solo lo que cuelga de `users` cae con `recursiveDelete`; el resto necesita una politica explicita.
 */
export function erasureRoot(policies: Record<string, ErasurePolicy>, name: string): string | undefined {
  const seen = new Set<string>();
  for (let cur = name; ; ) {
    const p = policies[cur];
    if (!p || seen.has(cur)) return undefined;
    seen.add(cur);
    if (p.kind === "userDoc") return cur;
    if (p.kind !== "subcollectionOf") return undefined;
    cur = p.parent;
  }
}

export const STORAGE_PREFIXES: ReadonlyArray<(uid: string) => string> = [(uid) => `users/${uid}/`];
