process.env.GCLOUD_PROJECT ??= "demo-violin-studio";
const entrypoint = require("../../src/index"); // eslint-disable-line @typescript-eslint/no-require-imports
import { REGION } from "../../src/config/runtime";

test("purgeIdentity: diaria 03:00 Europe/Madrid, 2 reintentos, region y timeout generoso", () => {
  const e = (entrypoint.purgeIdentity as { __endpoint: any }).__endpoint; // eslint-disable-line @typescript-eslint/no-explicit-any
  expect(e.region).toEqual([REGION]);
  expect(e.scheduleTrigger).toMatchObject({ schedule: "every day 03:00", timeZone: "Europe/Madrid", retryConfig: { retryCount: 2 } });
  expect(e.timeoutSeconds).toBe(540);
});
