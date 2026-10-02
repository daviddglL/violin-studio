import { safeErrorCode } from "../../src/common/errors";

test("solo códigos string o number; cualquier otra cosa (incluido el mensaje) -> unknown", () => {
  expect(safeErrorCode({ code: "unavailable" })).toBe("unavailable");
  expect(safeErrorCode({ code: 14 })).toBe(14);
  for (const raro of [{ code: { uid: "u1" } }, { code: ["x"] }, { code: null }, { message: "users/u1" }, null, undefined, "texto", new Error("users/u1")]) {
    expect(safeErrorCode(raro)).toBe("unknown");
  }
});
