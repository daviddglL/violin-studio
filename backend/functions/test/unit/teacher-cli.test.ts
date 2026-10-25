import { runTeacherCli, runTeacherRequestsCli, TeacherCliAction } from "../../src/admin/teacher-cli";
import { TeacherRoleError } from "../../src/admin/teacher-role";

const EMU = { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "127.0.0.1:8080", FIREBASE_AUTH_EMULATOR_HOST: "127.0.0.1:9099", STORAGE_BUCKET: "demo-x.appspot.com" };
const REAL = { GCLOUD_PROJECT: "violin-prod" };

const mk = () => {
  const out: string[] = [];
  const grant = jest.fn().mockResolvedValue(undefined);
  const revoke = jest.fn().mockResolvedValue(undefined);
  const reject = jest.fn().mockResolvedValue(undefined);
  const make = jest.fn(() => ({ grant, revoke, reject }));
  return {
    out, grant, revoke, reject, make,
    run: (action: TeacherCliAction, argv: string[], env: Record<string, string | undefined> = EMU) =>
      runTeacherCli(action, argv, make, (l) => out.push(l), env),
  };
};

test("grant con un uid invoca grant y devuelve 0", async () => {
  const t = mk();
  expect(await t.run("grant", ["abc123"])).toBe(0);
  expect(t.grant).toHaveBeenCalledWith("abc123");
  expect(t.revoke).not.toHaveBeenCalled();
});

test("revoke con un uid invoca revoke", async () => {
  const t = mk();
  expect(await t.run("revoke", ["abc123"])).toBe(0);
  expect(t.revoke).toHaveBeenCalledWith("abc123");
});

test.each([[[]], [["a", "b"]]])("argumentos incorrectos %p -> uso y 2 sin llamar", async (argv) => {
  const t = mk();
  expect(await t.run("grant", argv)).toBe(2);
  expect(t.grant).not.toHaveBeenCalled();
  expect(t.out.join(" ")).toMatch(/uso/i);
});

test.each([["-x"], ["--help"], ["-"]])("S5: argumento que empieza por - (%s) -> uso, sin llamar", async (arg) => {
  const t = mk();
  expect(await t.run("grant", [arg])).toBe(2);
  expect(t.make).not.toHaveBeenCalled();
  expect(t.out.join(" ")).toMatch(/uso/i);
});

test("W3: exige GCLOUD_PROJECT", async () => {
  const t = mk();
  expect(await t.run("grant", ["u"], {})).toBe(2);
  expect(t.out.join(" ")).toContain("GCLOUD_PROJECT");
  expect(t.make).not.toHaveBeenCalled();
});

test("W3: imprime proyecto y destino antes de actuar (emulador)", async () => {
  const t = mk();
  await t.run("grant", ["u"]);
  expect(t.out[0]).toContain("demo-x");
  expect(t.out[0]).toMatch(/EMULADOR/);
});

test("W3: proyecto REAL sin confirmacion -> 2 sin actuar", async () => {
  const t = mk();
  expect(await t.run("grant", ["u"], REAL)).toBe(2);
  expect(t.out[0]).toContain("violin-prod");
  expect(t.out[0]).toMatch(/REAL/);
  expect(t.make).not.toHaveBeenCalled();
});

test("W3: proyecto REAL con --yes o CONFIRM_PROJECT coincidente actua", async () => {
  const a = mk();
  expect(await a.run("grant", ["u", "--yes"], REAL)).toBe(0);
  const b = mk();
  expect(await b.run("grant", ["u"], { ...REAL, CONFIRM_PROJECT: "violin-prod" })).toBe(0);
  const c = mk();
  expect(await c.run("grant", ["u"], { ...REAL, CONFIRM_PROJECT: "otro" })).toBe(2);
  expect(c.make).not.toHaveBeenCalled();
});

test("W3: emulador solo a medias (un host de dos) -> 2", async () => {
  const t = mk();
  expect(await t.run("grant", ["u"], { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "h:1" })).toBe(2);
  expect(t.make).not.toHaveBeenCalled();
});

test("W3: fallo al inicializar la app -> 1 sin volcar la traza", async () => {
  const t = mk();
  t.make.mockImplementation(() => {
    throw new Error("credenciales en /ruta/secreta.json");
  });
  expect(await t.run("grant", ["u"])).toBe(1);
  expect(t.out.join(" ")).not.toContain("secreta");
});

test("TeacherRoleError -> 1 con el motivo y sin el uid", async () => {
  const t = mk();
  t.grant.mockRejectedValue(new TeacherRoleError("NOT_ADULT"));
  expect(await t.run("grant", ["secretuid"])).toBe(1);
  expect(t.out.join(" ")).toContain("NOT_ADULT");
  expect(t.out.join(" ")).not.toContain("secretuid");
});

test("error inesperado -> 1 sin volcar el mensaje", async () => {
  const t = mk();
  t.revoke.mockRejectedValue(new Error("detalle con a@b.com"));
  expect(await t.run("revoke", ["u"])).toBe(1);
  expect(t.out.join(" ")).not.toContain("a@b.com");
});

