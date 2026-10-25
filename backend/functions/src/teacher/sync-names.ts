import { DocumentData, Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";

/** Copia minima del perfil en el vinculo (REQ-LNK-09): nunca email, `birthDate` ni `isMinor`. */
const SYNCED = ["displayName", "instrument"] as const;

const same = (a: unknown, b: unknown) => (a ?? null) === (b ?? null);

/**
 * Trigger de `users/{uid}`: si cambia `displayName`/`instrument`, escribe valores ABSOLUTOS (releidos del perfil) en los vinculos
 * del uid (como profesor y como alumno). Cualquier otro cambio, o un doc borrado, no consulta ni escribe.
 * Repetir el evento deja el mismo estado; solo se escriben los vinculos que realmente difieren.
 */
export async function syncLinkNamesHandler(
  deps: { db: Firestore },
  uid: string,
  before: DocumentData | undefined,
  after: DocumentData | undefined,
): Promise<void> {
  if (!after || SYNCED.every((k) => same(before?.[k], after[k]))) return;
  // Se relee el perfil: un evento atrasado o reintentado no debe escribir un valor ya superado.
  const current = (await deps.db.collection(COLLECTIONS.users).doc(uid).get()).data();
  if (!current) return;
  const links = deps.db.collection(COLLECTIONS.teacherLinks);
  const displayName = typeof current.displayName === "string" ? current.displayName : "";
  const instrument = typeof current.instrument === "string" ? current.instrument : null;

  const [asTeacher, asStudent] = await Promise.all([
    links.where("teacherUid", "==", uid).get(),
    links.where("studentUid", "==", uid).get(),
  ]);
  const writes = [
    ...asTeacher.docs
      .filter((d) => d.data().teacherDisplayName !== displayName)
      .map((d) => d.ref.update({ teacherDisplayName: displayName })),
    ...asStudent.docs
      .filter((d) => d.data().studentDisplayName !== displayName || !same(d.data().studentInstrument, instrument))
      .map((d) => d.ref.update({ studentDisplayName: displayName, studentInstrument: instrument })),
  ];
  // Un vinculo borrado entre la consulta y la escritura (NOT_FOUND) ya no necesita copia.
  const results = await Promise.allSettled(writes);
  for (const r of results) {
    if (r.status === "rejected" && (r.reason as { code?: unknown }).code !== 5) throw r.reason;
  }
}
