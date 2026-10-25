import { FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { canGrantTeacher, TeacherDenyReason, TeacherEligibility } from "./eligibility";

/**
 * Solicitud de alta de profesor desde la app (decision de David, 2026-10-22): el adulto la pide y un administrador la
 * aprueba con `admin:grant-teacher` o la rechaza con `admin:reject-teacher`. El cliente NUNCA obtiene el rol: solo se
 * marca `users/{uid}.teacherRequest` (`status`, `requestedAt`, `decidedAt`), que las reglas no dejan escribir.
 */
export type TeacherRequestStatus = "pending" | "approved" | "rejected";

/** Tras un rechazo no se puede volver a pedir hasta pasado este plazo. */
export const TEACHER_REQUEST_COOLDOWN_DAYS = 30;
const DAY_MS = 86_400_000;

export type TeacherRequestDecision =
  | { kind: "deny"; reason: TeacherDenyReason }
  | { kind: "keep"; status: TeacherRequestStatus }
  | { kind: "create" };

const millisOf = (v: unknown): number | undefined =>
  v instanceof Timestamp ? v.toMillis() : v instanceof Date ? v.getTime() : undefined;

/** Funcion pura: que hacer con una solicitud dada la elegibilidad y la solicitud que ya hubiera. */
export function decideTeacherRequest(
  verdict: TeacherEligibility,
  existing: unknown,
  now: Date,
): TeacherRequestDecision {
  if (!verdict.eligible) return { kind: "deny", reason: verdict.reason };
  if (verdict.alreadyTeacher) return { kind: "keep", status: "approved" };
  const req = (existing ?? {}) as { status?: unknown; decidedAt?: unknown };
  if (req.status === "pending") return { kind: "keep", status: "pending" };
  if (req.status === "rejected") {
    const decided = millisOf(req.decidedAt);
    // Sin fecha legible se trata como reciente (fail-closed).
    if (decided === undefined || now.getTime() - decided < TEACHER_REQUEST_COOLDOWN_DAYS * DAY_MS) {
      return { kind: "keep", status: "rejected" };
    }
  }
  return { kind: "create" };
}

export interface TeacherRequestDeps {
  db: Firestore;
  clock?: Clock;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/** `requestTeacherRole`: idempotente; una solicitud pendiente o reciente no se reescribe. */
export async function requestTeacherRoleHandler(
  deps: TeacherRequestDeps,
  uid: string,
  emailVerified: boolean,
): Promise<{ status: TeacherRequestStatus }> {
  const now = (deps.clock ?? (() => new Date()))();
  const ref = deps.db.collection(COLLECTIONS.users).doc(uid);
  const decision = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const profile = snap.exists ? (snap.data() ?? {}) : undefined;
    const links = await tx.get(
      deps.db.collection(COLLECTIONS.teacherLinks).where("studentUid", "==", uid).limit(1),
    );
    const verdict = canGrantTeacher({ profile, emailVerified, now, hasStudentLinks: !links.empty });
    const d = decideTeacherRequest(verdict, profile?.teacherRequest, now);
    if (d.kind === "create") {
      tx.update(ref, { teacherRequest: { status: "pending", requestedAt: FieldValue.serverTimestamp() } });
    }
    return d;
  });
  const uidHash = sha256Hex(uid).slice(0, 12);
  (deps.log ?? ((m, d) => logger.info(m, d)))("requestTeacherRole", {
    uidHash,
    outcome: decision.kind === "deny" ? decision.reason : decision.kind,
  });
  if (decision.kind === "deny") throw fail("failed-precondition", ErrorReason.NOT_ELIGIBLE, { deny: decision.reason });
  return { status: decision.kind === "create" ? "pending" : decision.status };
}
