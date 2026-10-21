import { runRecountRecordingsCli } from "../../src/admin/recount-cli";

const EMU = { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "127.0.0.1:8080" };
const REAL = { GCLOUD_PROJECT: "violin-prod" };

const mk = (result = { scanned: 3, repaired: 2, failed: 0, stopped: false }) => {
  const out: string[] = [];
  const run = jest.fn().mockResolvedValue(result);
  const make = jest.fn(() => run);
  return { out, run, make, cli: (argv: string[], env: Record<string, string | undefined> = EMU) => runRecountRecordingsCli(argv, make, (l) => out.push(l), env) };
};

test("emulador: recalcula todo, imprime contadores y devuelve 0", async () => {
  const t = mk();
  expect(await t.cli([])).toBe(0);
  expect(t.run).toHaveBeenCalledWith(undefined);
  expect(t.out.join("\n")).toMatch(/EMULADOR/);
  expect(t.out.join("\n")).toContain("scanned=3 repaired=2 failed=0");
});

test("con un uid recalcula solo ese dueno, y el uid no se imprime", async () => {
  const t = mk({ scanned: 1, repaired: 1, failed: 0, stopped: false });
  expect(await t.cli(["user-123"])).toBe(0);
  expect(t.run).toHaveBeenCalledWith("user-123");
  expect(t.out.join("\n")).not.toContain("user-123");
});

test("con fallos devuelve 1", async () => {
  expect(await mk({ scanned: 3, repaired: 1, failed: 2, stopped: false }).cli([])).toBe(1);
});

test("argumentos de mas u opciones desconocidas -> uso y 2 sin actuar", async () => {
  const t = mk();
  expect(await t.cli(["a", "b"])).toBe(2);
  expect(await t.cli(["--help"])).toBe(2);
  expect(t.make).not.toHaveBeenCalled();
});

test("exige GCLOUD_PROJECT", async () => {
  const t = mk();
  expect(await t.cli([], {})).toBe(2);
  expect(t.make).not.toHaveBeenCalled();
});

test("proyecto REAL sin confirmacion -> 2; con --yes o CONFIRM_PROJECT -> 0", async () => {
  const t = mk();
  expect(await t.cli([], REAL)).toBe(2);
  expect(t.make).not.toHaveBeenCalled();
  expect(await t.cli(["--yes"], REAL)).toBe(0);
  expect(await t.cli(["u1", "--yes"], REAL)).toBe(0);
  expect(await t.cli([], { ...REAL, CONFIRM_PROJECT: "violin-prod" })).toBe(0);
});

test("fallo al inicializar o al ejecutar -> 1 sin volcar el error", async () => {
  const out: string[] = [];
  expect(await runRecountRecordingsCli([], () => { throw new Error("secret-detail"); }, (l) => out.push(l), EMU)).toBe(1);
  expect(await runRecountRecordingsCli([], () => async () => { throw new Error("secret-detail"); }, (l) => out.push(l), EMU)).toBe(1);
  expect(out.join(" ")).not.toContain("secret-detail");
});
