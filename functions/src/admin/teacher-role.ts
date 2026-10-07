import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS, TEACHER_LINKS_COLLECTION } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { syncClaims } from "../identity/claims";
import { canGrantTeacher, TeacherDenyReason } from "../teacher/eligibility";
import { isAllowedRoleTransition, Role } from "../teacher/role-transitions";

export type TeacherRoleErrorReason =
  | TeacherDenyReason
  | "HAS_LINKS"
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

  // `emailVerified` se lee fuera de la transaccion (Auth no es transaccional); es aceptable porque la
  // verificacion no se revierte y esta CLI la ejecuta un administrador de forma manual.
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
    // Fuente de verdad frente al contador `teacherCount`; una coleccion inexistente devuelve vacio.
    const links = await tx.get(
      deps.db.collection(TEACHER_LINKS_COLLECTION).where("studentUid", "==", uid).limit(1),
    );
    const verdict = canGrantTeacher({ profile, emailVerified, now: deps.clock(), hasStudentLinks: !links.empty });
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
 * Baja de profesor (REQ-TRL-04). Orden decidido (W1/W2): PRIMERO se devuelve el rol `independent` en
 * una transaccion (un canje concurrente exige `role == teacher`, asi que ya no puede crear vinculos ni
 * consumir codigos) y DESPUES se limpian codigos y alumnos. Es reanudable: un `independent` con
 * `studentCount > 0` (o codigos activos) repite la limpieza; solo es no-op cuando no queda nada.
 * Falla cerrado (`HAS_LINKS`) si hay alumnos y la limpieza no esta cableada (hasta A3b), ANTES de
 * cambiar nada. Siempre sincroniza claims (idempotente). `studentCount` lo decrementa `removeLink`.
 */
export async function revokeTeacher(deps: TeacherRoleDeps, uid: string): Promise<void> {
  requireValidUid(uid);
  const log = makeLog(deps, uid);
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);

  const before = (await ref.get()).data();
  const pendingStudents = typeof before?.studentCount === "number" ? before.studentCount : 0;
  if (pendingStudents > 0 && !deps.unlinkAllStudents) {
    log("revokeTeacher.rejected", { reason: "HAS_LINKS" });
    throw new TeacherRoleError("HAS_LINKS");
  }

  const { changed, count } = await deps.db.runTransaction(async (tx) => {
    const fresh = (await tx.get(ref)).data();
    const n = typeof fresh?.studentCount === "number" ? fresh.studentCount : 0;
    // Relectura transaccional: un canje concurrente pudo anadir alumnos desde la comprobacion previa.
    if (n > 0 && !deps.unlinkAllStudents) throw new TeacherRoleError("HAS_LINKS");
    if (fresh?.role !== "teacher") return { changed: false, count: n };
    tx.update(ref, { role: "independent", updatedAt: FieldValue.serverTimestamp() });
    return { changed: true, count: n };
  });

  const codes = await (deps.revokeActiveCodes ?? noop)(uid);
  let students = 0;
  if ((changed || count > 0) && deps.unlinkAllStudents) {
    students = await deps.unlinkAllStudents(uid);
  }
  await syncClaims({ db: deps.db, auth: deps.auth }, uid);
  log("revokeTeacher", { changed, codes, students });
}
