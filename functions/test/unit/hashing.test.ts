import { hmacEmail, randomToken, safeEqualHex, sha256Hex } from "../../src/common/hashing";

describe("sha256Hex", () => {
  test("vector conocido", () => {
    expect(sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});

describe("hmacEmail", () => {
  test("normaliza espacios y mayúsculas", () => {
    expect(hmacEmail("pepper", "  Ana@Example.COM ")).toBe(hmacEmail("pepper", "ana@example.com"));
  });
  test("depende del pepper", () => {
    expect(hmacEmail("a", "x@y.z")).not.toBe(hmacEmail("b", "x@y.z"));
  });
  test("es hex de 64 caracteres", () => {
    expect(hmacEmail("p", "x@y.z")).toMatch(/^[0-9a-f]{64}$/);
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
