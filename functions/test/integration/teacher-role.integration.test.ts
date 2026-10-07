import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { grantTeacher, revokeTeacher, TeacherRoleDeps, TeacherRoleError } from "../../src/admin/teacher-role";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const HOY = new Date("2026-10-07T12:00:00Z");
const logs: Array<{ message: string; data: Record<string, unknown> }> = [];
const deps = (parche: Partial<TeacherRoleDeps> = {}): TeacherRoleDeps => ({
  db,
  auth,
  clock: () => HOY,
  log: (message, data) => logs.push({ message, data }),
  ...parche,
});

async function nuevoUsuario(opts: { emailVerified?: boolean; perfil?: Record<string, unknown> | null } = {}) {
  const email = `tr-${Date.now()}-${Math.random()}@example.com`;
  const { uid } = await auth.createUser({ email, password: "Passw0rd!x", emailVerified: opts.emailVerified ?? true });
  if (opts.perfil !== null) {
    await db.collection("users").doc(uid).set({
      role: "independent",
      displayName: "Nombre Secreto",
      birthDate: "1990-04-02",
      consentStatus: "granted",
      policyVersion: CURRENT_POLICY_VERSION,
      ...opts.perfil,
    });
  }
  return { uid, email };
}
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data();
const claims = async (uid: string) => (await auth.getUser(uid)).customClaims;
const rechazo = async (p: Promise<unknown>) => p.then(() => null, (e: unknown) => e as TeacherRoleError);

beforeEach(() => {
  logs.length = 0;
});

describe("grantTeacher", () => {
  test("alta valida: role=teacher, studentCount=0 y claims sincronizados", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    expect(await perfil(uid)).toMatchObject({ role: "teacher", studentCount: 0 });
    expect(await claims(uid)).toEqual({ role: "teacher", consentOk: true });
  });

  test("repetirlo no cambia nada ni reescribe", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    const antes = await perfil(uid);
    const espia = jest.spyOn(auth, "setCustomUserClaims");
    try {
      await grantTeacher(deps(), uid);
      expect(espia).not.toHaveBeenCalled();
    } finally {
      espia.mockRestore();
    }
    expect(await perfil(uid)).toEqual(antes);
  });

  test("fallo al sincronizar claims: reintentar completa el estado", async () => {
    const { uid } = await nuevoUsuario();
    const espia = jest.spyOn(auth, "setCustomUserClaims").mockRejectedValueOnce(new Error("boom"));
    try {
      await expect(grantTeacher(deps(), uid)).rejects.toThrow("boom");
    } finally {
      espia.mockRestore();
    }
    expect((await perfil(uid))?.role).toBe("teacher");
    await grantTeacher(deps(), uid);
    expect(await claims(uid)).toEqual({ role: "teacher", consentOk: true });
  });

  test.each(["a_b", "_", "x_"])("uid con _ rechazado (%s) sin tocar nada", async (uid) => {
    const e = await rechazo(grantTeacher(deps(), uid));
    expect(e).toBeInstanceOf(TeacherRoleError);
    expect(e?.reason).toBe("INVALID_UID");
  });

  test("uid vacio rechazado", async () => {
    expect((await rechazo(grantTeacher(deps(), "")))?.reason).toBe("INVALID_UID");
  });

  test.each([
    ["menor de 18 (17 anios y 364 dias)", { perfil: { birthDate: "2008-10-08" } }, "NOT_ADULT"],
    ["email sin verificar", { emailVerified: false }, "EMAIL_NOT_VERIFIED"],
    ["consentimiento pendiente", { perfil: { consentStatus: "pending" } }, "CONSENT_NOT_CURRENT"],
    ["politica antigua", { perfil: { policyVersion: CURRENT_POLICY_VERSION - 1 } }, "CONSENT_NOT_CURRENT"],
    ["borrado en curso", { perfil: { deletion: { state: "in_progress" } } }, "DELETION_IN_PROGRESS"],
    ["vinculos como alumno", { perfil: { role: "student", teacherCount: 1 } }, "HAS_TEACHER_LINKS"],
  ])("rechaza: %s", async (_n, opts, reason) => {
    const { uid } = await nuevoUsuario(opts as Parameters<typeof nuevoUsuario>[0]);
    const antes = await perfil(uid);
    const e = await rechazo(grantTeacher(deps(), uid));
    expect(e?.reason).toBe(reason);
    expect(await perfil(uid)).toEqual(antes);
  });

  test("usuario sin perfil: rechaza sin crear documentos", async () => {
    const { uid } = await nuevoUsuario({ perfil: null });
    const e = await rechazo(grantTeacher(deps(), uid));
    expect(e?.reason).toBe("NO_PROFILE");
    expect((await db.collection("users").doc(uid).get()).exists).toBe(false);
  });

  test("usuario inexistente en Auth: USER_NOT_FOUND", async () => {
    const e = await rechazo(grantTeacher(deps(), "uidquenoexiste1234567890abcd"));
    expect(e?.reason).toBe("USER_NOT_FOUND");
  });

  test("los logs no contienen email, nombre, birthDate ni el uid en claro", async () => {
    const { uid, email } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await rechazo(grantTeacher(deps(), "a_b"));
    const { uid: menor } = await nuevoUsuario({ perfil: { birthDate: "2015-01-01" } });
    await rechazo(grantTeacher(deps(), menor));
    const texto = JSON.stringify(logs);
    expect(logs.length).toBeGreaterThan(0);
    for (const secreto of [email, "Nombre Secreto", "1990-04-02", "2015-01-01", uid, menor]) {
      expect(texto).not.toContain(secreto);
    }
    expect(texto).toContain("uidHash");
  });
});

