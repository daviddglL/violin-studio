import { systemClock } from "../../src/common/clock";
import { COLLECTIONS, CollectionName } from "../../src/common/collections";

describe("COLLECTIONS", () => {
  test("registra las colecciones de primer nivel de identidad", () => {
    expect(COLLECTIONS).toEqual({
      users: "users",
      consents: "consents",
      guardianRequests: "guardianRequests",
      guardianEmailLimits: "guardianEmailLimits",
      mail: "mail",
    });
  });
  test("CollectionName admite los valores registrados", () => {
    const nombre: CollectionName = COLLECTIONS.users;
    expect(nombre).toBe("users");
  });
});

describe("systemClock", () => {
  test("devuelve la hora actual", () => {
    const antes = Date.now();
    const ahora = systemClock().getTime();
    expect(ahora).toBeGreaterThanOrEqual(antes);
    expect(ahora).toBeLessThanOrEqual(Date.now());
  });
});
