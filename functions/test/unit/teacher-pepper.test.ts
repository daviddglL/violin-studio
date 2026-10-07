import { requireCodePepper } from "../../src/teacher/pepper";

test("requireCodePepper acepta >= 32 caracteres", () => {
  expect(requireCodePepper("x".repeat(32))).toBe("x".repeat(32));
});

test.each([[undefined], [""], ["secreto-corto"]])("pepper %p -> HttpsError internal tipado, sin revelar el valor", (v) => {
  let e: { code?: string; message?: string; details?: { reason?: string } } = {};
  try {
    requireCodePepper(v);
  } catch (x) {
    e = x as typeof e;
  }
  expect(e.code).toBe("internal");
  expect(e.details?.reason).toBe("SERVER_MISCONFIGURED");
  expect(JSON.stringify(e)).not.toContain("secreto-corto");
});
