import { generateToken, hashToken, verifyToken } from "../../src/guardian/token";

describe("guardian token", () => {
  test("generateToken: 32 bytes en base64url (43 chars) y distinto cada vez", () => {
    const a = generateToken();
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(generateToken()).not.toBe(a);
  });
  test("hashToken: sha256 hex determinista", () => {
    expect(hashToken("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
  test("verifyToken: acepta el correcto; rechaza otro, hash de otra solicitud, longitud mala y vacío", () => {
    const t = generateToken();
    expect(verifyToken(t, hashToken(t))).toBe(true);
    expect(verifyToken(generateToken(), hashToken(t))).toBe(false);
    expect(verifyToken(t, hashToken(generateToken()))).toBe(false);
    expect(verifyToken(t, "abcd")).toBe(false);
    expect(verifyToken("", "")).toBe(false);
  });
});
