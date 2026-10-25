import { requestGuardianConsentHandler } from "../../src/guardian/request";
import { requirePepper } from "../../src/guardian/pepper";

const PEPPER = "p".repeat(32);
// Un db que falla si se toca: las validaciones previas no deben acceder al backend.
const db = new Proxy({}, { get: () => { throw new Error("db tocado"); } }) as never;
const deps = (over = {}) => ({ db, pepper: PEPPER, linkBaseUrl: "https://x.app", guardianFlowEnabled: true, ...over });
const run = (data: unknown, o: Record<string, unknown> = {}, own = "menor@example.com") =>
  requestGuardianConsentHandler(deps(o) as never, "u1", own, data);

describe("requestGuardianConsentHandler: validación previa", () => {
  test("flujo desactivado -> failed-precondition sin tocar db", async () => {
    await expect(run({ guardianEmail: "t@e.com" }, { guardianFlowEnabled: false })).rejects.toMatchObject({
      code: "failed-precondition",
    });
  });
  test.each([[{}], [{ guardianEmail: 5 }], [{ guardianEmail: "sin-arroba" }], [{ guardianEmail: "a@b" }], [{ guardianEmail: "a b@c.com" }], [null],
    [{ guardianEmail: "x,victim@evil.com" }], [{ guardianEmail: "a;b@x.com" }], [{ guardianEmail: "\"a\"@x.com" }],
    [{ guardianEmail: "<a@x.com>" }], [{ guardianEmail: "a@x.com\nBcc: v@evil.com" }], [{ guardianEmail: "a@@x.com" }],
    [{ guardianEmail: "a@x.com\u0000" }], [{ guardianEmail: "(a)@x.com" }], [{ guardianEmail: "a[1]@x.com" }],
    [{ guardianEmail: "a\\b@x.com" }], [{ guardianEmail: "a".repeat(250) + "@x.com" }]])(
    "payload %j -> invalid-argument",
    async (data) => {
      await expect(run(data)).rejects.toMatchObject({ code: "invalid-argument" });
    },
  );
  test("email igual al del menor (mayúsculas/espacios) -> GUARDIAN_EMAIL_INVALID", async () => {
    await expect(run({ guardianEmail: "  MENOR@Example.com " })).rejects.toMatchObject({
      code: "invalid-argument",
      details: { reason: "GUARDIAN_EMAIL_INVALID" },
    });
  });
  test("compara con el email propio tras NFKC (anchura completa)", async () => {
    await expect(run({ guardianEmail: "ＭＥＮＯＲ@example.com" })).rejects.toMatchObject({ details: { reason: "GUARDIAN_EMAIL_INVALID" } });
  });
  test("pepper ausente falla claro antes de tocar db", async () => {
    await expect(run({ guardianEmail: "t@e.com" }, { pepper: "" })).rejects.toThrow(/GUARDIAN_EMAIL_PEPPER/);
  });
});

describe("requirePepper", () => {
  test.each([[undefined], [""], ["corto"]])("%p lanza con el nombre del secreto", (v) => {
    expect(() => requirePepper(v)).toThrow(/GUARDIAN_EMAIL_PEPPER/);
  });
  test("válido se devuelve tal cual", () => expect(requirePepper(PEPPER)).toBe(PEPPER));
});
