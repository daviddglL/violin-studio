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
  /** `appConfig/features`: interruptores en caliente que solo pueden apagar lo que el entorno enciende; solo servidor. */
  appConfig: "appConfig",
  /** `users/{uid}/devices/{sha256(token)}`: tokens de FCM para los avisos push; solo servidor. */
  devices: "devices",
  /** `users/{uid}/progress/curriculum`: lecciones del curriculo hechas; escribe el dueno, el profesor vinculado lee. */
  progress: "progress",
} as const;

export type CollectionName = (typeof COLLECTIONS)[keyof typeof COLLECTIONS];
