import { Auth } from "firebase-admin/auth";
import { DocumentData, DocumentReference, FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { requireObject } from "../common/validation";
import { CURRENT_POLICY_VERSION, REDEEM_MAX_FAILURES_PER_HOUR } from "../config/identity";
import { checkRateLimit } from "../guardian/rate-limit";
import { syncClaims } from "../identity/claims";
import { hashCode, parseCodeInput } from "./codes";
import { BaseCodeDeps, makeLog, nowOf } from "./code-handlers";
import { canRedeem, RedeemDenyReason, studentDenial } from "./eligibility";
import { requireCodePepper } from "./pepper";
import { linkId } from "./link-id";

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

const DENIALS: Record<RedeemDenyReason, () => HttpsError> = {
  NOTICE_OUTDATED: () => fail("failed-precondition", ErrorReason.NOTICE_OUTDATED),
  // REQ-LNK-05: resource-exhausted. Cuenta como fallo: si no, se podrian sondear codigos de profesores llenos sin coste.
  TEACHER_FULL: () => fail("resource-exhausted", ErrorReason.TEACHER_FULL),
  ALREADY_LINKED: () => fail("failed-precondition", ErrorReason.ALREADY_LINKED),
  TEACHER_UNAVAILABLE: notFound,
  STUDENT_INACTIVE: () => fail("permission-denied", ErrorReason.NOT_ELIGIBLE),
  NO_CONSENT: () => fail("permission-denied", ErrorReason.NOT_ELIGIBLE),
  STUDENT_IS_TEACHER: () => fail("permission-denied", ErrorReason.NOT_ELIGIBLE),
};

/** Motivos que dejan el intento reservado como fallo; cualquier otro lo libera. */
const COUNTED: readonly string[] = [ErrorReason.CODE_NOT_FOUND, ErrorReason.TEACHER_FULL];

const FAILURES = "linkRedeem.failures";
const toMillis = (v: unknown): number[] => (Array.isArray(v) ? v.map((t) => (t as Timestamp).toMillis()) : []);
const failuresOf = (profile: DocumentData | undefined): number[] =>
  toMillis((profile?.linkRedeem as { failures?: unknown } | undefined)?.failures);
const stamp = (ms: number[]) => ms.map((t) => Timestamp.fromMillis(t));
/** Quita UNA reserva con esa marca de tiempo. */
const withoutOne = (ms: number[], nowMs: number): number[] => {
  const i = ms.indexOf(nowMs);
  return i < 0 ? ms : [...ms.slice(0, i), ...ms.slice(i + 1)];
};

type Gate = "reserved" | "linked";
type Log = ReturnType<typeof makeLog>;

/**
 * Antes de mirar el codigo: reglas propias del alumno (uniformes con cualquier codigo, no cuentan) y, si
 * procede, RESERVA un intento (limite + marca) en una transaccion corta sobre `users/{s}`. La reserva queda como
 * fallo salvo que el canje la retire al prosperar. Si la reserva no se puede escribir, la peticion falla.
 */
async function gate(deps: RedeemDeps, ref: DocumentReference, nowMs: number, ackVersion: unknown, log: Log): Promise<Gate> {
  try {
    return await deps.db.runTransaction(
      async (tx): Promise<Gate> => {
        const student = (await tx.get(ref)).data();
        const denied = studentDenial({ student, ackVersion, policyVersion: deps.policyVersion });
        if (denied === "ALREADY_LINKED") return "linked"; // solo puede ser un reintento propio; se decide fuera
        if (denied) throw DENIALS[denied]();
        const limit = checkRateLimit(failuresOf(student), nowMs, REDEEM_MAX_FAILURES_PER_HOUR, HOUR_MS);
        if (!limit.allowed) {
          log("redeemTeacherCode.rateLimited");
          throw fail("resource-exhausted", ErrorReason.RATE_LIMITED, { retryAfterSeconds: limit.retryAfterSeconds });
        }
        tx.update(ref, { [FAILURES]: stamp(limit.sends) });
        return "reserved";
      },
      { maxAttempts: 10 },
    );
  } catch (e) {
    if (e instanceof HttpsError) throw e;
    log("redeemTeacherCode.reserveFailed");
    throw fail("unavailable", ErrorReason.TEMPORARILY_UNAVAILABLE);
  }
}

/** Libera la reserva de un intento que no debe contar (best-effort: en el peor caso cuenta de mas). */
async function release(db: Firestore, ref: DocumentReference, nowMs: number): Promise<void> {
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) tx.update(ref, { [FAILURES]: stamp(withoutOne(failuresOf(snap.data()), nowMs)) });
  });
}

