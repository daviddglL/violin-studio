import { HttpsError } from "firebase-functions/v2/https";
import { requireEnum, requireIsoDate, requireObject, requireString } from "../../src/common/validation";

function fallo(fn: () => unknown): HttpsError {
  try {
    fn();
  } catch (e) {
    return e as HttpsError;
  }
  throw new Error("no lanzó");
}

describe("requireObject", () => {
  test("acepta un objeto plano", () => {
    expect(requireObject({ a: 1 })).toEqual({ a: 1 });
  });
  test.each([null, undefined, 3, "x", [1], true])("rechaza %p", (valor) => {
    const err = fallo(() => requireObject(valor));
    expect(err.code).toBe("invalid-argument");
    expect(err.details).toMatchObject({ reason: "INVALID_ARGUMENT" });
  });
});

describe("requireString", () => {
  const opts = { min: 1, max: 5 };
  test("devuelve el texto válido", () => {
    expect(requireString({ n: "ana" }, "n", opts)).toBe("ana");
  });
  test("rechaza tipo erróneo indicando el campo", () => {
    const err = fallo(() => requireString({ n: 3 }, "n", opts));
    expect(err.code).toBe("invalid-argument");
    expect(err.details).toMatchObject({ field: "n" });
  });
  test("rechaza ausente, vacío y demasiado largo", () => {
    expect(fallo(() => requireString({}, "n", opts)).code).toBe("invalid-argument");
    expect(fallo(() => requireString({ n: "" }, "n", opts)).code).toBe("invalid-argument");
    expect(fallo(() => requireString({ n: "abcdef" }, "n", opts)).code).toBe("invalid-argument");
  });
  test("aplica el patrón", () => {
    const o = { min: 1, max: 10, pattern: /^[a-z]{2}$/ };
    expect(requireString({ l: "es" }, "l", o)).toBe("es");
    expect(fallo(() => requireString({ l: "ES" }, "l", o)).code).toBe("invalid-argument");
  });
});

describe("requireEnum", () => {
  const valores = ["violin", "viola"] as const;
  test("acepta un valor permitido", () => {
    expect(requireEnum({ i: "viola" }, "i", valores)).toBe("viola");
  });
  test.each([["piano"], [null], [1], [{}]])("rechaza %p", (valor) => {
    expect(fallo(() => requireEnum({ i: valor }, "i", valores)).code).toBe("invalid-argument");
  });
});

describe("requireIsoDate", () => {
  test("acepta YYYY-MM-DD", () => {
    expect(requireIsoDate({ d: "2010-05-20" }, "d")).toBe("2010-05-20");
  });
  test.each([["2010-5-20"], ["20/05/2010"], ["2010-05-20T00:00:00Z"], [null], [20100520], [{}], [undefined]])(
    "rechaza %p (fail-closed)",
    (valor) => {
      const err = fallo(() => requireIsoDate({ d: valor }, "d"));
      expect(err.code).toBe("invalid-argument");
      expect(err.details).toMatchObject({ reason: "INVALID_BIRTH_DATE", field: "d" });
    },
  );
});
