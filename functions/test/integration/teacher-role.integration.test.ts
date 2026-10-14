import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { bucketStore } from "../../src/recordings/finalize";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { grantTeacher, revokeTeacher, TeacherRoleDeps, TeacherRoleError } from "../../src/admin/teacher-role";
import { createTeacherCodeHandler } from "../../src/teacher/code-handlers";
import { bucket, exists, seedRecording, upload } from "./helpers/finalize-fixtures";

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
  store: bucketStore(getStorage(app).bucket(`${project}.appspot.com`)),
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

  test("W5: un profesor con borrado en curso no se reconfirma", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await db.collection("users").doc(uid).update({ deletion: { state: "in_progress" } });
    expect((await rechazo(grantTeacher(deps(), uid)))?.reason).toBe("DELETION_IN_PROGRESS");
  });

  test("W4: un teacherLinks con studentUid=uid bloquea el alta aunque el contador sea 0", async () => {
    const { uid } = await nuevoUsuario();
    await db.collection("teacherLinks").doc(`profeX_${uid}`).set({ teacherUid: "profeX", studentUid: uid });
    const antes = await perfil(uid);
    expect((await rechazo(grantTeacher(deps(), uid)))?.reason).toBe("HAS_TEACHER_LINKS");
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
  const poner = (uid: string, parche: Record<string, unknown>) => db.collection("users").doc(uid).update(parche);

  test("W1: el cambio de rol ocurre ANTES de la limpieza y studentCount no se sobrescribe", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await poner(uid, { studentCount: 3 });
    const orden: string[] = [];
    await revokeTeacher(
      deps({
        revokeActiveCodes: async () => {
          orden.push(`codes:${(await perfil(uid))?.role}`);
          return 2;
        },
        unlinkAllStudents: async () => {
          const d = await perfil(uid);
          orden.push(`students:${d?.role}:${d?.studentCount}`);
          await poner(uid, { studentCount: 0 });
          return 3;
        },
      }),
      uid,
    );
    expect(orden).toEqual(["codes:independent", "students:independent:3"]);
    expect((await perfil(uid))?.role).toBe("independent");
    expect(await claims(uid)).toEqual({ role: "independent", consentOk: true });
  });

  async function conAlumnos(n: number) {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await poner(uid, { studentCount: n });
    const alumnos: string[] = [];
    for (let i = 0; i < n; i++) {
      const a = await nuevoUsuario({ perfil: { role: "student", teacherCount: 1 } });
      await auth.setCustomUserClaims(a.uid, { role: "student", consentOk: true });
      await db.collection("teacherLinks").doc(`${uid}_${a.uid}`).set({ teacherUid: uid, studentUid: a.uid });
      alumnos.push(a.uid);
    }
    return { uid, alumnos };
  }

  test("B5b.4: al revocar el rol se borran las demostraciones para cada alumno (objeto en demos/ + doc) y no las grabaciones del profesor", async () => {
    const { uid, alumnos } = await conAlumnos(2);
    const demos: string[] = [];
    for (const a of alumnos) {
      const ev = await upload(uid, { dir: "demos", meta: { sharedWith: a, assignmentId: "a1" } });
      await seedRecording(uid, ev, { sharedWith: a, assignmentId: "a1" });
      demos.push(ev.name);
    }
    const own = await upload(uid);
    await seedRecording(uid, own);
    await revokeTeacher(deps({ store: bucketStore(bucket) }), uid);
    for (const d of demos) expect(await exists(d)).toBe(false);
    expect(await exists(own.name)).toBe(true);
    expect((await db.collection("users").doc(uid).collection("recordings").get()).size).toBe(1);
  });

  test("A3b.4: desvincula a todos los alumnos (por defecto), revoca codigos y deja claims sincronizados", async () => {
    const { uid, alumnos } = await conAlumnos(2);
    const { id } = await createTeacherCodeHandler({ db, pepper: "ci-fixed-non-secret-pepper-0123456789abcdef" }, uid);
    await revokeTeacher(deps(), uid);
    expect(await perfil(uid)).toMatchObject({ role: "independent", studentCount: 0 });
    expect((await db.collection("teacherCodes").doc(id).get()).data()?.revokedAt).not.toBeNull();
    for (const a of alumnos) {
      expect((await db.collection("teacherLinks").doc(`${uid}_${a}`).get()).exists).toBe(false);
      expect(await perfil(a)).toMatchObject({ role: "independent", teacherCount: 0 });
      expect(await claims(a)).toMatchObject({ role: "independent" });
    }
    expect(await claims(uid)).toEqual({ role: "independent", consentOk: true });
  });

  test("sin alumnos la limpieza por defecto es tolerante", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await revokeTeacher(deps(), uid);
    expect((await perfil(uid))?.role).toBe("independent");
  });

  test("usuario que no es profesor y sin restos: no-op sin error", async () => {
    const { uid } = await nuevoUsuario();
    const antes = await perfil(uid);
    await revokeTeacher(deps(), uid);
    expect(await perfil(uid)).toEqual(antes);
  });

  test("W1: reanudable: si falla la limpieza el rol ya es independent y el reintento la repite", async () => {
    const { uid, alumnos } = await conAlumnos(2);
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
    expect(await perfil(uid)).toMatchObject({ role: "independent", studentCount: 2 });
    // El reintento (limpieza por defecto) completa el trabajo.
    await revokeTeacher(deps(), uid);
    expect(await perfil(uid)).toMatchObject({ role: "independent", studentCount: 0 });
    expect(await perfil(alumnos[0])).toMatchObject({ role: "independent", teacherCount: 0 });
    expect(await claims(uid)).toEqual({ role: "independent", consentOk: true });
  });

  test("contador desfasado a 0 pero con vinculos vivos: revocar tambien los elimina", async () => {
    const { uid, alumnos } = await conAlumnos(1);
    await poner(uid, { studentCount: 0, role: "independent" }); // reanudacion con contador desfasado
    await revokeTeacher(deps(), uid);
    expect((await db.collection("teacherLinks").doc(`${uid}_${alumnos[0]}`).get()).exists).toBe(false);
    expect(await perfil(alumnos[0])).toMatchObject({ role: "independent", teacherCount: 0 });
  });

  test("independent sin restos: revocar de nuevo consulta los vinculos (no-op) en vez de fiarse del contador", async () => {
    const { uid } = await nuevoUsuario();
    await grantTeacher(deps(), uid);
    await revokeTeacher(deps(), uid);
    const unlink = jest.fn(async () => 0);
    await revokeTeacher(deps({ unlinkAllStudents: unlink }), uid);
    expect(unlink).toHaveBeenCalledTimes(1);
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
