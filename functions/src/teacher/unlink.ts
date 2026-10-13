import { ErrorReason, fail } from "../common/errors";
import { sha256Hex } from "../common/hashing";
import { requireObject } from "../common/validation";
import { removeLink, RemoveLinkDeps } from "./remove-link";

/** Uids de Firebase Auth (alfanumericos); ademas el id de vinculo es `{t}_{s}`, asi que `_` y `/` no caben. */
const UID = /^[A-Za-z0-9]{1,128}$/;

const uidOf = (input: Record<string, unknown>, key: string): string => {
  const v = input[key];
  if (typeof v !== "string" || !UID.test(v)) throw fail("invalid-argument", ErrorReason.INVALID_ARGUMENT, { field: key });
  return v;
};

/**
 * REQ-LNK-08. Solo las partes del vinculo. No es un oraculo: cualquier otro caller recibe el mismo
 * `permission-denied` exista o no el vinculo; para las partes la llamada es idempotente (vinculo ausente = limpieza).
 */
export async function unlinkTeacherHandler(
  deps: RemoveLinkDeps,
  uid: string,
  data: unknown,
): Promise<{ unlinked: true }> {
  const input = requireObject(data);
  const teacherUid = uidOf(input, "teacherUid");
  const studentUid = uidOf(input, "studentUid");
  if (uid !== teacherUid && uid !== studentUid) {
    (deps.log ?? (() => undefined))("unlinkTeacher.denied", { uidHash: sha256Hex(uid).slice(0, 12) });
    throw fail("permission-denied", ErrorReason.NOT_LINK_PARTY);
  }
  await removeLink(deps, teacherUid, studentUid);
  return { unlinked: true };
}
