import { recordingFlags } from "../../src/config/features";
import { RECORDING_CONSENT_VERSION } from "../../src/config/identity";

describe("recordingFlags (A19)", () => {
  test("producción sin variables: todo apagado (fail-closed)", () => {
    expect(recordingFlags({})).toEqual({ recordingsEnabled: false, videoEnabled: false, minorRecordingEnabled: false });
  });
  test("emulador o test sin variables: encendido para poder desarrollar y probar", () => {
    const todo = { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true };
    expect(recordingFlags({ FUNCTIONS_EMULATOR: "true" })).toEqual(todo);
    expect(recordingFlags({ NODE_ENV: "test" })).toEqual(todo);
  });
  test("la variable explícita manda sobre el valor por defecto, en ambos sentidos", () => {
    const env = { RECORDINGS_ENABLED: "true", VIDEO_ENABLED: "false", MINOR_RECORDING_ENABLED: "true" };
    expect(recordingFlags(env)).toEqual({ recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: true });
    expect(recordingFlags({ NODE_ENV: "test", RECORDINGS_ENABLED: "false" }).recordingsEnabled).toBe(false);
  });
  test("tolera mayúsculas y espacios; cualquier otro valor cuenta como apagado", () => {
    expect(recordingFlags({ RECORDINGS_ENABLED: " TRUE " }).recordingsEnabled).toBe(true);
    for (const v of ["1", "yes", "", "verdadero", "tru"]) {
      expect(recordingFlags({ NODE_ENV: "test", RECORDINGS_ENABLED: v }).recordingsEnabled).toBe(false);
    }
  });
});

test("RECORDING_CONSENT_VERSION es un entero >= 1 (versionado propio, aparte de la política)", () => {
  expect(Number.isInteger(RECORDING_CONSENT_VERSION)).toBe(true);
  expect(RECORDING_CONSENT_VERSION).toBeGreaterThanOrEqual(1);
});
