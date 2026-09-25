import functionsTest from "firebase-functions-test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { health } from "../../src/index";
import { VERSION } from "../../src/version";

const fft = functionsTest();
afterAll(() => fft.cleanup());

test("health responde ok con la versión", async () => {
  const wrapped = fft.wrap(health);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  await expect(wrapped({ data: {} } as any)).resolves.toEqual({ status: "ok", version: VERSION });
});

test("VERSION coincide con package.json", () => {
  const pkg = JSON.parse(readFileSync(join(__dirname, "../../package.json"), "utf8"));
  expect(VERSION).toBe(pkg.version);
});
