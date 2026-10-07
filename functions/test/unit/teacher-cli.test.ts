import { runTeacherCli } from "../../src/admin/teacher-cli";
import { TeacherRoleError } from "../../src/admin/teacher-role";

const mk = () => {
  const out: string[] = [];
  const grant = jest.fn().mockResolvedValue(undefined);
  const revoke = jest.fn().mockResolvedValue(undefined);
  return { out, grant, revoke, run: (action: "grant" | "revoke", argv: string[]) =>
    runTeacherCli(action, argv, { grant, revoke }, (l) => out.push(l)) };
};

test("grant con un uid invoca grant y devuelve 0", async () => {
  const t = mk();
  expect(await t.run("grant", ["abc123"])).toBe(0);
  expect(t.grant).toHaveBeenCalledWith("abc123");
  expect(t.revoke).not.toHaveBeenCalled();
});

test("revoke con un uid invoca revoke", async () => {
  const t = mk();
  expect(await t.run("revoke", ["abc123"])).toBe(0);
  expect(t.revoke).toHaveBeenCalledWith("abc123");
});

test.each([[[]], [["a", "b"]]])("argumentos incorrectos %p -> uso y 2 sin llamar", async (argv) => {
  const t = mk();
  expect(await t.run("grant", argv)).toBe(2);
  expect(t.grant).not.toHaveBeenCalled();
  expect(t.out.join(" ")).toMatch(/uso/i);
});

test("TeacherRoleError -> 1 con el motivo y sin el uid", async () => {
  const t = mk();
  t.grant.mockRejectedValue(new TeacherRoleError("NOT_ADULT"));
  expect(await t.run("grant", ["secretuid"])).toBe(1);
  expect(t.out.join(" ")).toContain("NOT_ADULT");
  expect(t.out.join(" ")).not.toContain("secretuid");
});

test("error inesperado -> 1 sin volcar el mensaje", async () => {
  const t = mk();
  t.revoke.mockRejectedValue(new Error("detalle con a@b.com"));
  expect(await t.run("revoke", ["u"])).toBe(1);
  expect(t.out.join(" ")).not.toContain("a@b.com");
});
