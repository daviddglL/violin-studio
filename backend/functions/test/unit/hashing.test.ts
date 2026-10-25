import { normalizeEmail, hmacEmail, randomToken, safeEqualHex, sha256Hex } from "../../src/common/hashing";

describe("sha256Hex", () => {
  test("vector conocido", () => {
    expect(sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});

const P = "p".repeat(32);

describe("hmacEmail", () => {
  test.each([[""], ["corto"], ["x".repeat(31)]])("pepper de longitud insuficiente %p lanza", (pepper) => {
    expect(() => hmacEmail(pepper, "x@y.z")).toThrow();
  });
  test("normaliza espacios y mayúsculas", () => {
    expect(hmacEmail(P, "  Ana@Example.COM ")).toBe(hmacEmail(P, "ana@example.com"));
  });
  test("depende del pepper", () => {
    expect(hmacEmail("a".repeat(32), "x@y.z")).not.toBe(hmacEmail("b".repeat(32), "x@y.z"));
  });
  test("es hex de 64 caracteres", () => {
    expect(hmacEmail(P, "x@y.z")).toMatch(/^[0-9a-f]{64}$/);
  });
});

describe("randomToken", () => {
  test("43 caracteres base64url y no se repite", () => {
    const a = randomToken();
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(randomToken()).not.toBe(a);
  });
});

describe("safeEqualHex", () => {
  test("iguales -> true, distintos -> false", () => {
    expect(safeEqualHex(sha256Hex("a"), sha256Hex("a"))).toBe(true);
    expect(safeEqualHex(sha256Hex("a"), sha256Hex("b"))).toBe(false);
  });
  test("longitudes distintas o no hex -> false sin lanzar", () => {
    expect(safeEqualHex("ab", "abcd")).toBe(false);
    expect(safeEqualHex("zz", "zz")).toBe(false);
    expect(safeEqualHex("", "")).toBe(false);
  });
});

describe("normalizeEmail (única normalización: HMAC y comparación)", () => {
  test("NFKC + trim + minúsculas; sin alias de proveedor", () => {
    expect(normalizeEmail("  ＡNA@Example.COM ")).toBe("ana@example.com");
    expect(normalizeEmail("a.b+tag@gmail.com")).toBe("a.b+tag@gmail.com");
  });
  test("hmacEmail usa la misma normalización", () => {
    expect(hmacEmail("p".repeat(32), "ＡＮＡ@EXAMPLE.com")).toBe(hmacEmail("p".repeat(32), "ana@example.com"));
  });
});
