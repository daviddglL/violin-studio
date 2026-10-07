/** Registro central de colecciones de primer nivel; el borrado y las reglas se derivan de aquí. */
export const COLLECTIONS = {
  users: "users",
  consents: "consents",
  practiceSessions: "practiceSessions",
  guardianRequests: "guardianRequests",
  guardianEmailLimits: "guardianEmailLimits",
  mail: "mail",
} as const;

export type CollectionName = (typeof COLLECTIONS)[keyof typeof COLLECTIONS];

/**
 * Fuera de `COLLECTIONS` hasta el slice A3 (que la registra con `ERASABLE_COLLECTIONS` y reglas):
 * aqui solo se consulta, y una coleccion inexistente devuelve un resultado vacio.
 */
export const TEACHER_LINKS_COLLECTION = "teacherLinks";
