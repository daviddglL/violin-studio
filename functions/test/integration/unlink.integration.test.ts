import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { bucketStore } from "../../src/recordings/finalize";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { removeLink, RemoveLinkDeps, unlinkAllStudents } from "../../src/teacher/remove-link";
import { unlinkTeacherHandler } from "../../src/teacher/unlink";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const logs: Array<{ message: string; data: Record<string, unknown> }> = [];
const deps = (extra: Partial<RemoveLinkDeps> = {}): RemoveLinkDeps => ({
  db,
  auth,
  store: bucketStore(getStorage(app).bucket(`${project}.appspot.com`)),
  log: (message, data) => logs.push({ message, data }),
  ...extra,
});
beforeEach(() => {
  logs.length = 0;
});

const adulto = { birthDate: "1985-03-01", consentStatus: "granted", policyVersion: CURRENT_POLICY_VERSION };
async function crearUsuario(perfil: Record<string, unknown>) {
  const { uid } = await auth.createUser({ email: `ul-${Date.now()}-${Math.random()}@example.com`, emailVerified: true });
  await db.collection("users").doc(uid).set({ ...adulto, ...perfil });
  await auth.setCustomUserClaims(uid, { role: perfil.role, consentOk: true });
  return uid;
}
const profesor = (studentCount = 1) => crearUsuario({ role: "teacher", displayName: "Profe", studentCount });
const alumno = (extra: Record<string, unknown> = {}) =>
  crearUsuario({ role: "student", displayName: "Alum", teacherCount: 1, ...extra });
const independiente = () => crearUsuario({ role: "independent", displayName: "Otro" });
async function vincular(t: string, s: string) {
  await db.collection("teacherLinks").doc(`${t}_${s}`).set({
    teacherUid: t,
    studentUid: s,
    teacherDisplayName: "Profe",
    studentDisplayName: "Alum",
    studentInstrument: null,
    ackVersion: 1,
    createdAt: Timestamp.now(),
  });
}
const userDoc = async (uid: string) => (await db.collection("users").doc(uid).get()).data() ?? {};
const existe = async (t: string, s: string) => (await db.collection("teacherLinks").doc(`${t}_${s}`).get()).exists;
const claims = async (uid: string) => (await auth.getUser(uid)).customClaims;
const rechazo = (p: Promise<unknown>) =>
  p.then(() => null, (e: unknown) => e as { code: string; message: string; details?: { reason?: string } });

describe("unlinkTeacher (A3b.1)", () => {
  test.each(["alumno", "profesor"])(
    "%s desvincula: borra el vinculo, baja contadores, student->independent y sincroniza claims",
    async (quien) => {
      const t = await profesor(2);
      const s = await alumno();
      await vincular(t, s);
      const res = await unlinkTeacherHandler(deps(), quien === "alumno" ? s : t, { teacherUid: t, studentUid: s });
      expect(res).toEqual({ unlinked: true });
      expect(await existe(t, s)).toBe(false);
      expect(await userDoc(t)).toMatchObject({ role: "teacher", studentCount: 1 });
      expect(await userDoc(s)).toMatchObject({ role: "independent", teacherCount: 0 });
      expect(await claims(s)).toMatchObject({ role: "independent", consentOk: true });
      expect(await claims(t)).toMatchObject({ role: "teacher" });
    },
  );

  test("un tercero recibe el mismo permission-denied exista o no el vinculo, y no cambia nada", async () => {
    const t = await profesor();
    const s = await alumno();
    await vincular(t, s);
    const tercero = await profesor(0);
    const real = await rechazo(unlinkTeacherHandler(deps(), tercero, { teacherUid: t, studentUid: s }));
    const falso = await rechazo(
      unlinkTeacherHandler(deps(), tercero, { teacherUid: t, studentUid: await independiente() }),
    );
    expect(real?.code).toBe("permission-denied");
    expect(real?.details?.reason).toBe("NOT_LINK_PARTY");
    expect(falso).toEqual(real);
    expect(await existe(t, s)).toBe(true);
    expect(await userDoc(t)).toMatchObject({ studentCount: 1 });
  });

  test("argumentos mal formados -> invalid-argument", async () => {
    const t = await profesor();
    for (const data of [undefined, {}, { teacherUid: t }, { teacherUid: t, studentUid: "a_b" }, { teacherUid: t, studentUid: "a-b" }, { teacherUid: 1, studentUid: t }]) {
      expect((await rechazo(unlinkTeacherHandler(deps(), t, data)))?.code).toBe("invalid-argument");
    }
  });

  test("vinculo ausente sin relacion pendiente (desconocido): no ejecuta limpiezas ni toca claims ni contadores", async () => {
    const t = await profesor(0);
    const s = await alumno({ teacherCount: 0, role: "independent" });
    await auth.setCustomUserClaims(s, { role: "student", consentOk: true }); // claim desfasado: no se toca
    const paso = jest.fn();
    await expect(unlinkTeacherHandler(deps({ cleanupSteps: [paso] }), s, { teacherUid: t, studentUid: s })).resolves.toEqual({
      unlinked: true,
    });
    await expect(unlinkTeacherHandler(deps({ cleanupSteps: [paso] }), t, { teacherUid: t, studentUid: s })).resolves.toEqual({
      unlinked: true,
    });
    expect(paso).not.toHaveBeenCalled();
    expect((await claims(s))?.role).toBe("student");
    expect(await userDoc(t)).toMatchObject({ studentCount: 0 });
    expect(await userDoc(s)).toMatchObject({ teacherCount: 0, role: "independent" });
  });

  test("los contadores nunca bajan de 0 aunque esten desfasados", async () => {
    const t = await profesor(0);
    const s = await alumno({ teacherCount: 0 });
    await vincular(t, s);
    await removeLink(deps(), t, s);
    expect(await userDoc(t)).toMatchObject({ studentCount: 0 });
    expect(await userDoc(s)).toMatchObject({ teacherCount: 0, role: "independent" });
  });

  test("alumno borrado (sin doc): borra el vinculo y baja el contador del profesor", async () => {
    const t = await profesor(1);
    const s = "alumnoborrado0123456789abcd";
    await vincular(t, s);
    await removeLink(deps(), t, s);
    expect(await existe(t, s)).toBe(false);
    expect(await userDoc(t)).toMatchObject({ studentCount: 0 });
  });

  test("los logs solo llevan hashes: nunca el uid ni los nombres", async () => {
    const t = await profesor();
    const s = await alumno();
    await vincular(t, s);
    await unlinkTeacherHandler(deps(), s, { teacherUid: t, studentUid: s });
    await rechazo(unlinkTeacherHandler(deps(), await profesor(0), { teacherUid: t, studentUid: s }));
    const texto = JSON.stringify(logs);
    expect(texto).toContain("uidHash");
    for (const secreto of [t, s, "Profe", "Alum"]) expect(texto).not.toContain(secreto);
  });
});

