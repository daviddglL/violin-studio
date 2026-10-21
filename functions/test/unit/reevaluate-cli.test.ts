import { runReevaluateAllCli } from "../../src/admin/reevaluate-cli";

const EMU = { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "127.0.0.1:8080", FIREBASE_AUTH_EMULATOR_HOST: "127.0.0.1:9099" };
const REAL = { GCLOUD_PROJECT: "violin-prod" };

const mk = (result = { scanned: 3, changed: 2, failed: 0 }) => {
  const out: string[] = [];
  const run = jest.fn().mockResolvedValue(result);
  const make = jest.fn(() => run);
  return { out, run, make, cli: (argv: string[], env: Record<string, string | undefined> = EMU) => runReevaluateAllCli(argv, make, (l) => out.push(l), env) };
};

test("emulador: ejecuta, imprime contadores y devuelve 0", async () => {
  const t = mk();
  expect(await t.cli([])).toBe(0);
  expect(t.run).toHaveBeenCalledTimes(1);
  expect(t.out.join("\n")).toMatch(/EMULADOR/);
  expect(t.out.join("\n")).toContain("scanned=3 changed=2 failed=0");
});

test("con fallos devuelve 1", async () => {
  const t = mk({ scanned: 3, changed: 1, failed: 2 });
  expect(await t.cli([])).toBe(1);
});

test("argumentos desconocidos -> uso y 2 sin actuar", async () => {
  const t = mk();
  expect(await t.cli(["uid"])).toBe(2);
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
  expect(await t.cli([], { ...REAL, CONFIRM_PROJECT: "violin-prod" })).toBe(0);
});

test("emulador a medias -> 2", async () => {
  const t = mk();
  expect(await t.cli([], { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "h" })).toBe(2);
});

test("fallo al inicializar o al ejecutar -> 1 sin volcar el error", async () => {
  const out: string[] = [];
  expect(await runReevaluateAllCli([], () => { throw new Error("secret-detail"); }, (l) => out.push(l), EMU)).toBe(1);
  expect(await runReevaluateAllCli([], () => async () => { throw new Error("secret-detail"); }, (l) => out.push(l), EMU)).toBe(1);
  expect(out.join(" ")).not.toContain("secret-detail");
});
