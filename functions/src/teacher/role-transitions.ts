export const ROLES = ["independent", "student", "teacher"] as const;
export type Role = (typeof ROLES)[number];

/**
 * REQ-PRF-T02: unicas transiciones de rol permitidas. `independent<->student` las provocan canje y
 * desvinculacion; `independent<->teacher` solo los scripts de administracion. `teacher` nunca es alumno.
 */
const ALLOWED: ReadonlySet<string> = new Set([
  "independent>student",
  "student>independent",
  "independent>teacher",
  "teacher>independent",
]);

export function isAllowedRoleTransition(from: Role, to: Role): boolean {
  return ALLOWED.has(`${from}>${to}`);
}
