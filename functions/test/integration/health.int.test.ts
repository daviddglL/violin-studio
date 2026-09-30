import { VERSION } from "../../src/version";

const host = process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001";
const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const url = `http://${host}/${project}/europe-west1/health`;

test("health responde ok en el emulador", async () => {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ data: {} }),
  });
  expect(res.status).toBe(200);
  expect(await res.json()).toEqual({ result: { status: "ok", version: VERSION } });
});
