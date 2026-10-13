import { Auth } from "firebase-admin/auth";
import { DocumentReference, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { requireObject } from "../common/validation";
import { CURRENT_POLICY_VERSION, REDEEM_MAX_FAILURES_PER_HOUR } from "../config/identity";
import { checkRateLimit } from "../guardian/rate-limit";
import { syncClaims } from "../identity/claims";
import { hashCode, parseCodeInput } from "./codes";
import { BaseCodeDeps, makeLog, nowOf } from "./code-handlers";
import { canRedeem, RedeemDenyReason } from "./eligibility";
import { requireCodePepper } from "./pepper";

const HOUR_MS = 3_600_000;

export interface RedeemDeps extends BaseCodeDeps {
  auth: Auth;
  /** Valor del secreto `TEACHER_CODE_PEPPER`. */
  pepper: string;
}

export interface RedeemResult {
  linked: true;
  teacherUid: string;
}

/** Un solo error para todo codigo inutilizable: no revela si existe, caduco, se revoco o es de otro. */
const notFound = () => fail("not-found", ErrorReason.CODE_NOT_FOUND);

const DENIALS: Record<RedeemDenyReason, () => ReturnType<typeof fail>> = {
  NOTICE_OUTDATED: () => fail("failed-precondition", ErrorReason.NOTICE_OUTDATED),
  TEACHER_FULL: () => fail("failed-precondition", ErrorReason.TEACHER_FULL),
  ALREADY_LINKED: () => fail("failed-precondition", ErrorReason.ALREADY_LINKED),
  TEACHER_UNAVAILABLE: notFound,
  STUDENT_INACTIVE: () => fail("permission-denied", ErrorReason.NOT_ELIGIBLE),
  NO_CONSENT: () => fail("permission-denied", ErrorReason.NOT_ELIGIBLE),
  STUDENT_IS_TEACHER: () => fail("permission-denied", ErrorReason.NOT_ELIGIBLE),
};

const toMillis = (v: unknown): number[] => (Array.isArray(v) ? v.map((t) => (t as Timestamp).toMillis()) : []);
const failuresOf = (profile: Record<string, unknown> | undefined): number[] =>
  toMillis((profile?.linkRedeem as { failures?: unknown } | undefined)?.failures);

/** Anota un fallo en `users/{s}.linkRedeem.failures` en su propia transaccion (la del canje ya aborto). Best-effort. */
async function recordFailure(db: Firestore, ref: DocumentReference, nowMs: number): Promise<void> {
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return; // sin perfil no se crea un doc fantasma
    const { sends } = checkRateLimit(failuresOf(snap.data()), nowMs, Infinity, HOUR_MS);
    tx.update(ref, { linkRedeem: { failures: sends.map((t) => Timestamp.fromMillis(t)) } });
  });
}

/** REQ-LNK-03..07. Canje transaccional; solo los codigos inutilizables suman fallos (5/h por alumno). */
export async function redeemTeacherCodeHandler(deps: RedeemDeps, uid: string, data: unknown): Promise<RedeemResult> {
  const input = requireObject(data);
  const code = parseCodeInput(input.code);
  if (!code) throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "code" });
  const now = nowOf(deps);
  const nowMs = now.getTime();
  const log = makeLog(deps, uid);
  const codeRef = deps.db.collection(COLLECTIONS.teacherCodes).doc(hashCode(requireCodePepper(deps.pepper), code));
  const studentRef = deps.db.collection(COLLECTIONS.users).doc(uid);

  const limit = checkRateLimit(failuresOf((await studentRef.get()).data()), nowMs, REDEEM_MAX_FAILURES_PER_HOUR, HOUR_MS);
  if (!limit.allowed) {
    log("redeemTeacherCode.rateLimited");
    throw fail("resource-exhausted", ErrorReason.RATE_LIMITED, { retryAfterSeconds: limit.retryAfterSeconds });
  }

  let teacherUid: string;
  try {
    teacherUid = await deps.db.runTransaction(async (tx) => {
      const c = (await tx.get(codeRef)).data();
      if (!c || typeof c.teacherUid !== "string") throw notFound();
      const t: string = c.teacherUid;
      const linkRef = deps.db.collection(COLLECTIONS.teacherLinks).doc(`${t}_${uid}`);
      if (c.usedBy === uid) {
        // Reintento del mismo alumno (p. ej. tras un timeout): exito si el vinculo existe.
        if ((await tx.get(linkRef)).exists) return t;
        throw notFound();
      }
      if (c.usedBy != null || c.usedAt != null || c.revokedAt != null || (c.expiresAt as Timestamp).toMillis() <= nowMs) {
        throw notFound();
      }
      const teacherRef = deps.db.collection(COLLECTIONS.users).doc(t);
      const student = (await tx.get(studentRef)).data();
      const teacher = (await tx.get(teacherRef)).data(); // releido aqui: el rol y el consentimiento cuentan al canjear
      const verdict = canRedeem({
        student,
        teacher,
        ackVersion: input.ackVersion,
        now,
        policyVersion: deps.policyVersion ?? CURRENT_POLICY_VERSION,
      });
      if (!verdict.ok) throw DENIALS[verdict.reason]();
      const at = Timestamp.fromMillis(nowMs);
      tx.create(linkRef, {
        teacherUid: t,
        studentUid: uid,
        teacherDisplayName: teacher?.displayName ?? "",
        studentDisplayName: student?.displayName ?? "",
        studentInstrument: student?.instrument ?? null,
        ackVersion: input.ackVersion,
        createdAt: at,
      });
      tx.update(codeRef, { usedBy: uid, usedAt: at });
      tx.update(teacherRef, { studentCount: FieldValue.increment(1) });
      tx.update(studentRef, { teacherCount: FieldValue.increment(1), role: "student", updatedAt: FieldValue.serverTimestamp() });
      return t;
    });
  } catch (e) {
    const reason = (e as { details?: { reason?: unknown } }).details?.reason;
    if (reason === ErrorReason.CODE_NOT_FOUND) {
      // Fuera de la transaccion fallida (no persistiria nada dentro).
      await recordFailure(deps.db, studentRef, nowMs).catch(() => log("redeemTeacherCode.recordFailureFailed"));
    }
    if (typeof reason === "string") log("redeemTeacherCode.rejected", { reason });
    throw e;
  }
  await syncClaims(deps, uid);
  log("redeemTeacherCode");
  return { linked: true, teacherUid };
}
