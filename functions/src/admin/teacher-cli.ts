import { PendingTeacherRequest, TeacherRoleError } from "./teacher-role";

export interface TeacherCliActions {
  grant: (uid: string) => Promise<void>;
  revoke: (uid: string) => Promise<void>;
  /** Rechaza la solicitud pendiente hecha desde la app. */
  reject: (uid: string) => Promise<void>;
}

export type TeacherCliAction = keyof TeacherCliActions;

type Env = Record<string, string | undefined>;

/**
 * W3 (revision fresca B5b): bucket de Storage EXPLICITO para la CLI (`STORAGE_BUCKET`, o `storageBucket` de `FIREBASE_CONFIG`).
 * Nunca se adivina `<proyecto>.appspot.com`: en un proyecto con bucket `*.firebasestorage.app` la desvinculacion daria por
 * borradas demos que siguen en el bucket real. `undefined` = no configurado.
 */
export function adminStorageBucket(env: Env): string | undefined {
  if (env.STORAGE_BUCKET) return env.STORAGE_BUCKET;
  try {
    const b = (JSON.parse(env.FIREBASE_CONFIG ?? "") as { storageBucket?: unknown }).storageBucket;
    return typeof b === "string" && b !== "" ? b : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Comprobaciones comunes de destino: proyecto definido, emuladores completos o ninguno, y confirmacion para un
 * proyecto REAL. Devuelve el codigo de salida de uso (2) o `null` si se puede seguir.
 */
function checkTarget(yes: boolean, print: (line: string) => void, env: Env): number | null {
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
  return null;
}

/**
 * Logica de `grant-teacher`/`revoke-teacher`/`reject-teacher`; devuelve el codigo de salida (0 ok, 1 fallo, 2 uso/seguridad).
 * `makeActions` se invoca solo tras validar argumentos, destino y (para `revoke`) el bucket, y su fallo (p. ej. `initializeApp`) no
 * vuelca traza. Nunca imprime el uid ni detalles de error.
 */
export async function runTeacherCli(
  action: TeacherCliAction,
  argv: string[],
  makeActions: (opts: { bucket?: string }) => TeacherCliActions,
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
  const blocked = checkTarget(yes, print, env);
  if (blocked !== null) return blocked;
  // `revoke` desvincula y BORRA las demos del profesor (objeto + doc): sin el bucket correcto no se actua.
  const bucket = adminStorageBucket(env);
  if (action === "revoke") {
    if (!bucket) {
      print("Falta STORAGE_BUCKET (o storageBucket en FIREBASE_CONFIG): revoke-teacher borra objetos de Storage.");
      return 2;
    }
    print(`Bucket: ${bucket}`);
  }
  let actions: TeacherCliActions;
  try {
    actions = makeActions(action === "revoke" ? { bucket } : {});
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

/**
 * Logica de `teacher-requests`: lista las solicitudes pendientes (uid y fecha) para aprobarlas con `grant-teacher` o
 * rechazarlas con `reject-teacher`. Es el unico comando que imprime uids: los necesita el administrador para decidir.
 */
export async function runTeacherRequestsCli(
  argv: string[],
  makeList: () => () => Promise<PendingTeacherRequest[]>,
  print: (line: string) => void,
  env: Env = process.env,
): Promise<number> {
  const yes = argv.includes("--yes");
  if (argv.some((a) => a !== "--yes")) {
    print("Uso: teacher-requests [--yes]");
    return 2;
  }
  const blocked = checkTarget(yes, print, env);
  if (blocked !== null) return blocked;
  let list: () => Promise<PendingTeacherRequest[]>;
  try {
    list = makeList();
  } catch {
    print("teacher-requests: no se pudo inicializar Firebase Admin");
    return 1;
  }
  try {
    const pending = await list();
    if (pending.length === 0) print("Sin solicitudes pendientes.");
    for (const r of pending) print(`${r.uid}  ${r.requestedAt ?? "(sin fecha)"}`);
    return 0;
  } catch {
    print("teacher-requests: ERROR");
    return 1;
  }
}