describe("W3 (revision fresca B5b): revoke-teacher exige el bucket de Storage", () => {
  const NO_BUCKET = { GCLOUD_PROJECT: "demo-x", FIRESTORE_EMULATOR_HOST: "h:1", FIREBASE_AUTH_EMULATOR_HOST: "h:2" };

  test("revoke sin STORAGE_BUCKET ni FIREBASE_CONFIG.storageBucket -> 2 sin inicializar nada", async () => {
    const t = mk();
    expect(await t.run("revoke", ["u"], NO_BUCKET)).toBe(2);
    expect(t.out.join(" ")).toContain("STORAGE_BUCKET");
    expect(t.make).not.toHaveBeenCalled();
  });

  test("FIREBASE_CONFIG ilegible o sin storageBucket cuenta como ausente", async () => {
    for (const FIREBASE_CONFIG of ["{no json", JSON.stringify({ projectId: "demo-x" }), JSON.stringify({ storageBucket: "" })]) {
      const t = mk();
      expect(await t.run("revoke", ["u"], { ...NO_BUCKET, FIREBASE_CONFIG })).toBe(2);
      expect(t.make).not.toHaveBeenCalled();
    }
  });

  test("STORAGE_BUCKET se pasa a makeActions y se imprime; FIREBASE_CONFIG.storageBucket sirve de respaldo", async () => {
    const a = mk();
    expect(await a.run("revoke", ["u"], { ...NO_BUCKET, STORAGE_BUCKET: "b1.firebasestorage.app" })).toBe(0);
    expect(a.make).toHaveBeenCalledWith({ bucket: "b1.firebasestorage.app" });
    expect(a.out.join(" ")).toContain("b1.firebasestorage.app");
    const b = mk();
    expect(await b.run("revoke", ["u"], { ...NO_BUCKET, FIREBASE_CONFIG: JSON.stringify({ storageBucket: "b2.appspot.com" }) })).toBe(0);
    expect(b.make).toHaveBeenCalledWith({ bucket: "b2.appspot.com" });
  });

  test("grant no necesita el bucket", async () => {
    const t = mk();
    expect(await t.run("grant", ["u"], NO_BUCKET)).toBe(0);
    expect(t.grant).toHaveBeenCalledWith("u");
  });
});

test("reject con un uid invoca reject y no exige bucket", async () => {
  const t = mk();
  const sinBucket = { ...EMU, STORAGE_BUCKET: undefined };
  expect(await t.run("reject", ["abc123"], sinBucket)).toBe(0);
  expect(t.reject).toHaveBeenCalledWith("abc123");
  expect(t.out.join(" ")).toContain("reject-teacher: OK");
});

test("reject sin solicitud pendiente -> 1 con el motivo", async () => {
  const t = mk();
  t.reject.mockRejectedValue(new TeacherRoleError("NO_PENDING_REQUEST"));
  expect(await t.run("reject", ["abc123"])).toBe(1);
  expect(t.out.join(" ")).toContain("NO_PENDING_REQUEST");
});

describe("teacher-requests", () => {
  const run = (argv: string[], list: jest.Mock, env: Record<string, string | undefined> = EMU) => {
    const out: string[] = [];
    return runTeacherRequestsCli(argv, () => list, (l) => out.push(l), env).then((code) => ({ code, out }));
  };

  test("lista uid y fecha de cada solicitud pendiente", async () => {
    const list = jest.fn().mockResolvedValue([
      { uid: "u1", requestedAt: "2026-10-20T10:00:00.000Z" },
      { uid: "u2", requestedAt: null },
    ]);
    const { code, out } = await run([], list);
    expect(code).toBe(0);
    expect(out).toContain("u1  2026-10-20T10:00:00.000Z");
    expect(out).toContain("u2  (sin fecha)");
  });

  test("sin pendientes lo dice", async () => {
    const { code, out } = await run([], jest.fn().mockResolvedValue([]));
    expect(code).toBe(0);
    expect(out).toContain("Sin solicitudes pendientes.");
  });

  test("argumentos de mas -> uso y 2 sin listar", async () => {
    const list = jest.fn();
    const { code, out } = await run(["u1"], list);
    expect(code).toBe(2);
    expect(list).not.toHaveBeenCalled();
    expect(out.join(" ")).toMatch(/uso/i);
  });

  test("proyecto REAL sin confirmacion -> 2 sin listar", async () => {
    const list = jest.fn();
    const { code } = await run([], list, REAL);
    expect(code).toBe(2);
    expect(list).not.toHaveBeenCalled();
  });

  test("un fallo al listar -> 1 sin detalles", async () => {
    const { code, out } = await run([], jest.fn().mockRejectedValue(new Error("secreto")));
    expect(code).toBe(1);
    expect(out.join(" ")).not.toContain("secreto");
  });
});
