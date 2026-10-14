import type { ReevaluateAllResult } from "../consent/reevaluate-all";

type Env = Record<string, string | undefined>;

/**
 * Lógica de `admin:reevaluate-consents` (A11, A4 S1); devuelve el código de salida (0 ok, 1 fallo, 2 uso/seguridad).
 * Mismas protecciones que `grant-teacher`: exige proyecto, muestra EMULADOR/REAL y un proyecto real pide
 * `--yes` o `CONFIRM_PROJECT=<id>`. Nunca imprime uids ni detalles de error.
 */
export async function runReevaluateAllCli(
  argv: string[],
  makeRun: () => () => Promise<ReevaluateAllResult>,
  print: (line: string) => void,
  env: Env = process.env,
): Promise<number> {
  const yes = argv.includes("--yes");
  if (argv.some((a) => a !== "--yes")) {
    print("Uso: reevaluate-consents [--yes]");
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
  let run: () => Promise<ReevaluateAllResult>;
  try {
    run = makeRun();
  } catch {
    print("reevaluate-consents: no se pudo inicializar Firebase Admin");
    return 1;
  }
  try {
    const r = await run();
    print(`reevaluate-consents: scanned=${r.scanned} changed=${r.changed} failed=${r.failed}`);
    return r.failed > 0 ? 1 : 0;
  } catch {
    print("reevaluate-consents: ERROR");
    return 1;
  }
}
