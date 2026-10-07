import { requireCodePepper } from "../../src/teacher/pepper";

test("requireCodePepper acepta >= 32 caracteres y rechaza ausente o corto sin revelar el valor", () => {
  expect(requireCodePepper("x".repeat(32))).toBe("x".repeat(32));
  expect(() => requireCodePepper(undefined)).toThrow(/TEACHER_CODE_PEPPER/);
  expect(() => requireCodePepper("secreto-corto")).toThrow(/TEACHER_CODE_PEPPER/);
  expect(() => requireCodePepper("secreto-corto")).not.toThrow(/secreto-corto/);
});
