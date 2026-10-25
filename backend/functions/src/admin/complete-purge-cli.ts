import { CompletePurgeError, CompletePurgeResult } from "../recordings/complete-purge";

type Env = Record<string, string | undefined>;
type Run = (uid: string, opts: { force: boolean }) => Promise<CompletePurgeResult>;

/**
 * Logica de `admin:complete-recording-purge <uid> [--force] [--yes]` (W4 de la revision fresca B5b): cierra a mano una purga
 * de revocacion atascada tras la limpieza manual (runbook en README). Mismas protecciones que `recount-recordings`: exige
 * proyecto, muestra EMULADOR/REAL y un proyecto real pide `--yes` o `CONFIRM_PROJECT=<id>`. Nunca imprime el uid ni detalles
 * de error. Devuelve el codigo de salida (0 ok, 1 fallo o grabaciones pendientes sin `--force`, 2 uso/seguridad).
 */
export async function runCompletePurgeCli(argv: string[], makeRun: () => Run, print: (line: string) => void, env: Env = process.env): Promise<number> {
  const yes = argv.includes("--yes");
  const force = argv.includes("--force");
  const rest = argv.filter((a) => a !== "--yes" && a !== "--force");
  if (rest.length !== 1 || rest[0].startsWith("-")) {
    print("Uso: complete-recording-purge <uid> [--force] [--yes]");
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
  let run: Run;
  try {
    run = makeRun();
  } catch {
    print("complete-recording-purge: no se pudo inicializar Firebase Admin");
    return 1;
  }
  try {
    const r = await run(rest[0], { force });
    print(`complete-recording-purge: ${r.outcome} remaining=${r.remaining}`);
    return 0;
  } catch (e) {
    if (e instanceof CompletePurgeError) {
      print(`complete-recording-purge: ${e.reason} remaining=${e.remaining}`);
      return 1;
    }
    print("complete-recording-purge: ERROR");
    return 1;
  }
}
