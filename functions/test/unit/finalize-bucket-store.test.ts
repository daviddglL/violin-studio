import { Bucket } from "@google-cloud/storage";
import { bucketStore } from "../../src/recordings/finalize";

/**
 * W1: el emulador de Storage IGNORA `ifGenerationMatch` (y `file(name, {generation})`) en delete, asi que la atomicidad
 * de produccion se fija aqui contra un Bucket falso: el SDK recibe la precondicion y 404/412 cuentan como OK.
 */
function fakeBucket(live: string | null, deleteError?: { code: unknown }) {
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
  return { bucket: { file: () => file } as unknown as Bucket, calls };
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
