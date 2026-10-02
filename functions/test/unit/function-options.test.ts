process.env.GCLOUD_PROJECT ??= "demo-violin-studio"; // lo exige el endpoint v1 de auth
const entrypoint = require("../../src/index"); // eslint-disable-line @typescript-eslint/no-require-imports
import { REGION } from "../../src/config/runtime";

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
