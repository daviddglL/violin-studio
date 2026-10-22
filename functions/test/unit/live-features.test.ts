import type { Firestore } from "firebase-admin/firestore";
import { applyRemote, FEATURES_CACHE_MS, LiveFeatures, liveFeaturesReader } from "../../src/config/live-features";

const ALL_ON: LiveFeatures = {
  flags: { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true },
  chatEnabled: true,
};
const ALL_OFF: LiveFeatures = {
  flags: { recordingsEnabled: false, videoEnabled: false, minorRecordingEnabled: false },
  chatEnabled: false,
};
const ENV_ON = {
  RECORDINGS_ENABLED: "true",
  VIDEO_ENABLED: "true",
  MINOR_RECORDING_ENABLED: "true",
  CHAT_ENABLED: "true",
};

/** Firestore minimo: `collection(c).doc(d).get()` devuelve lo que diga `read`, y cuenta las lecturas. */
function fakeDb(read: (path: string) => Promise<Record<string, unknown> | undefined>) {
  const paths: string[] = [];
  const db = {
    collection: (c: string) => ({
      doc: (d: string) => ({
        get: async () => {
          paths.push(`${c}/${d}`);
          const data = await read(`${c}/${d}`);
          return { data: () => data };
        },
      }),
    }),
  } as unknown as Firestore;
  return { db, paths };
}

describe("applyRemote", () => {
  test("sin doc manda el entorno", () => {
    expect(applyRemote(ALL_ON, undefined)).toEqual(ALL_ON);
    expect(applyRemote(ALL_OFF, undefined)).toEqual(ALL_OFF);
  });

  test("el doc apaga con false y deja el resto como en el entorno", () => {
    expect(applyRemote(ALL_ON, { videoEnabled: false, chatEnabled: false })).toEqual({
      flags: { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: true },
      chatEnabled: false,
    });
  });

  test("el doc nunca enciende lo que el entorno tiene apagado", () => {
    const remote = { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true, chatEnabled: true };
    expect(applyRemote(ALL_OFF, remote)).toEqual(ALL_OFF);
  });

  test("un valor que no es booleano apaga (fail-closed)", () => {
    for (const v of ["true", "false", 1, 0, null, {}]) {
      expect(applyRemote(ALL_ON, { recordingsEnabled: v }).flags.recordingsEnabled).toBe(false);
    }
  });

  test("ignora campos desconocidos", () => {
    expect(applyRemote(ALL_ON, { otraCosa: false })).toEqual(ALL_ON);
  });
});

describe("liveFeaturesReader", () => {
  test("lee appConfig/features y combina con el entorno", async () => {
    const { db, paths } = fakeDb(async () => ({ chatEnabled: false }));
    const read = liveFeaturesReader({ db: () => db, env: ENV_ON, now: () => 0 });
    expect(await read()).toEqual({ ...ALL_ON, chatEnabled: false });
    expect(paths).toEqual(["appConfig/features"]);
  });

  test("cachea durante un minuto y luego relee", async () => {
    let remote: Record<string, unknown> | undefined = undefined;
    let t = 0;
    const { db, paths } = fakeDb(async () => remote);
    const read = liveFeaturesReader({ db: () => db, env: ENV_ON, now: () => t });
    expect(await read()).toEqual(ALL_ON);
    remote = { recordingsEnabled: false };
    t = FEATURES_CACHE_MS - 1;
    expect((await read()).flags.recordingsEnabled).toBe(true);
    t = FEATURES_CACHE_MS;
    expect((await read()).flags.recordingsEnabled).toBe(false);
    expect(paths).toHaveLength(2);
  });

  test("un fallo de lectura se propaga y no se cachea", async () => {
    let fail = true;
    const { db, paths } = fakeDb(async () => {
      if (fail) throw new Error("unavailable");
      return undefined;
    });
    const read = liveFeaturesReader({ db: () => db, env: ENV_ON, now: () => 0 });
    await expect(read()).rejects.toThrow("unavailable");
    fail = false;
    expect(await read()).toEqual(ALL_ON);
    expect(paths).toHaveLength(2);
  });

  test("sin variables en produccion todo sigue apagado aunque el doc diga true", async () => {
    const { db } = fakeDb(async () => ({ recordingsEnabled: true, chatEnabled: true }));
    expect(await liveFeaturesReader({ db: () => db, env: {}, now: () => 0 })()).toEqual(ALL_OFF);
  });
});
