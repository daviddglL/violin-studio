import { onUserDeletedHandler } from "../../src/erasure/on-user-deleted";
import * as erase from "../../src/erasure/erase-user-data";

afterEach(() => jest.restoreAllMocks());
const deps = {} as never;

test("error permanente: se registra solo uidHash y código, se traga y no hay reintento", async () => {
  jest.spyOn(erase, "eraseUserData").mockRejectedValue(Object.assign(new Error("denied users/u9/x"), { code: "permission-denied" }));
  const log = jest.fn();
  await expect(onUserDeletedHandler({ ...(deps as object), log } as never, "u9")).resolves.toBeUndefined();
  expect(log).toHaveBeenCalledTimes(1);
  const [, data] = log.mock.calls[0];
  expect(Object.keys(data).sort()).toEqual(["code", "uidHash"]);
  expect(data.code).toBe("permission-denied");
  expect(JSON.stringify(log.mock.calls)).not.toMatch(/u9|users/);
});

test("error transitorio: se relanza para que la plataforma reintente", async () => {
  const e = Object.assign(new Error("boom"), { code: "unavailable" });
  jest.spyOn(erase, "eraseUserData").mockRejectedValue(e);
  await expect(onUserDeletedHandler({ log: jest.fn() } as never, "u9")).rejects.toBe(e);
});

test("éxito: devuelve el resultado de la cascada con deleteAuth:false", async () => {
  const spy = jest.spyOn(erase, "eraseUserData").mockResolvedValue({ deleted: {} });
  await expect(onUserDeletedHandler({} as never, "u9")).resolves.toEqual({ deleted: {} });
  expect(spy).toHaveBeenCalledWith(expect.anything(), "u9", { deleteAuth: false });
});
