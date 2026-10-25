/** Mismo patron que `unlinkTeacher`. Sin `_` ni `-`: el id `{t}_{s}` no puede ser ambiguo. */
const LINK_UID = /^[A-Za-z0-9]{1,128}$/;

/**
 * Id del vinculo `teacherLinks/{t}_{s}`. Se niega a construirlo si algun uid contiene `_`
 * (`a_b`+`c` y `a`+`b_c` darian el mismo id). Los uid de Firebase Auth son alfanumericos.
 */
export function linkId(teacherUid: string, studentUid: string): string {
  if (!LINK_UID.test(teacherUid) || !LINK_UID.test(studentUid)) throw new Error("uid invalido para el id de vinculo");
  return `${teacherUid}_${studentUid}`;
}
