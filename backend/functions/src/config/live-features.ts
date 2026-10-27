import type { Firestore } from "firebase-admin/firestore";
import { COLLECTIONS } from "../common/collections";
import {
  chatEnabled,
  minVersionCode,
  notificationsEnabled,
  parseMinVersion,
  RecordingFlags,
  recordingFlags,
} from "./features";

/** Doc `appConfig/features`: interruptores en caliente, editables desde la consola (solo Admin SDK lo lee). */
export const FEATURES_DOC_ID = "features";
/** Cada instancia relee el doc como mucho una vez por minuto: apagar tarda hasta un minuto en llegar a todas. */
export const FEATURES_CACHE_MS = 60_000;

export interface LiveFeatures {
  flags: RecordingFlags;
  chatEnabled: boolean;
  notificationsEnabled: boolean;
  /** `versionCode` minimo de la app; 0 = sin minimo. */
  minVersionCode: number;
}

type Key = keyof RecordingFlags | "chatEnabled" | "notificationsEnabled";

/**
 * El entorno (`functions/.env.<project>`) es el techo y el doc solo puede apagar: un campo ausente o `true` deja el valor
 * del entorno; cualquier otro valor presente (`false`, un texto, un numero) lo apaga (fail-closed). Sin doc, manda el
 * entorno, como antes de existir el doc. Asi un error en la consola nunca enciende algo que no se desplego encendido.
 * `minVersionCode` es la excepcion: el doc solo puede subirlo por encima del entorno (para forzar una actualizacion
 * sin desplegar), y un valor que no sea un entero >= 0 se ignora: nunca bloquea a todos por un error de escritura.
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
    minVersionCode: Math.max(base.minVersionCode, parseMinVersion(remote?.minVersionCode) ?? 0),
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
      {
        flags: recordingFlags(env),
        chatEnabled: chatEnabled(env),
        notificationsEnabled: notificationsEnabled(env),
        minVersionCode: minVersionCode(env),
      },
      snap.data(),
    );
    cached = { value, at: t };
    return value;
  };
}
