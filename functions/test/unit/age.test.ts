import { DIGITAL_CONSENT_AGE, ADULT_AGE } from "../../src/config/identity";
import { ageOn, isAdult, isMinor, parseBirthDate } from "../../src/identity/age";

const hoy = new Date("2026-06-15T10:00:00Z");

describe("parseBirthDate", () => {
  test("acepta fechas válidas", () => {
    expect(parseBirthDate("2015-02-28", hoy)).toEqual({ year: 2015, month: 2, day: 28 });
    expect(parseBirthDate("2012-02-29", hoy)).toEqual({ year: 2012, month: 2, day: 29 });
    expect(parseBirthDate("2026-06-15", hoy)).toEqual({ year: 2026, month: 6, day: 15 });
  });
  test.each([["2015-02-30"], ["2015-13-01"], ["2015-00-10"], ["2015-04-31"], ["2013-02-29"], ["abcd-ef-gh"], ["2015-2-3"], [""]])(
    "rechaza la inexistente o malformada %p",
    (texto) => {
      expect(() => parseBirthDate(texto, hoy)).toThrow(expect.objectContaining({ code: "invalid-argument" }));
    },
  );
  test("rechaza fecha futura", () => {
    expect(() => parseBirthDate("2026-06-16", hoy)).toThrow(expect.objectContaining({ code: "invalid-argument" }));
  });
  test("rechaza más de MAX_PLAUSIBLE_AGE años", () => {
    expect(() => parseBirthDate("1900-01-01", hoy)).toThrow(expect.objectContaining({ code: "invalid-argument" }));
    expect(() => parseBirthDate("1906-06-14", hoy)).not.toThrow(); // 120 años cumplidos: límite admitido
    expect(() => parseBirthDate("1905-06-15", hoy)).toThrow(); // 121 años
  });
  test("el error lleva la reason INVALID_BIRTH_DATE", () => {
    expect(() => parseBirthDate("2015-02-30", hoy)).toThrow(
      expect.objectContaining({ details: expect.objectContaining({ reason: "INVALID_BIRTH_DATE" }) }),
    );
  });
});

describe("ageOn / isMinor bordes", () => {
  const nacido = { year: 2012, month: 6, day: 15 };
  test("cumple 14 hoy -> ya no es menor", () => {
    const dia = new Date("2026-06-15T00:00:00Z");
    expect(ageOn(nacido, dia)).toBe(14);
    expect(isMinor(nacido, dia)).toBe(false);
  });
  test("cumple 14 mañana -> sigue siendo menor", () => {
    const dia = new Date("2026-06-14T23:59:59Z");
    expect(ageOn(nacido, dia)).toBe(13);
    expect(isMinor(nacido, dia)).toBe(true);
  });
  test("29-feb-2012: cumple el 1 de marzo en años no bisiestos", () => {
    const bisiesto = { year: 2012, month: 2, day: 29 };
    expect(isMinor(bisiesto, new Date("2026-02-28T12:00:00Z"))).toBe(true);
    expect(isMinor(bisiesto, new Date("2026-03-01T12:00:00Z"))).toBe(false);
  });
});

describe("ageOn 29-feb en año bisiesto", () => {
  const bisiesto = { year: 2012, month: 2, day: 29 };
  test("cumple el propio 29-feb", () => {
    expect(ageOn(bisiesto, new Date("2028-02-28T12:00:00Z"))).toBe(15);
    expect(ageOn(bisiesto, new Date("2028-02-29T12:00:00Z"))).toBe(16);
  });
});

describe("isMinor umbral parametrizado y fail-closed", () => {
  const nacido = { year: 2010, month: 6, day: 15 };
  test("respeta el umbral recibido", () => {
    expect(isMinor(nacido, hoy, 16)).toBe(false); // cumple 16 hoy
    expect(isMinor(nacido, new Date("2026-06-14T12:00:00Z"), 16)).toBe(true); // le falta un día
    expect(isMinor(nacido, hoy, 18)).toBe(true);
  });
  test.each([[null], [undefined], [{}], [{ year: 2010, month: 2, day: 30 }], [{ year: "2010", month: 1, day: 1 }], ["2010-01-01"]])(
    "entrada inválida %p se trata como menor, nunca como adulto",
    (entrada) => {
      expect(isMinor(entrada, hoy)).toBe(true);
    },
  );
  test("fecha futura se trata como menor", () => {
    expect(isMinor({ year: 2030, month: 1, day: 1 }, hoy)).toBe(true);
  });
  test("hoy inválido se trata como menor", () => {
    expect(isMinor(nacido, new Date("nope"))).toBe(true);
  });
});

describe("ADULT_AGE e isAdult (REQ-PRF-T01)", () => {
  const nacido18 = { year: 2008, month: 6, day: 15 };
  test("ADULT_AGE vale 18 y es independiente del umbral de consentimiento", () => {
    expect(ADULT_AGE).toBe(18);
    expect(ADULT_AGE).toBeGreaterThan(DIGITAL_CONSENT_AGE);
  });
  test("exactamente 18 años hoy -> adulto; un día antes -> no", () => {
    expect(isAdult(nacido18, new Date("2026-06-15T12:00:00Z"))).toBe(true);
    expect(isAdult(nacido18, new Date("2026-06-14T12:00:00Z"))).toBe(false);
  });
  test("17 años y 364 días no es adulto", () => {
    expect(isAdult({ year: 2008, month: 6, day: 16 }, new Date("2026-06-15T12:00:00Z"))).toBe(false);
  });
  test("29-feb: cumple 18 el 1 de marzo en año no bisiesto", () => {
    const bisiesto = { year: 2008, month: 2, day: 29 };
    expect(isAdult(bisiesto, new Date("2026-02-28T12:00:00Z"))).toBe(false);
    expect(isAdult(bisiesto, new Date("2026-03-01T12:00:00Z"))).toBe(true);
  });
  test("dos umbrales independientes: 16 años es no menor y no adulto", () => {
    const de16 = { year: 2010, month: 1, day: 1 };
    const dia = new Date("2026-06-15T12:00:00Z");
    expect(isMinor(de16, dia)).toBe(false);
    expect(isAdult(de16, dia)).toBe(false);
  });
  test.each([[null], [undefined], [{}], [{ year: 2010, month: 2, day: 30 }], ["1990-01-01"]])(
    "fail-closed: entrada inválida %p nunca es adulto",
    (entrada) => {
      expect(isAdult(entrada, new Date("2026-06-15T12:00:00Z"))).toBe(false);
    },
  );
  test("fecha futura u hoy inválido nunca es adulto", () => {
    expect(isAdult({ year: 2040, month: 1, day: 1 }, new Date("2026-06-15T12:00:00Z"))).toBe(false);
    expect(isAdult(nacido18, new Date("nope"))).toBe(false);
  });
  test("umbral inyectable", () => {
    expect(isAdult(nacido18, new Date("2026-06-15T12:00:00Z"), 21)).toBe(false);
  });
});
