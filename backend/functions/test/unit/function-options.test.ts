process.env.GCLOUD_PROJECT ??= "demo-violin-studio"; // lo exige el endpoint v1 de auth
const entrypoint = require("../../src/index"); // eslint-disable-line @typescript-eslint/no-require-imports
import { REGION, storageTriggerRegion } from "../../src/config/runtime";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const endpoint = (fn: unknown): any => (fn as { __endpoint: unknown }).__endpoint;

test("deleteAccount: región y timeoutSeconds 300", () => {
  const e = endpoint(entrypoint.deleteAccount);
  expect(e.region).toEqual([REGION]);
  expect(e.timeoutSeconds).toBe(300);
});

test("onUserDeleted: región y failurePolicy activa", () => {
  const e = endpoint(entrypoint.onUserDeleted);
  expect(e.region).toEqual([REGION]);
  expect(e.eventTrigger.retry).toBe(true);
});

test("guardianConsent: HTTPS en la región, maxInstances 5 y sin secretos (el handler no necesita el pepper)", () => {
  const e = endpoint(entrypoint.guardianConsent);
  expect(e.region).toEqual([REGION]);
  expect(e.maxInstances).toBe(5);
  expect(e.timeoutSeconds).toBe(300); // la cascada de rechazo corre en línea: un 504 de plataforma no lleva nuestras cabeceras
  expect(e.httpsTrigger).toBeDefined();
  expect(e.secretEnvironmentVariables ?? []).toEqual([]);
});

test("finalizeRecording: trigger de Storage finalized en la region del bucket, con reintentos y techo de instancias", () => {
  const e = endpoint(entrypoint.finalizeRecording);
  expect(e.region).toEqual([storageTriggerRegion()]);
  expect(e.eventTrigger.eventType).toBe("google.cloud.storage.object.v1.finalized");
  expect(e.eventTrigger.retry).toBe(true);
  expect(e.maxInstances).toBe(10);
  expect(e.timeoutSeconds).toBe(120);
});
