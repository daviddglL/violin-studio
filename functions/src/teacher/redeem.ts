import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore, Timestamp } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { requireObject } from "../common/validation";
import { syncClaims } from "../identity/claims";
import { hashCode, parseCodeInput } from "./codes";
import { BaseCodeDeps, makeLog, nowOf } from "./code-handlers";
import { requireCodePepper } from "./pepper";

export interface RedeemDeps extends BaseCodeDeps {
  auth: Auth;
  /** Valor del secreto `TEACHER_CODE_PEPPER`. */
  pepper: string;
}

export interface RedeemResult {
  linked: true;
  teacherUid: string;
}

/** REQ-LNK-03/07. Canje transaccional: vinculo, codigo usado, contadores y rol del alumno. */
export async function redeemTeacherCodeHandler(deps: RedeemDeps, uid: string, data: unknown): Promise<RedeemResult> {
  const input = requireObject(data);
  const code = parseCodeInput(input.code);
  if (!code) throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "code" });
  const nowMs = nowOf(deps).getTime();
  const codeRef = deps.db.collection(COLLECTIONS.teacherCodes).doc(hashCode(requireCodePepper(deps.pepper), code));
  const studentRef = deps.db.collection(COLLECTIONS.users).doc(uid);

  const teacherUid = await deps.db.runTransaction(async (tx) => {
    const c = (await tx.get(codeRef)).data();
    const t = String(c?.teacherUid);
    const teacherRef = deps.db.collection(COLLECTIONS.users).doc(t);
    const student = (await tx.get(studentRef)).data() ?? {};
    const teacher = (await tx.get(teacherRef)).data() ?? {};
    const at = Timestamp.fromMillis(nowMs);
    tx.create(deps.db.collection(COLLECTIONS.teacherLinks).doc(`${t}_${uid}`), {
      teacherUid: t,
      studentUid: uid,
      teacherDisplayName: teacher.displayName,
      studentDisplayName: student.displayName,
      studentInstrument: student.instrument,
      ackVersion: input.ackVersion,
      createdAt: at,
    });
    tx.update(codeRef, { usedBy: uid, usedAt: at });
    tx.update(teacherRef, { studentCount: FieldValue.increment(1) });
    tx.update(studentRef, { teacherCount: FieldValue.increment(1), role: "student" });
    return t;
  });
  await syncClaims(deps, uid);
  makeLog(deps, uid)("redeemTeacherCode");
  return { linked: true, teacherUid };
}
