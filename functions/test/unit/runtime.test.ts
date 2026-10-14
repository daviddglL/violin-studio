import { callableOptions, httpOptions, REGION, storageTriggerRegion } from "../../src/config/runtime";
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

  describe("storageTriggerRegion (spike Q5)", () => {
    test("por defecto es REGION; STORAGE_TRIGGER_REGION la sustituye (trim) para alinearla con el bucket real", () => {
      expect(storageTriggerRegion({})).toBe(REGION);
      expect(storageTriggerRegion({ STORAGE_TRIGGER_REGION: " europe-west3 " })).toBe("europe-west3");
    });
    test("valor vacio o con forma invalida cae a REGION (no se despliega con una region rota)", () => {
      expect(storageTriggerRegion({ STORAGE_TRIGGER_REGION: "  " })).toBe(REGION);
      expect(storageTriggerRegion({ STORAGE_TRIGGER_REGION: "Europe West!" })).toBe(REGION);
    });
  });
});
