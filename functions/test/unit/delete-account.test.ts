import { HttpsError } from "firebase-functions/v2/https";
import { deleteAccountHandler } from "../../src/erasure/delete-account";

const NOW = new Date("2026-01-01T12:00:00Z");
const nowSec = NOW.getTime() / 1000;
const clock = () => NOW;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const req = (auth: unknown) => ({ auth }) as any;

async function fallo(p: Promise<unknown>): Promise<HttpsError> {
  try {
    await p;
  } catch (e) {
    return e as HttpsError;
  }
  throw new Error("no lanzó");
}

function setup(erase = jest.fn().mockResolvedValue({ deleted: {} })) {
  return { erase, deps: { erase, clock, log: jest.fn() } };
}

test("sin sesión -> unauthenticated y sin borrado", async () => {
  const { erase, deps } = setup();
  expect((await fallo(deleteAccountHandler(deps, req(undefined)))).code).toBe("unauthenticated");
  expect(erase).not.toHaveBeenCalled();
});

test("auth_time de hace 4:59 procede y borra con deleteAuth:true", async () => {
  const { erase, deps } = setup();
  await expect(deleteAccountHandler(deps, req({ uid: "u1", token: { auth_time: nowSec - 299 } }))).resolves.toEqual({ deleted: true });
  expect(erase).toHaveBeenCalledWith("u1");
});

test("auth_time de hace 5:01 -> REAUTH_REQUIRED sin borrar", async () => {
  const { erase, deps } = setup();
  const err = await fallo(deleteAccountHandler(deps, req({ uid: "u1", token: { auth_time: nowSec - 301 } })));
  expect(err.code).toBe("failed-precondition");
  expect(err.details).toMatchObject({ reason: "REAUTH_REQUIRED" });
  expect(erase).not.toHaveBeenCalled();
});

test("sin auth_time -> REAUTH_REQUIRED", async () => {
  const { erase, deps } = setup();
  expect((await fallo(deleteAccountHandler(deps, req({ uid: "u1", token: {} })))).details).toMatchObject({ reason: "REAUTH_REQUIRED" });
  expect(erase).not.toHaveBeenCalled();
});

test("D1: no exige email_verified ni consentimiento", async () => {
  const { erase, deps } = setup();
  const token = { auth_time: nowSec - 10, email_verified: false, consentOk: false };
  await expect(deleteAccountHandler(deps, req({ uid: "u2", token }))).resolves.toEqual({ deleted: true });
  expect(erase).toHaveBeenCalledWith("u2");
});

test("fallo de la cascada -> internal/ERASURE_FAILED sin filtrar el mensaje original ni loguearlo", async () => {
  const erase = jest.fn().mockRejectedValue(new Error("fail users/u3/secret.txt"));
  const { deps } = setup(erase);
  const err = await fallo(deleteAccountHandler(deps, req({ uid: "u3", token: { auth_time: nowSec } })));
  expect(err.code).toBe("internal");
  expect(err.details).toMatchObject({ reason: "ERASURE_FAILED" });
  expect(err.message).not.toContain("u3");
  expect(JSON.stringify(deps.log.mock.calls)).not.toContain("secret");
  expect(JSON.stringify(deps.log.mock.calls)).not.toContain("u3");
});
