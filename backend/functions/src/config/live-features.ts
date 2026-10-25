import type { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import { chatEnabled, notificationsEnabled, RecordingFlags, recordingFlags } from "./features";

/** Doc `appConfig/features`: interruptores en caliente, editables desde la consola (solo Admin SDK lo lee). */
export const FEATURES_DOC_ID = "features";
/** Cada instancia relee el doc como mucho una vez por minuto: apagar tarda hasta un minuto en llegar a todas. */
export const FEATURES_CACHE_MS = 60_000;

export interface LiveFeatures {
  flags: RecordingFlags;
  chatEnabled: boolean;
  notificationsEnabled: boolean;
}

type Key = keyof RecordingFlags | "chatEnabled" | "notificationsEnabled";

/**
 * El entorno (`functions/.env.<project>`) es el techo y el doc solo puede apagar: un campo ausente o `true` deja el valor
 * del entorno; cualquier otro valor presente (`false`, un texto, un numero) lo apaga (fail-closed). Sin doc, manda el
 * entorno, como antes de existir el doc. Asi un error en la consola nunca enciende algo que no se desplego encendido.
 */
export function applyRemote(base: LiveFeatures, remote: Record<string, unknown> | undefined): LiveFeatures {
  const on = (key: Key, value: boolean) => value && (remote?.[key] === undefined || remote[key] === true);
  return {
    flags: {
      recordingsEnabled: on("recordingsEnabled", base.flags.recordingsEnabled),
      videoEnabled: on("videoEnabled", base.flags.videoEnabled),
      minorRecordingEnabled: on("minorRecordingEnabled", base.flags.minorRecordingEnabled),
    },
    chatEnabled: on("chatEnabled", base.chatEnabled),
    notificationsEnabled: on("notificationsEnabled", base.notificationsEnabled),
  };
}

export interface LiveFeaturesDeps {
  db: () => Firestore;
  env?: NodeJS.ProcessEnv;
  /** Milisegundos actuales; inyectable en tests. */
  now?: () => number;
}

/**
 * Lector con cache por instancia. Un fallo al leer el doc se propaga sin cachear (el callable responde error y el trigger
 * reintenta): nunca se decide con un valor inventado.
 */
export function liveFeaturesReader(deps: LiveFeaturesDeps): () => Promise<LiveFeatures> {
  const now = deps.now ?? Date.now;
  let cached: { value: LiveFeatures; at: number } | undefined;
  return async () => {
    const t = now();
    if (cached && t - cached.at < FEATURES_CACHE_MS) return cached.value;
    const env = deps.env ?? process.env;
    const snap = await deps.db().collection(COLLECTIONS.appConfig).doc(FEATURES_DOC_ID).get();
    const value = applyRemote(
      { flags: recordingFlags(env), chatEnabled: chatEnabled(env), notificationsEnabled: notificationsEnabled(env) },
      snap.data(),
    );
    cached = { value, at: t };
    return value;
  };
}
