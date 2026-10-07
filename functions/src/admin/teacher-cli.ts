import { TeacherRoleError } from "./teacher-role";

export interface TeacherCliActions {
  grant: (uid: string) => Promise<void>;
  revoke: (uid: string) => Promise<void>;
}

/** Logica de `grant-teacher`/`revoke-teacher`; devuelve el codigo de salida. Nunca imprime el uid ni detalles de error. */
export async function runTeacherCli(
  action: "grant" | "revoke",
  argv: string[],
  actions: TeacherCliActions,
  print: (line: string) => void,
): Promise<number> {
  if (argv.length !== 1) {
    print(`Uso: ${action}-teacher <uid>`);
    return 2;
  }
  try {
    await actions[action](argv[0]);
    print(`${action}-teacher: OK`);
    return 0;
  } catch (e) {
    print(`${action}-teacher: ${e instanceof TeacherRoleError ? e.reason : "ERROR"}`);
    return 1;
  }
}
