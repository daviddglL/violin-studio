import { storedObjectPath } from "../../src/recordings/delete-recording";

/** Que objeto se borra para un doc (revision fresca de B5a): solo una ruta propia, del MISMO id y coherente con el tipo. */
describe("storedObjectPath", () => {
  const rec = (over: Record<string, unknown> = {}) => ({ status: "ready", objectPath: "users/u1/recordings/r1.m4a", generation: "1", ...over });

  test("ruta propia del mismo id -> se acepta (grabacion y demo con sharedWith)", () => {
    expect(storedObjectPath("u1", "r1", rec())).toBe("users/u1/recordings/r1.m4a");
    expect(storedObjectPath("u1", "d1", rec({ objectPath: "users/u1/demos/d1.m4a", sharedWith: "s1" }))).toBe("users/u1/demos/d1.m4a");
  });

  test("el id de la ruta debe ser el id del doc", () => {
    expect(storedObjectPath("u1", "otro", rec())).toBeNull();
  });

  test("otro dueno o ruta fuera de los prefijos -> null", () => {
    expect(storedObjectPath("u2", "r1", rec())).toBeNull();
    expect(storedObjectPath("u1", "r1", rec({ objectPath: "users/u1/avatar.png" }))).toBeNull();
    expect(storedObjectPath("u1", "r1", rec({ objectPath: 7 }))).toBeNull();
    expect(storedObjectPath("u1", "r1", undefined)).toBeNull();
  });

  test("doc ready: el prefijo debe casar con el tipo (demos/ <=> sharedWith)", () => {
    expect(storedObjectPath("u1", "r1", rec({ objectPath: "users/u1/demos/r1.m4a" }))).toBeNull();
    expect(storedObjectPath("u1", "r1", rec({ sharedWith: "s1" }))).toBeNull();
  });

  test("doc rejected (no guarda sharedWith): cualquiera de los dos prefijos vale", () => {
    expect(storedObjectPath("u1", "r1", rec({ status: "rejected", objectPath: "users/u1/demos/r1.m4a" }))).toBe("users/u1/demos/r1.m4a");
    expect(storedObjectPath("u1", "r1", rec({ status: "rejected" }))).toBe("users/u1/recordings/r1.m4a");
  });
});