const usable = (c: DocumentData, nowMs: number): boolean =>
  c.expiresAt instanceof Timestamp && c.expiresAt.toMillis() > nowMs;

/** REQ-LNK-03..07. Canje transaccional; los codigos inutilizables y el cupo lleno suman fallos (5/h por alumno). */
export async function redeemTeacherCodeHandler(deps: RedeemDeps, uid: string, data: unknown): Promise<RedeemResult> {
  const input = requireObject(data);
  const code = parseCodeInput(input.code);
  if (!code) throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "code" });
  const now = nowOf(deps);
  const nowMs = now.getTime();
  const log = makeLog(deps, uid);
  const codeRef = deps.db.collection(COLLECTIONS.teacherCodes).doc(hashCode(requireCodePepper(deps.pepper), code));
  const studentRef = deps.db.collection(COLLECTIONS.users).doc(uid);

  const linkOf = (t: string) => deps.db.collection(COLLECTIONS.teacherLinks).doc(linkId(t, uid));
  const done = async (t: string): Promise<RedeemResult> => {
    // Si esto falla el error sube: el reintento entra por la rama idempotente y vuelve a sincronizar.
    await syncClaims(deps, uid);
    log("redeemTeacherCode");
    return { linked: true, teacherUid: t };
  };

  if ((await gate(deps, studentRef, nowMs, input.ackVersion, log)) === "linked") {
    // Alumno ya vinculado: solo el reintento de SU propio codigo (vinculo existente) tiene exito; no mide el limite.
    const c = (await codeRef.get()).data();
    if (c?.usedBy === uid && typeof c.teacherUid === "string" && (await linkOf(c.teacherUid).get()).exists) {
      return done(c.teacherUid);
    }
    throw DENIALS.ALREADY_LINKED();
  }

  let teacherUid: string;
  try {
    teacherUid = await deps.db.runTransaction(async (tx) => {
      const c = (await tx.get(codeRef)).data();
      if (!c || typeof c.teacherUid !== "string") throw notFound();
      const t: string = c.teacherUid;
      const teacherRef = deps.db.collection(COLLECTIONS.users).doc(t);
      const student = (await tx.get(studentRef)).data();
      const unreserved = { [FAILURES]: stamp(withoutOne(failuresOf(student), nowMs)) };
      if (c.usedBy === uid && (await tx.get(linkOf(t))).exists) {
        tx.update(studentRef, unreserved); // reintento en carrera con su propio canje: exito
        return t;
      }
      if (!usable(c, nowMs) || c.usedBy != null || c.usedAt != null || c.revokedAt != null) throw notFound();
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
      tx.create(linkOf(t), {
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
      tx.update(studentRef, {
        ...unreserved,
        teacherCount: FieldValue.increment(1),
        role: "student",
        updatedAt: FieldValue.serverTimestamp(),
      });
      return t;
    });
  } catch (e) {
    const reason = (e as { details?: { reason?: unknown } }).details?.reason;
    if (typeof reason === "string") log("redeemTeacherCode.rejected", { reason });
    if (typeof reason !== "string" || !COUNTED.includes(reason)) {
      await release(deps.db, studentRef, nowMs).catch(() => log("redeemTeacherCode.releaseFailed"));
    }
    throw e;
  }
  return done(teacherUid);
}
