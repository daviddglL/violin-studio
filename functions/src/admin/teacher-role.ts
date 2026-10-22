import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { syncClaims } from "../identity/claims";
import { revokeActiveCodes } from "../teacher/code-handlers";
import type { ObjectStore } from "../recordings/finalize";
import { unlinkAllStudents } from "../teacher/remove-link";
import { canGrantTeacher, TeacherDenyReason } from "../teacher/eligibility";
import { isAllowedRoleTransition, Role } from "../teacher/role-transitions";

export type TeacherRoleErrorReason =
  | TeacherDenyReason
  | "INVALID_UID"
  | "USER_NOT_FOUND"
  | "INVALID_TRANSITION"
  | "NO_PENDING_REQUEST";

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
  /** Objetos de las demostraciones que se borran al desvincular (B5b.4). */
  store: ObjectStore;
  /** Sumidero de logs sin PII; por defecto `logger.info` de Functions. */
  log?: (message: string, data: Record<string, unknown>) => void;
  /** Revoca los codigos de vinculo activos del profesor; devuelve cuantos. Por defecto, `revokeActiveCodes`. */
  revokeActiveCodes?: (teacherUid: string) => Promise<number>;
  /** Desvincula a todos los alumnos del profesor ; devuelve cuantos. Por defecto, `unlinkAllStudents` (`removeLink`). */
  unlinkAllStudents?: (teacherUid: string) => Promise<number>;
}

/** Los ids de vinculo son `{t}_{s}`: un uid con `_` romperia el separador (design §3.1). */
function requireValidUid(uid: string): void {
  if (typeof uid !== "string" || uid.length === 0 || uid.includes("_") || uid.includes("/")) {
    throw new TeacherRoleError("INVALID_UID");
  }
}

function makeLog(deps: Pick<TeacherRoleDeps, "log">, uid: string) {
  const uidHash = sha256Hex(uid).slice(0, 12);
  const sink = deps.log ?? ((m, d) => logger.info(m, d));
  return (message: string, data: Record<string, unknown> = {}) => sink(message, { uidHash, ...data });
}

/** Solicitud resuelta: conserva `requestedAt` y sella la decision. */
function decided(request: unknown, status: "approved" | "rejected"): Record<string, unknown> {
  const requestedAt = (request as { requestedAt?: unknown } | null)?.requestedAt;
  return { status, ...(requestedAt ? { requestedAt } : {}), decidedAt: FieldValue.serverTimestamp() };
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
      deps.db.collection(COLLECTIONS.teacherLinks).where("studentUid", "==", uid).limit(1),
    );
    const verdict = canGrantTeacher({ profile, emailVerified, now: deps.clock(), hasStudentLinks: !links.empty });
    if (!verdict.eligible) return reject(verdict.reason);
    if (verdict.alreadyTeacher) return false;
    if (!isAllowedRoleTransition(String(profile?.role ?? "independent") as Role, "teacher")) {
      return reject("INVALID_TRANSITION");
    }
    tx.update(ref, {
      role: "teacher",
      studentCount: 0,
      updatedAt: FieldValue.serverTimestamp(),
      // Aprobar una solicitud de la app es este mismo alta; sin solicitud (alta directa) no se crea ninguna.
      ...(profile?.teacherRequest ? { teacherRequest: decided(profile.teacherRequest, "approved") } : {}),
    });
    return true;
  });

  // Tambien en el no-op: un fallo anterior tras escribir el rol deja los claims sin sincronizar.
  await syncClaims({ db: deps.db, auth: deps.auth }, uid);
  log("grantTeacher", { changed });
}

/**
 * Baja de profesor (REQ-TRL-04). Orden decidido (W1/W2): PRIMERO se devuelve el rol `independent` en
 * una transaccion (un canje concurrente exige `role == teacher`, asi que ya no puede crear vinculos ni
 * consumir codigos) y DESPUES se revocan codigos y se desvincula a los alumnos (`removeLink`, que baja
 * `studentCount`). Es reanudable: un `independent` repite siempre la limpieza (no-op cuando no queda nada). Siempre sincroniza claims (idempotente).
 */
export async function revokeTeacher(deps: TeacherRoleDeps, uid: string): Promise<void> {
  requireValidUid(uid);
  const log = makeLog(deps, uid);
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);

  const { changed } = await deps.db.runTransaction(async (tx) => {
    const fresh = (await tx.get(ref)).data();
    if (fresh?.role !== "teacher") return { changed: false };
    // Sin solicitud previa: tras una baja el usuario puede volver a pedirlo desde la app.
    tx.update(ref, { role: "independent", teacherRequest: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp() });
    return { changed: true };
  });

  const linkDeps = { db: deps.db, auth: deps.auth, store: deps.store, log: deps.log };
  const codes = await (deps.revokeActiveCodes ?? ((t) => revokeActiveCodes({ db: deps.db }, t)))(uid);
  // Siempre: la fuente de verdad son los vinculos (consulta indexada, no-op si no hay), no el contador, que puede estar desfasado.
  const students = await (deps.unlinkAllStudents ?? ((t) => unlinkAllStudents(linkDeps, t)))(uid);
  await syncClaims({ db: deps.db, auth: deps.auth }, uid);
  log("revokeTeacher", { changed, codes, students });
}

/** Solicitud pendiente de alta de profesor, para que el administrador decida. */
export interface PendingTeacherRequest {
  uid: string;
  /** ISO 8601, o `null` si el servidor aun no sello la fecha. */
  requestedAt: string | null;
}

/** Solicitudes pendientes, las mas antiguas primero (como mucho [limit]). */
export async function listTeacherRequests(
  deps: Pick<TeacherRoleDeps, "db">,
  limit = 50,
): Promise<PendingTeacherRequest[]> {
  const snap = await deps.db
    .collection(COLLECTIONS.users)
    .where("teacherRequest.status", "==", "pending")
    .limit(limit)
    .get();
  return snap.docs
    .map((d) => {
      const at = d.get("teacherRequest.requestedAt") as { toDate?: () => Date } | undefined;
      return { uid: d.id, requestedAt: at?.toDate ? at.toDate().toISOString() : null };
    })
    .sort((a, b) => (a.requestedAt ?? "").localeCompare(b.requestedAt ?? ""));
}

/**
 * Rechaza una solicitud pendiente; el usuario no puede volver a pedirlo durante `TEACHER_REQUEST_COOLDOWN_DAYS`.
 * Sin solicitud pendiente responde `NO_PENDING_REQUEST` y no escribe.
 */
export async function rejectTeacherRequest(deps: Pick<TeacherRoleDeps, "db" | "log">, uid: string): Promise<void> {
  requireValidUid(uid);
  const log = makeLog(deps, uid);
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);
  const ok = await deps.db.runTransaction(async (tx) => {
    const request = (await tx.get(ref)).get("teacherRequest") as { status?: unknown } | undefined;
    if (request?.status !== "pending") return false;
    tx.update(ref, { teacherRequest: decided(request, "rejected"), updatedAt: FieldValue.serverTimestamp() });
    return true;
  });
  if (!ok) {
    log("rejectTeacherRequest.rejected", { reason: "NO_PENDING_REQUEST" });
    throw new TeacherRoleError("NO_PENDING_REQUEST");
  }
  log("rejectTeacherRequest", {});
}
