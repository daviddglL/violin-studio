import { Auth } from "firebase-admin/auth";
import { FieldValue, Firestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { syncClaims } from "../identity/claims";
import { linkId } from "./link-id";

/** Paso idempotente posterior a la transaccion; se repite entero en cada llamada. */
export type LinkCleanupStep = (teacherUid: string, studentUid: string) => Promise<void>;

export interface RemoveLinkDeps {
  db: Firestore;
  auth: Auth;
  /** Sumidero de logs sin PII; por defecto `logger.info`. */
  log?: (message: string, data: Record<string, unknown>) => void;
  /**
   * Limpiezas posteriores a la transaccion, en orden. Vacio hasta que existan los datos a limpiar:
   * TODO(A6) borrar tareas `open` del profesor en `users/{s}/assignments` (conservar `done`);
   * TODO(B5b/B10) borrar demostraciones del profesor con `sharedWith == s` (`deleteRecording`).
   */
  cleanupSteps?: readonly LinkCleanupStep[];
}

const hash = (uid: string) => sha256Hex(uid).slice(0, 12);
const count = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) && v > 0 ? Math.floor(v) : 0);
/** Un usuario con borrado en curso no se toca: la cascada de borrado es la duena de su doc. */
const writable = (d: { exists: boolean; data(): Record<string, unknown> | undefined }) =>
  d.exists && d.data()?.deletion == null;

/**
 * Desvinculacion (REQ-LNK-08), compartida por la callable, `revoke-teacher` y (A5) la cascada de borrado.
 *
 * Transaccion: si el vinculo existe lo borra, baja ambos contadores (nunca por debajo de 0), devuelve al
 * alumno a `independent` cuando no le quedan profesores y deja en `users/{s}.pendingUnlinks` una marca
 * `teacherUid` hasta que terminen las limpiezas y los claims. Esa marca hace reanudable la llamada SIN
 * permitir que un tercero dispare limpiezas sobre usuarios ajenos: con el vinculo ausente solo se ejecutan
 * limpiezas y claims si hay marca pendiente (en otro caso la llamada es un no-op). Un usuario con `deletion`
 * no se modifica (el vinculo si se borra y el otro lado se ajusta). Un alumno sin usuario en Auth (borrado
 * a medias) no es un error: no hay claims que sincronizar.
 */
export async function removeLink(deps: RemoveLinkDeps, teacherUid: string, studentUid: string): Promise<void> {
  const { db } = deps;
  const linkRef = db.collection(COLLECTIONS.teacherLinks).doc(linkId(teacherUid, studentUid));
  const teacherRef = db.collection(COLLECTIONS.users).doc(teacherUid);
  const studentRef = db.collection(COLLECTIONS.users).doc(studentUid);
  const log = deps.log ?? ((m, d) => logger.info(m, d));
  const ids = { uidHash: hash(studentUid), teacherUidHash: hash(teacherUid) };

  const { removed, pending } = await db.runTransaction(async (tx) => {
    const [link, teacher, student] = await Promise.all([tx.get(linkRef), tx.get(teacherRef), tx.get(studentRef)]);
    const marks = student.data()?.pendingUnlinks;
    const marked = Array.isArray(marks) && marks.includes(teacherUid);
    if (!link.exists) return { removed: false, pending: marked };
    tx.delete(linkRef);
    if (writable(teacher)) tx.update(teacherRef, { studentCount: Math.max(0, count(teacher.data()?.studentCount) - 1) });
    if (writable(student)) {
      const teachers = Math.max(0, count(student.data()?.teacherCount) - 1);
      tx.update(studentRef, {
        teacherCount: teachers,
        ...(teachers === 0 && student.data()?.role === "student" ? { role: "independent" } : {}),
        pendingUnlinks: FieldValue.arrayUnion(teacherUid),
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
    return { removed: true, pending: true };
  });
  if (!removed && !pending) {
    log("removeLink", { ...ids, removed: false, resumed: false });
    return;
  }

  for (const step of deps.cleanupSteps ?? []) await step(teacherUid, studentUid);
  try {
    await syncClaims(deps, studentUid);
  } catch (e) {
    if ((e as { code?: unknown }).code !== "auth/user-not-found") throw e;
    log("removeLink.authUserGone", ids);
  }
  // Todo hecho: se retira la marca (solo si el doc existe y no esta en borrado).
  await db.runTransaction(async (tx) => {
    const student = await tx.get(studentRef);
    if (writable(student)) tx.update(studentRef, { pendingUnlinks: FieldValue.arrayRemove(teacherUid) });
  });
  log("removeLink", { ...ids, removed, resumed: !removed });
}

/**
 * Desvincula a todos los alumnos del profesor: los vinculos vivos y los que quedaron con limpieza pendiente
 * (`pendingUnlinks`). Un fallo en un alumno no detiene a los demas; al final lanza un error agregado (sin
 * uids). Reanudable. Devuelve cuantos alumnos proceso.
 */
export async function unlinkAllStudents(deps: RemoveLinkDeps, teacherUid: string): Promise<number> {
  const [links, pending] = await Promise.all([
    deps.db.collection(COLLECTIONS.teacherLinks).where("teacherUid", "==", teacherUid).get(),
    deps.db.collection(COLLECTIONS.users).where("pendingUnlinks", "array-contains", teacherUid).get(),
  ]);
  const students = new Set<string>(pending.docs.map((d) => d.id));
  for (const doc of links.docs) {
    const s = doc.data().studentUid;
    if (typeof s === "string") students.add(s);
  }
  let failed = 0;
  for (const studentUid of students) {
    try {
      await removeLink(deps, teacherUid, studentUid);
    } catch {
      failed += 1;
    }
  }
  if (failed > 0) throw new Error(`unlinkAllStudents: ${failed} of ${students.size} students failed`);
  return students.size;
}