describe("removeLink reanudable (A3b.2)", () => {
  test("fallo tras la transaccion: repetir la llamada completa los pasos restantes con el mismo resultado", async () => {
    const t = await profesor(2);
    const s = await alumno();
    await vincular(t, s);
    const paso = jest.fn().mockRejectedValueOnce(new Error("parcial")).mockResolvedValue(undefined);
    await expect(removeLink(deps({ cleanupSteps: [paso] }), t, s)).rejects.toThrow("parcial");
    expect(await existe(t, s)).toBe(false);
    expect(await userDoc(t)).toMatchObject({ studentCount: 1 });
    expect(await userDoc(s)).toMatchObject({ role: "independent", teacherCount: 0 });

    expect((await userDoc(s)).pendingUnlinks).toEqual([t]); // marca de limpieza pendiente
    await removeLink(deps({ cleanupSteps: [paso] }), t, s);
    expect(paso).toHaveBeenCalledTimes(2);
    expect((await userDoc(s)).pendingUnlinks).toEqual([]);
    await removeLink(deps({ cleanupSteps: [paso] }), t, s); // ya completo: no repite
    expect(paso).toHaveBeenCalledTimes(2);
    expect(paso).toHaveBeenLastCalledWith(t, s);
    expect(await userDoc(t)).toMatchObject({ studentCount: 1 });
    expect(await userDoc(s)).toMatchObject({ role: "independent", teacherCount: 0 });
    expect(await claims(s)).toMatchObject({ role: "independent" });
  });

  test("fallo al sincronizar claims: el reintento los deja correctos sin tocar contadores", async () => {
    const t = await profesor(1);
    const s = await alumno();
    await vincular(t, s);
    const espia = jest.spyOn(auth, "setCustomUserClaims").mockRejectedValueOnce(new Error("boom"));
    try {
      await expect(removeLink(deps(), t, s)).rejects.toThrow("boom");
    } finally {
      espia.mockRestore();
    }
    expect((await claims(s))?.role).toBe("student");
    await removeLink(deps(), t, s);
    expect(await claims(s)).toMatchObject({ role: "independent" });
    expect(await userDoc(t)).toMatchObject({ studentCount: 0 });
  });

  test("llamadas concurrentes decrementan una sola vez", async () => {
    const t = await profesor(2);
    const s = await alumno();
    await vincular(t, s);
    await Promise.all([removeLink(deps(), t, s), removeLink(deps(), t, s), removeLink(deps(), t, s)]);
    expect(await userDoc(t)).toMatchObject({ studentCount: 1 });
    expect(await userDoc(s)).toMatchObject({ teacherCount: 0, role: "independent" });
  });
});

