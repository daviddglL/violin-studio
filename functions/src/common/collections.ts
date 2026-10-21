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
  assignments: "assignments",
  recordings: "recordings",
  reviews: "reviews",
  /** `users/{uid}/recordingCountLedger/{marca}`: idempotencia del decremento del cupo en `onRecordingDeleted` (solo servidor, TTL). */
  recordingCountLedger: "recordingCountLedger",
  /** Estado tecnico de los trabajos diarios (cursores de reanudacion); solo servidor. */
  maintenance: "maintenance",
  /** `chatMessages/{id}`: mensajes del chat de un vinculo, con `teacherUid` y `studentUid` en el doc (fase 4c). */
  chatMessages: "chatMessages",
} as const;

export type CollectionName = (typeof COLLECTIONS)[keyof typeof COLLECTIONS];
