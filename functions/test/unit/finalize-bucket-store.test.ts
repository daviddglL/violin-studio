import { Bucket } from "@google-cloud/storage";
import { bucketStore } from "../../src/recordings/finalize";

/**
 * W1: el emulador de Storage IGNORA `ifGenerationMatch` (y `file(name, {generation})`) en delete, asi que la atomicidad
 * de produccion se fija aqui contra un Bucket falso: el SDK recibe la precondicion y 404/412 cuentan como OK.
 */
function fakeBucket(live: string | null, deleteError?: { code: unknown }, bucketExists = true) {
  const calls: unknown[] = [];
  const file = {
    getMetadata: async () => {
      if (live === null) throw Object.assign(new Error("No such object"), { code: 404 });
      return [{ generation: live }];
    },
    delete: async (opts: unknown) => {
      calls.push(opts);
      if (deleteError) throw Object.assign(new Error("x"), deleteError);
    },
  };
  // Sondeo del bucket: listar 1 objeto. En GCS un bucket inexistente da 404 (el emulador de Storage devuelve 200 vacio).
  const exists = jest.fn(async () => {
    if (!bucketExists) throw Object.assign(new Error("The specified bucket does not exist."), { code: 404 });
    return [[]];
  });
  return { bucket: { file: () => file, getFiles: exists } as unknown as Bucket, calls, exists };
}

describe("bucketStore.remove(name, generation)", () => {
  test("generation vigente: borra con la precondicion ifGenerationMatch", async () => {
    const { bucket, calls } = fakeBucket("17");
    await bucketStore(bucket).remove("users/u/recordings/r.m4a", "17");
    expect(calls).toEqual([{ ifGenerationMatch: "17" }]);
  });

  test("ya hay otra generation (el cliente resubio): no borra", async () => {
    const { bucket, calls } = fakeBucket("18");
    await bucketStore(bucket).remove("users/u/recordings/r.m4a", "17");
    expect(calls).toEqual([]);
  });

  test("404 (no existe) y 412 (cambio entre la lectura y el borrado) son OK; otros errores se propagan", async () => {
    await expect(bucketStore(fakeBucket(null).bucket).remove("n", "1")).resolves.toBeUndefined();
    await expect(bucketStore(fakeBucket("1", { code: 412 }).bucket).remove("n", "1")).resolves.toBeUndefined();
    await expect(bucketStore(fakeBucket("1", { code: 404 }).bucket).remove("n", "1")).resolves.toBeUndefined();
    await expect(bucketStore(fakeBucket("1", { code: 503 }).bucket).remove("n", "1")).rejects.toMatchObject({ code: 503 });
  });
});

describe("W3 (revision fresca B5b): bucket inexistente != objeto inexistente", () => {
  test("404 con el bucket inexistente (nombre mal configurado) -> lanza, no se da el borrado por hecho", async () => {
    await expect(bucketStore(fakeBucket(null, undefined, false).bucket).remove("n", "1")).rejects.toMatchObject({ code: "bucket-not-found" });
    await expect(bucketStore(fakeBucket("1", { code: 404 }, false).bucket).remove("n", "1")).rejects.toMatchObject({ code: "bucket-not-found" });
  });

  test("404 de objeto con el bucket vivo -> OK; la existencia del bucket se comprueba una sola vez por store", async () => {
    const f = fakeBucket(null);
    const store = bucketStore(f.bucket);
    await expect(store.remove("a", "1")).resolves.toBeUndefined();
    await expect(store.remove("b", "1")).resolves.toBeUndefined();
    expect(f.exists).toHaveBeenCalledTimes(1);
  });

  test("un fallo del sondeo que no es 404 se propaga tal cual (reintentable)", async () => {
    const f = fakeBucket(null);
    f.exists.mockRejectedValueOnce(Object.assign(new Error("x"), { code: 503 }));
    await expect(bucketStore(f.bucket).remove("n", "1")).rejects.toMatchObject({ code: 503 });
  });

  test("el error de bucket inexistente no lleva el nombre del bucket ni la ruta", async () => {
    const e = await bucketStore(fakeBucket(null, undefined, false).bucket).remove("users/u/recordings/r.m4a", "1").catch((x: Error) => x);
    expect(String((e as Error).message)).not.toContain("users/u");
  });
});
