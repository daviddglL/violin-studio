/** Registro central de colecciones de primer nivel; el borrado y las reglas se derivan de aquí. */
export const COLLECTIONS = {
  users: "users",
  consents: "consents",
  practiceSessions: "practiceSessions",
  guardianRequests: "guardianRequests",
  guardianEmailLimits: "guardianEmailLimits",
  mail: "mail",
  teacherCodes: "teacherCodes",
  teacherLinks: "teacherLinks",
} as const;

export type CollectionName = (typeof COLLECTIONS)[keyof typeof COLLECTIONS];
