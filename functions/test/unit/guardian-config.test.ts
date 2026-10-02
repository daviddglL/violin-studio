import { DEFAULT_GUARDIAN_LINK_BASE_URL, guardianLinkBaseUrl } from "../../src/guardian/config";

describe("guardianLinkBaseUrl", () => {
  test("emulador o test sin variable: valor dev por defecto", () => {
    expect(guardianLinkBaseUrl({ FUNCTIONS_EMULATOR: "true" })).toBe(DEFAULT_GUARDIAN_LINK_BASE_URL);
    expect(guardianLinkBaseUrl({ NODE_ENV: "test" })).toBe(DEFAULT_GUARDIAN_LINK_BASE_URL);
  });
  test("producción sin variable: error de configuración claro (nunca enlaces dev)", () => {
    expect(() => guardianLinkBaseUrl({})).toThrow(/GUARDIAN_LINK_BASE_URL/);
    expect(() => guardianLinkBaseUrl({ GUARDIAN_LINK_BASE_URL: "" })).toThrow(/GUARDIAN_LINK_BASE_URL/);
  });
  test("exige https y quita la barra final", () => {
    expect(guardianLinkBaseUrl({ GUARDIAN_LINK_BASE_URL: "https://app.example.com//" })).toBe("https://app.example.com");
    expect(() => guardianLinkBaseUrl({ GUARDIAN_LINK_BASE_URL: "http://app.example.com" })).toThrow(/https/);
    expect(() => guardianLinkBaseUrl({ GUARDIAN_LINK_BASE_URL: "app.example.com" })).toThrow(/https/);
  });
  test("http solo a localhost/127.0.0.1 y solo en emulador", () => {
    const emu = { FUNCTIONS_EMULATOR: "true" };
    expect(guardianLinkBaseUrl({ ...emu, GUARDIAN_LINK_BASE_URL: "http://localhost:5000/" })).toBe("http://localhost:5000");
    expect(guardianLinkBaseUrl({ ...emu, GUARDIAN_LINK_BASE_URL: "http://127.0.0.1:5000" })).toBe("http://127.0.0.1:5000");
    expect(() => guardianLinkBaseUrl({ ...emu, GUARDIAN_LINK_BASE_URL: "http://evil.com" })).toThrow(/https/);
    expect(() => guardianLinkBaseUrl({ GUARDIAN_LINK_BASE_URL: "http://localhost:5000" })).toThrow(/https/);
  });
});
