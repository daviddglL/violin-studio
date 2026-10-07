import { TeacherRoleError } from "./teacher-role";

export interface TeacherCliActions {
  grant: (uid: string) => Promise<void>;
  revoke: (uid: string) => Promise<void>;
}

type Env = Record<string, string | undefined>;

/**
 * Logica de `grant-teacher`/`revoke-teacher`; devuelve el codigo de salida (0 ok, 1 fallo, 2 uso/seguridad).
 * `makeActions` se invoca solo tras validar argumentos y destino, y su fallo (p. ej. `initializeApp`) no
 * vuelca traza. Nunca imprime el uid ni detalles de error.
 */
export async function runTeacherCli(
  action: "grant" | "revoke",
  argv: string[],
  makeActions: () => TeacherCliActions,
  print: (line: string) => void,
  env: Env = process.env,
): Promise<number> {
  const usage = `Uso: ${action}-teacher <uid> [--yes]`;
  const yes = argv.includes("--yes");
  const positional = argv.filter((a) => a !== "--yes");
  if (positional.length !== 1 || positional[0].startsWith("-")) {
    print(usage);
    return 2;
  }
  const project = env.GCLOUD_PROJECT;
  if (!project) {
    print("Falta GCLOUD_PROJECT (id del proyecto Firebase).");
    return 2;
  }
  const hosts = [env.FIRESTORE_EMULATOR_HOST, env.FIREBASE_AUTH_EMULATOR_HOST].filter(Boolean).length;
  if (hosts === 1) {
    print("Emulador a medias: define FIRESTORE_EMULATOR_HOST y FIREBASE_AUTH_EMULATOR_HOST, o ninguno.");
    return 2;
  }
  const emulator = hosts === 2;
  print(`Proyecto: ${project} (${emulator ? "EMULADOR" : "REAL"})`);
  if (!emulator && !yes && env.CONFIRM_PROJECT !== project) {
    print("Proyecto REAL: confirma con --yes o CONFIRM_PROJECT=<id del proyecto>.");
    return 2;
  }
  let actions: TeacherCliActions;
  try {
    actions = makeActions();
  } catch {
    print(`${action}-teacher: no se pudo inicializar Firebase Admin`);
    return 1;
  }
  try {
    await actions[action](positional[0]);
    print(`${action}-teacher: OK`);
    return 0;
  } catch (e) {
    print(`${action}-teacher: ${e instanceof TeacherRoleError ? e.reason : "ERROR"}`);
    return 1;
  }
}
