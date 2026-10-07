import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { syncClaims } from "../identity/claims";
import { canGrantTeacher, TeacherDenyReason } from "../teacher/eligibility";
import { isAllowedRoleTransition, Role } from "../teacher/role-transitions";

export type TeacherRoleErrorReason =
  | TeacherDenyReason
  | "INVALID_UID"
  | "USER_NOT_FOUND"
  | "INVALID_TRANSITION";

/** Error de la CLI de administracion; el mensaje solo lleva el motivo (nunca PII). */
export class TeacherRoleError extends Error {
  constructor(readonly reason: TeacherRoleErrorReason) {
    super(reason);
    this.name = "TeacherRoleError";
  }
}

export interface TeacherRoleDeps {
  db: Firestore;
  auth: Auth;
  clock: Clock;
  /** Sumidero de logs sin PII; por defecto `logger.info` de Functions. */
  log?: (message: string, data: Record<string, unknown>) => void;
  /** Revoca los codigos de vinculo activos del profesor; devuelve cuantos. Sin coleccion aun (A2): no-op. */
  revokeActiveCodes?: (teacherUid: string) => Promise<number>;
  /** Desvincula a todos los alumnos del profesor (`removeLink`, A3); devuelve cuantos. Sin vinculos aun: no-op. */
  unlinkAllStudents?: (teacherUid: string) => Promise<number>;
}

const noop = async (): Promise<number> => 0;

/** Los ids de vinculo son `{t}_{s}`: un uid con `_` romperia el separador (design §3.1). */
function requireValidUid(uid: string): void {
  if (typeof uid !== "string" || uid.length === 0 || uid.includes("_") || uid.includes("/")) {
    throw new TeacherRoleError("INVALID_UID");
  }
}

function makeLog(deps: TeacherRoleDeps, uid: string) {
  const uidHash = sha256Hex(uid).slice(0, 12);
  const sink = deps.log ?? ((m, d) => logger.info(m, d));
  return (message: string, data: Record<string, unknown> = {}) => sink(message, { uidHash, ...data });
}

/** Alta de profesor (REQ-TRL-01/02). Reintentable: el rol se escribe en transaccion y los claims despues. */
export async function grantTeacher(deps: TeacherRoleDeps, uid: string): Promise<void> {
  requireValidUid(uid);
  const log = makeLog(deps, uid);
  const reject = (reason: TeacherRoleErrorReason): never => {
    log("grantTeacher.rejected", { reason });
    throw new TeacherRoleError(reason);
  };

  let emailVerified: boolean;
  try {
    emailVerified = (await deps.auth.getUser(uid)).emailVerified === true;
  } catch (e) {
    if ((e as { code?: string }).code === "auth/user-not-found") return reject("USER_NOT_FOUND");
    throw e;
  }

  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);
  const changed = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const profile = snap.exists ? (snap.data() ?? {}) : undefined;
    const verdict = canGrantTeacher({ profile, emailVerified, now: deps.clock() });
    if (!verdict.eligible) return reject(verdict.reason);
    if (verdict.alreadyTeacher) return false;
    if (!isAllowedRoleTransition(String(profile?.role ?? "independent") as Role, "teacher")) {
      return reject("INVALID_TRANSITION");
    }
    tx.update(ref, { role: "teacher", studentCount: 0, updatedAt: FieldValue.serverTimestamp() });
    return true;
  });

  // Tambien en el no-op: un fallo anterior tras escribir el rol deja los claims sin sincronizar.
  await syncClaims({ db: deps.db, auth: deps.auth }, uid);
  log("grantTeacher", { changed });
}

/**
 * Baja de profesor (REQ-TRL-04): primero limpia codigos y alumnos y solo despues devuelve el rol,
 * de modo que un fallo intermedio deja al usuario como `teacher` y repetir el comando lo completa.
 * Siempre sincroniza claims (idempotente) para recuperar un fallo posterior al cambio de rol.
 */
export async function revokeTeacher(deps: TeacherRoleDeps, uid: string): Promise<void> {
  requireValidUid(uid);
  const log = makeLog(deps, uid);
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);
  const snap = await ref.get();
  let codes = 0;
  let students = 0;
  let changed = false;
  if (snap.exists && snap.data()?.role === "teacher") {
    codes = await (deps.revokeActiveCodes ?? noop)(uid);
    students = await (deps.unlinkAllStudents ?? noop)(uid);
    changed = await deps.db.runTransaction(async (tx) => {
      const fresh = await tx.get(ref);
      if (fresh.data()?.role !== "teacher") return false;
      tx.update(ref, { role: "independent", studentCount: 0, updatedAt: FieldValue.serverTimestamp() });
      return true;
    });
  }
  await syncClaims({ db: deps.db, auth: deps.auth }, uid);
  log("revokeTeacher", { changed, codes, students });
}
