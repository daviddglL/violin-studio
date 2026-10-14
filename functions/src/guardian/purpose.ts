/** Finalidades de una solicitud al tutor (`guardianRequests.purpose`; ausente en solicitudes antiguas = política). */
export const GUARDIAN_PURPOSES = ["privacy_policy", "recording_sharing"] as const;
export type GuardianPurpose = (typeof GUARDIAN_PURPOSES)[number];

/** Finalidad de una solicitud guardada: lo ausente o ilegible es la de política (solicitudes anteriores a 4b). */
export const purposeOf = (r: Record<string, unknown> | undefined): GuardianPurpose =>
  r?.purpose === "recording_sharing" ? "recording_sharing" : "privacy_policy";