describe("revokeTeacher", () => {
  test("profesor -> independent, claims sincronizados y limpieza ejecutada antes del cambio de rol", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    const orden: string[] = [];
    await revokeTeacher(
      deps({
        revokeActiveCodes: async () => {
          orden.push(`codes:${(await perfil(uid))?.role}`);
          return 2;
        },
        unlinkAllStudents: async () => {
          orden.push(`students:${(await perfil(uid))?.role}`);
          return 3;
        },
      }),
      uid,
    );
    expect(orden).toEqual(["codes:teacher", "students:teacher"]);
    expect((await perfil(uid))?.role).toBe("independent");
    expect(await claims(uid)).toEqual({ role: "independent", consentOk: true });
  });

  test("sin colecciones aun: la limpieza por defecto es tolerante", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await revokeTeacher(deps(), uid);
    expect((await perfil(uid))?.role).toBe("independent");
  });

  test("usuario que no es profesor: no-op sin error", async () => {
    const { uid } = await nuevoUsuario();
    const antes = await perfil(uid);
    await revokeTeacher(deps(), uid);
    expect(await perfil(uid)).toEqual(antes);
  });

  test("reanudable: si falla la limpieza el rol no cambia y el reintento termina", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await expect(
      revokeTeacher(
        deps({
          unlinkAllStudents: async () => {
            throw new Error("parcial");
          },
        }),
        uid,
      ),
    ).rejects.toThrow("parcial");
    expect((await perfil(uid))?.role).toBe("teacher");
    await revokeTeacher(deps(), uid);
    expect((await perfil(uid))?.role).toBe("independent");
    expect(await claims(uid)).toEqual({ role: "independent", consentOk: true });
  });

  test("reanudable: fallo al sincronizar claims tras cambiar el rol, el reintento los sincroniza", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    const espia = jest.spyOn(auth, "setCustomUserClaims").mockRejectedValueOnce(new Error("boom"));
    try {
      await expect(revokeTeacher(deps(), uid)).rejects.toThrow("boom");
    } finally {
      espia.mockRestore();
    }
    expect((await perfil(uid))?.role).toBe("independent");
    await revokeTeacher(deps(), uid);
    expect(await claims(uid)).toEqual({ role: "independent", consentOk: true });
  });

  test("uid con _ rechazado", async () => {
    expect((await rechazo(revokeTeacher(deps(), "a_b")))?.reason).toBe("INVALID_UID");
  });
});
