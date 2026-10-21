import { DocumentData, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { ErrorReason, fail } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { requireObject } from "../common/validation";
import {
  CURRENT_POLICY_VERSION,
  MAX_ACTIVE_CODES,
  MAX_CODES_PER_24H,
  TEACHER_CODE_TTL_DAYS,
} from "../config/identity";
import { adultFromProfile } from "./eligibility";
import { generateCode, hashCode, RandomInt } from "./codes";
import { requireCodePepper } from "./pepper";

const DAY_MS = 86_400_000;
const HEX64 = /^[0-9a-f]{64}$/;

/** Dependencias de revocar/listar: no necesitan el pepper. */
export interface BaseCodeDeps {
  db: Firestore;
  clock?: Clock;
  /** Version vigente de la politica; solo se inyecta en tests. */
  policyVersion?: number;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/** Solo `createTeacherCode` necesita el pepper. */
export interface CodeDeps extends BaseCodeDeps {
  /** Valor del secreto `TEACHER_CODE_PEPPER` (se valida al usarlo). */
  pepper: string;
  random?: RandomInt;
}

export interface CreatedCode {
  /** Id del doc (HMAC): identificador opaco para revocar. */
  id: string;
  /** Codigo en claro: se devuelve UNA vez y no se guarda. */
  code: string;
  codeHint: string;
  /** Epoch ms. */
  expiresAt: number;
}

export interface CodeSummary {
  id: string;
  codeHint: string;
  /** Epoch ms. */
  expiresAt: number;
}

const nowOf = (deps: { clock?: Clock }): Date => (deps.clock ?? (() => new Date()))();

function makeLog(deps: { log?: CodeDeps["log"] }, uid: string) {
  const uidHash = sha256Hex(uid).slice(0, 12);
  const sink = deps.log ?? ((m, d) => logger.info(m, d));
  return (message: string, data: Record<string, unknown> = {}) => sink(message, { uidHash, ...data });
}

/** Decide contra el doc (no el claim): profesor adulto, consentimiento vigente y sin borrado en curso. */
function requireActiveTeacher(deps: BaseCodeDeps, profile: DocumentData | undefined, now: Date): void {
  if (!profile || profile.deletion) throw fail("failed-precondition", ErrorReason.NO_PROFILE);
  if (profile.role !== "teacher") throw fail("permission-denied", ErrorReason.NOT_TEACHER);
  if (!adultFromProfile(profile.birthDate, now)) throw fail("permission-denied", ErrorReason.NOT_TEACHER);
  if (profile.consentStatus !== "granted" || profile.policyVersion !== (deps.policyVersion ?? CURRENT_POLICY_VERSION)) {
    throw fail("failed-precondition", ErrorReason.NO_ACTIVE_CONSENT);
  }
}

const millis = (v: unknown): number => (v as Timestamp).toMillis();
const isActive = (d: DocumentData, nowMs: number): boolean =>
  d.usedBy == null && d.usedAt == null && d.revokedAt == null && millis(d.expiresAt) > nowMs;

/** REQ-LNK-01. Cupos (5 activos, 20 en 24 h) comprobados y escritos en una transaccion. */
export async function createTeacherCodeHandler(deps: CodeDeps, uid: string): Promise<CreatedCode> {
  const now = nowOf(deps);
  const nowMs = now.getTime();
  const log = makeLog(deps, uid);
  const code = generateCode(deps.random);
  const id = hashCode(requireCodePepper(deps.pepper), code); // falla antes de tocar nada si el pepper no vale
  const userRef = deps.db.collection(COLLECTIONS.users).doc(uid);
  const codes = deps.db.collection(COLLECTIONS.teacherCodes);
  const expiresAtMs = nowMs + TEACHER_CODE_TTL_DAYS * DAY_MS;

  let limite: { activos: number; en24h: number } | undefined;
  await deps.db.runTransaction(async (tx) => {
    limite = undefined; // la transaccion puede reintentarse
    requireActiveTeacher(deps, (await tx.get(userRef)).data(), now);
    // Todo codigo creado en 24 h sigue sin caducar (TTL 7 d): una sola consulta sirve a ambos cupos.
    const vivos = (
      await tx.get(codes.where("teacherUid", "==", uid).where("expiresAt", ">", Timestamp.fromMillis(nowMs)))
    ).docs.map((d) => d.data());
    const activos = vivos.filter((d) => isActive(d, nowMs)).length;
    const en24h = vivos.filter((d) => millis(d.createdAt) > nowMs - DAY_MS).length;
    if (activos >= MAX_ACTIVE_CODES || en24h >= MAX_CODES_PER_24H) {
      limite = { activos, en24h };
      throw fail("resource-exhausted", ErrorReason.CODE_LIMIT_REACHED);
    }
    tx.create(codes.doc(id), {
      teacherUid: uid,
      createdAt: Timestamp.fromMillis(nowMs),
      expiresAt: Timestamp.fromMillis(expiresAtMs),
      expireAt: Timestamp.fromMillis(expiresAtMs + TEACHER_CODE_TTL_DAYS * DAY_MS),
      usedBy: null,
      usedAt: null,
      revokedAt: null,
      codeHint: code.slice(-2),
    });
  }).catch((e: unknown) => {
    // Se registra fuera de la transaccion: un reintento no duplica la linea.
    if (limite) log("createTeacherCode.limit", limite);
    throw e;
  });

  log("createTeacherCode");
  return { id, code, codeHint: code.slice(-2), expiresAt: expiresAtMs };
}

/** REQ-LNK-02. Solo el propietario; idempotente; un codigo ya canjeado no cambia. */
export async function revokeTeacherCodeHandler(
  deps: BaseCodeDeps,
  uid: string,
  data: unknown,
): Promise<{ revoked: boolean }> {
  const raw = requireObject(data).id;
  if (typeof raw !== "string" || !HEX64.test(raw)) {
    throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: "id" });
  }
  const nowMs = nowOf(deps).getTime();
  const log = makeLog(deps, uid);
  const ref = deps.db.collection(COLLECTIONS.teacherCodes).doc(raw);

  const revoked = await deps.db.runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data();
    // Inexistente y ajeno se responden igual: no revela que ids existen.
    if (!d || d.teacherUid !== uid) throw fail("not-found", ErrorReason.CODE_NOT_FOUND);
    if (d.revokedAt != null || d.usedBy != null || d.usedAt != null) return false;
    tx.update(ref, { revokedAt: Timestamp.fromMillis(nowMs) });
    return true;
  });
  log("revokeTeacherCode", { revoked });
  return { revoked };
}

