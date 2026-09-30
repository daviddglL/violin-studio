import { callableOptions, httpOptions, REGION } from "../../src/config/runtime";
import { shouldEnforceAppCheck } from "../../src/appcheck";

describe("config/runtime", () => {
  test("la región es europe-west1", () => {
    expect(REGION).toBe("europe-west1");
  });
  test("callableOptions usa la región, App Check según entorno y maxInstances 10", () => {
    const opts = callableOptions();
    expect(opts.region).toBe(REGION);
    expect(opts.enforceAppCheck).toBe(shouldEnforceAppCheck(process.env));
    expect(opts.maxInstances).toBe(10);
  });
  test("callableOptions respeta el entorno inyectado", () => {
    expect(callableOptions({ FUNCTIONS_EMULATOR: "true" }).enforceAppCheck).toBe(false);
    expect(callableOptions({}).enforceAppCheck).toBe(true);
  });
  test("httpOptions fija región y un techo de instancias menor (sin App Check)", () => {
    expect(httpOptions).toEqual({ region: REGION, maxInstances: 5 });
  });
});
