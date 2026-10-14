import type { ReconcileAllResult } from "../recordings/recount";

type Env = Record<string, string | undefined>;

/**
 * Logica de `admin:recount-recordings` (B5a.4, A21): recalcula `recordingCount` desde los docs `ready`, de todos los
 * duenos o de uno (`<uid>`). Mismas protecciones que `reevaluate-consents`: exige proyecto, muestra EMULADOR/REAL y un
 * proyecto real pide `--yes` o `CONFIRM_PROJECT=<id>`. Nunca imprime uids ni detalles de error. Devuelve el codigo de
 * salida (0 ok, 1 fallo, 2 uso/seguridad). Ejecutar con la app quieta: un borrado en vuelo puede bajar el contador una vez mas.
 */
export async function runRecountRecordingsCli(
  argv: string[],
  makeRun: () => (uid?: string) => Promise<ReconcileAllResult>,
  print: (line: string) => void,
  env: Env = process.env,
): Promise<number> {
  const yes = argv.includes("--yes");
  const rest = argv.filter((a) => a !== "--yes");
  if (rest.length > 1 || rest.some((a) => a.startsWith("-"))) {
    print("Uso: recount-recordings [<uid>] [--yes]");
    return 2;
  }
  const project = env.GCLOUD_PROJECT;
  if (!project) {
    print("Falta GCLOUD_PROJECT (id del proyecto Firebase).");
    return 2;
  }
  const emulator = Boolean(env.FIRESTORE_EMULATOR_HOST);
  print(`Proyecto: ${project} (${emulator ? "EMULADOR" : "REAL"})`);
  if (!emulator && !yes && env.CONFIRM_PROJECT !== project) {
    print("Proyecto REAL: confirma con --yes o CONFIRM_PROJECT=<id del proyecto>.");
    return 2;
  }
  let run: (uid?: string) => Promise<ReconcileAllResult>;
  try {
    run = makeRun();
  } catch {
    print("recount-recordings: no se pudo inicializar Firebase Admin");
    return 1;
  }
  try {
    const r = await run(rest[0]);
    print(`recount-recordings: scanned=${r.scanned} repaired=${r.repaired} failed=${r.failed}`);
    return r.failed > 0 ? 1 : 0;
  } catch {
    print("recount-recordings: ERROR");
    return 1;
  }
}