/** REQ-LNK-02. Solo codigos activos; sin codigo en claro ni campos internos. */
export async function listTeacherCodesHandler(deps: BaseCodeDeps, uid: string): Promise<{ codes: CodeSummary[] }> {
  const now = nowOf(deps);
  const nowMs = now.getTime();
  requireActiveTeacher(deps, (await deps.db.collection(COLLECTIONS.users).doc(uid).get()).data(), now);
  const snap = await deps.db
    .collection(COLLECTIONS.teacherCodes)
    .where("teacherUid", "==", uid)
    .where("expiresAt", ">", Timestamp.fromMillis(nowMs))
    .orderBy("expiresAt")
    .get();
  const codes = snap.docs
    .filter((d) => isActive(d.data(), nowMs))
    .map((d) => ({ id: d.id, codeHint: String(d.data().codeHint), expiresAt: millis(d.data().expiresAt) }));
  return { codes };
}

/** Revoca todos los codigos activos del profesor (hook de `revoke-teacher`); devuelve cuantos. Idempotente. */
export async function revokeActiveCodes(deps: Pick<BaseCodeDeps, "db" | "clock">, teacherUid: string): Promise<number> {
  const nowMs = nowOf(deps).getTime();
  const snap = await deps.db
    .collection(COLLECTIONS.teacherCodes)
    .where("teacherUid", "==", teacherUid)
    .where("expiresAt", ">", Timestamp.fromMillis(nowMs))
    .get();
  const vivos = snap.docs.filter((d) => isActive(d.data(), nowMs));
  for (let i = 0; i < vivos.length; i += 400) {
    const batch = deps.db.batch();
    for (const d of vivos.slice(i, i + 400)) batch.update(d.ref, { revokedAt: Timestamp.fromMillis(nowMs) });
    await batch.commit();
  }
  return vivos.length;
}
