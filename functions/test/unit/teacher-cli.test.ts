import { runTeacherCli } from "../../src/admin/teacher-cli";
import { TeacherRoleError } from "../../src/admin/teacher-role";

const EMU = { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "127.0.0.1:8080", FIREBASE_AUTH_EMULATOR_HOST: "127.0.0.1:9099" };
const REAL = { GCLOUD_PROJECT: "violin-prod" };

const mk = () => {
  const out: string[] = [];
  const grant = jest.fn().mockResolvedValue(undefined);
  const revoke = jest.fn().mockResolvedValue(undefined);
  const make = jest.fn(() => ({ grant, revoke }));
  return {
    out, grant, revoke, make,
    run: (action: "grant" | "revoke", argv: string[], env: Record<string, string | undefined> = EMU) =>
      runTeacherCli(action, argv, make, (l) => out.push(l), env),
  };
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

test.each([["-x"], ["--help"], ["-"]])("S5: argumento que empieza por - (%s) -> uso, sin llamar", async (arg) => {
  const t = mk();
  expect(await t.run("grant", [arg])).toBe(2);
  expect(t.make).not.toHaveBeenCalled();
  expect(t.out.join(" ")).toMatch(/uso/i);
});

test("W3: exige GCLOUD_PROJECT", async () => {
  const t = mk();
  expect(await t.run("grant", ["u"], {})).toBe(2);
  expect(t.out.join(" ")).toContain("GCLOUD_PROJECT");
  expect(t.make).not.toHaveBeenCalled();
});

test("W3: imprime proyecto y destino antes de actuar (emulador)", async () => {
  const t = mk();
  await t.run("grant", ["u"]);
  expect(t.out[0]).toContain("demo-x");
  expect(t.out[0]).toMatch(/EMULADOR/);
});

test("W3: proyecto REAL sin confirmacion -> 2 sin actuar", async () => {
  const t = mk();
  expect(await t.run("grant", ["u"], REAL)).toBe(2);
  expect(t.out[0]).toContain("violin-prod");
  expect(t.out[0]).toMatch(/REAL/);
  expect(t.make).not.toHaveBeenCalled();
});

test("W3: proyecto REAL con --yes o CONFIRM_PROJECT coincidente actua", async () => {
  const a = mk();
  expect(await a.run("grant", ["u", "--yes"], REAL)).toBe(0);
  const b = mk();
  expect(await b.run("grant", ["u"], { ...REAL, CONFIRM_PROJECT: "violin-prod" })).toBe(0);
  const c = mk();
  expect(await c.run("grant", ["u"], { ...REAL, CONFIRM_PROJECT: "otro" })).toBe(2);
  expect(c.make).not.toHaveBeenCalled();
});

test("W3: emulador solo a medias (un host de dos) -> 2", async () => {
  const t = mk();
  expect(await t.run("grant", ["u"], { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "h:1" })).toBe(2);
  expect(t.make).not.toHaveBeenCalled();
});

test("W3: fallo al inicializar la app -> 1 sin volcar la traza", async () => {
  const t = mk();
  t.make.mockImplementation(() => {
    throw new Error("credenciales en /ruta/secreta.json");
  });
  expect(await t.run("grant", ["u"])).toBe(1);
  expect(t.out.join(" ")).not.toContain("secreta");
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
