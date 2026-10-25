import { runCompletePurgeCli } from "../../src/admin/complete-purge-cli";
import { CompletePurgeError } from "../../src/recordings/complete-purge";

const EMU = { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "127.0.0.1:8080" };
const REAL = { GCLOUD_PROJECT: "violin-prod" };

const mk = (result: { outcome: "completed" | "not_pending"; remaining: number } = { outcome: "completed", remaining: 0 }) => {
  const out: string[] = [];
  const run = jest.fn().mockResolvedValue(result);
  const make = jest.fn(() => run);
  return { out, run, make, cli: (argv: string[], env: Record<string, string | undefined> = EMU) => runCompletePurgeCli(argv, make, (l) => out.push(l), env) };
};

describe("admin:complete-recording-purge (W4 de la revision fresca B5b)", () => {
  test("emulador: cierra la purga del uid, imprime el resultado sin el uid y devuelve 0", async () => {
    const t = mk();
    expect(await t.cli(["user-123"])).toBe(0);
    expect(t.run).toHaveBeenCalledWith("user-123", { force: false });
    const text = t.out.join("\n");
    expect(text).toMatch(/EMULADOR/);
    expect(text).toContain("complete-recording-purge: completed remaining=0");
    expect(text).not.toContain("user-123");
  });

  test("--force se pasa al nucleo", async () => {
    const t = mk({ outcome: "completed", remaining: 2 });
    expect(await t.cli(["u1", "--force"])).toBe(0);
    expect(t.run).toHaveBeenCalledWith("u1", { force: true });
    expect(t.out.join("\n")).toContain("remaining=2");
  });

  test("sin purga pendiente: not_pending y 0", async () => {
    const t = mk({ outcome: "not_pending", remaining: 0 });
    expect(await t.cli(["u1"])).toBe(0);
    expect(t.out.join("\n")).toContain("not_pending");
  });

  test("quedan grabaciones sin --force -> 1 con el motivo y el recuento, sin uid", async () => {
    const t = mk();
    t.run.mockRejectedValue(new CompletePurgeError("RECORDINGS_REMAIN", 3));
    expect(await t.cli(["secretuid"])).toBe(1);
    const text = t.out.join("\n");
    expect(text).toContain("RECORDINGS_REMAIN remaining=3");
    expect(text).not.toContain("secretuid");
  });

  test.each([[[]], [["a", "b"]], [["--help"]], [["-x", "u"]]])("argumentos incorrectos %p -> uso y 2 sin actuar", async (argv) => {
    const t = mk();
    expect(await t.cli(argv)).toBe(2);
    expect(t.make).not.toHaveBeenCalled();
    expect(t.out.join(" ")).toMatch(/uso/i);
  });

  test("exige GCLOUD_PROJECT", async () => {
    const t = mk();
    expect(await t.cli(["u"], {})).toBe(2);
    expect(t.make).not.toHaveBeenCalled();
  });

  test("proyecto REAL sin confirmacion -> 2; con --yes o CONFIRM_PROJECT -> 0", async () => {
    const t = mk();
    expect(await t.cli(["u"], REAL)).toBe(2);
    expect(t.make).not.toHaveBeenCalled();
    expect(await t.cli(["u", "--yes"], REAL)).toBe(0);
    expect(await t.cli(["u"], { ...REAL, CONFIRM_PROJECT: "violin-prod" })).toBe(0);
    expect(await t.cli(["u"], { ...REAL, CONFIRM_PROJECT: "otro" })).toBe(2);
  });

  test("fallo al inicializar o error inesperado -> 1 sin volcar el detalle", async () => {
    const out: string[] = [];
    expect(await runCompletePurgeCli(["u"], () => { throw new Error("secret-detail"); }, (l) => out.push(l), EMU)).toBe(1);
    expect(await runCompletePurgeCli(["u"], () => async () => { throw new Error("secret-detail"); }, (l) => out.push(l), EMU)).toBe(1);
    expect(out.join(" ")).not.toContain("secret-detail");
  });
});