describe("removeLink robusto (revision A3b)", () => {
  test("alumno sin usuario en Auth (borrado a medias): borra el vinculo y no falla", async () => {
    const t = await profesor(1);
    const s = await alumno();
    await vincular(t, s);
    await auth.deleteUser(s);
    await expect(removeLink(deps(), t, s)).resolves.toBeUndefined();
    expect(await existe(t, s)).toBe(false);
    expect(await userDoc(t)).toMatchObject({ studentCount: 0 });
    expect(JSON.stringify(logs)).not.toContain(s);
    await expect(removeLink(deps(), t, s)).resolves.toBeUndefined(); // reintento: sin marca pendiente
  });

  test("no escribe en un usuario con deletion en curso, pero borra el vinculo y ajusta el otro lado", async () => {
    const t = await profesor(1);
    const s = await alumno({ deletion: { startedAt: Timestamp.now() } });
    await vincular(t, s);
    await removeLink(deps(), t, s);
    expect(await existe(t, s)).toBe(false);
    expect(await userDoc(t)).toMatchObject({ studentCount: 0 });
    expect(await userDoc(s)).toMatchObject({ teacherCount: 1, role: "student" });
    expect((await userDoc(s)).pendingUnlinks).toBeUndefined();

    const t2 = await crearUsuario({ role: "teacher", studentCount: 1, deletion: { startedAt: Timestamp.now() } });
    const s2 = await alumno();
    await vincular(t2, s2);
    await removeLink(deps(), t2, s2);
    expect(await userDoc(t2)).toMatchObject({ studentCount: 1 });
    expect(await userDoc(s2)).toMatchObject({ teacherCount: 0, role: "independent" });
  });

  test("unlinkAllStudents sigue con los demas aunque uno falle, lanza un agregado y el reintento cierra al fallido", async () => {
    const t = await profesor(3);
    const ss = [await alumno(), await alumno(), await alumno()];
    for (const s of ss) await vincular(t, s);
    let fallar = true;
    const paso = jest.fn(async (_t: string, s: string) => {
      if (fallar && s === ss[1]) throw new Error("parcial");
    });
    await expect(unlinkAllStudents(deps({ cleanupSteps: [paso] }), t)).rejects.toThrow(/1/);
    for (const s of ss) expect(await existe(t, s)).toBe(false);
    expect(await userDoc(ss[2])).toMatchObject({ role: "independent" });
    expect(await userDoc(t)).toMatchObject({ studentCount: 0 });
    fallar = false;
    paso.mockClear();
    await expect(unlinkAllStudents(deps({ cleanupSteps: [paso] }), t)).resolves.toBe(1);
    expect(paso).toHaveBeenCalledTimes(1);
    expect(paso).toHaveBeenCalledWith(t, ss[1]);
    expect(await claims(ss[1])).toMatchObject({ role: "independent" });
  });
});

describe("removeLink limpia tareas (A6.4)", () => {
  const tarea = (s: string, id: string, teacherUid: string, status: "open" | "done") =>
    db
      .collection("users")
      .doc(s)
      .collection("assignments")
      .doc(id)
      .set({ teacherUid, title: id, status, createdAt: Timestamp.now(), updatedAt: Timestamp.now(), ...(status === "done" ? { doneAt: Timestamp.now() } : {}) });
  const ids = async (s: string) =>
    (await db.collection("users").doc(s).collection("assignments").get()).docs.map((d) => d.id).sort();

  test("borra las open del profesor, conserva sus done y deja intactas las de otro profesor; repetir es idempotente", async () => {
    const t = await profesor();
    const otro = await profesor();
    const s = await alumno({ teacherCount: 2 });
    await vincular(t, s);
    await vincular(otro, s);
    await tarea(s, "t-open1", t, "open");
    await tarea(s, "t-open2", t, "open");
    await tarea(s, "t-done", t, "done");
    await tarea(s, "o-open", otro, "open");
    await removeLink(deps(), t, s);
    expect(await ids(s)).toEqual(["o-open", "t-done"]);
    await removeLink(deps(), t, s);
    expect(await ids(s)).toEqual(["o-open", "t-done"]);
  });

  test("forma parte del flujo reanudable: si un paso posterior falla, reintentar completa la limpieza", async () => {
    const t = await profesor();
    const s = await alumno();
    await vincular(t, s);
    await tarea(s, "a1", t, "open");
    const fallo = jest.fn(async () => {
      throw new Error("parcial");
    });
    await expect(removeLink(deps({ cleanupSteps: [fallo] }), t, s)).rejects.toThrow("parcial");
    await tarea(s, "a2", t, "open"); // aparece una abierta entre medias (p. ej. escritura offline)
    await removeLink(deps(), t, s);
    expect(await ids(s)).toEqual([]);
    expect((await userDoc(s)).pendingUnlinks ?? []).toEqual([]);
  });

  test("unlinkAllStudents tambien limpia las tareas de cada alumno", async () => {
    const t = await profesor(2);
    const [s1, s2] = [await alumno(), await alumno()];
    for (const s of [s1, s2]) {
      await vincular(t, s);
      await tarea(s, "x", t, "open");
    }
    await unlinkAllStudents(deps(), t);
    expect(await ids(s1)).toEqual([]);
    expect(await ids(s2)).toEqual([]);
  });
});
