import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { Firestore, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { eraseUserData } from "../../src/erasure/erase-user-data";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const bucket = getStorage(app).bucket();

const logs: Array<{ message: string; data: Record<string, unknown> }> = [];
const log = (message: string, data: Record<string, unknown>) => logs.push({ message, data });
const sleep = async () => undefined;
const deps = { db, auth, bucket, log, sleep };
beforeEach(() => {
  logs.length = 0;
});

const adulto = { birthDate: "1985-03-01", consentStatus: "granted", policyVersion: CURRENT_POLICY_VERSION };
async function crearUsuario(perfil: Record<string, unknown>) {
  const { uid } = await auth.createUser({ email: `el-${Date.now()}-${Math.random()}@example.com`, emailVerified: true });
  await db.collection("users").doc(uid).set({ ...adulto, displayName: "NombreSecreto", ...perfil });
  await auth.setCustomUserClaims(uid, { role: perfil.role, consentOk: true });
  return uid;
}
const profesor = (studentCount = 0) => crearUsuario({ role: "teacher", studentCount });
const alumno = (teacherCount = 1, extra: Record<string, unknown> = {}) =>
  crearUsuario({ role: teacherCount > 0 ? "student" : "independent", teacherCount, ...extra });
async function vincular(t: string, s: string) {
  await db.collection("teacherLinks").doc(`${t}_${s}`).set({ teacherUid: t, studentUid: s, ackVersion: 1, createdAt: Timestamp.now() });
}
const tarea = (s: string, id: string, teacherUid: string, status = "open") =>
  db.collection("users").doc(s).collection("assignments").doc(id).set({ teacherUid, status, title: "TituloSecreto" });
const userDoc = async (uid: string) => (await db.collection("users").doc(uid).get()).data() ?? {};
const claims = async (uid: string) => (await auth.getUser(uid)).customClaims;
const tareas = async (s: string) => (await db.collection("users").doc(s).collection("assignments").get()).docs.map((d) => d.id).sort();
const links = async (campo: string, uid: string) => (await db.collection("teacherLinks").where(campo, "==", uid).get()).size;
const codigo = (id: string, extra: Record<string, unknown>) => db.collection("teacherCodes").doc(id).set({ expiresAt: Timestamp.now(), ...extra });

/** Proxy de Firestore cuyo `collectionGroup` falla `veces` veces con un error transitorio (gRPC UNAVAILABLE). */
function dbFlaky(veces: number): Firestore {
  let restantes = veces;
  return new Proxy(db, {
    get(target, prop) {
      if (prop === "collectionGroup") {
        return (id: string) => {
          if (restantes-- > 0) throw Object.assign(new Error("transitorio"), { code: 14 });
          return target.collectionGroup(id);
        };
      }
      const v = Reflect.get(target, prop, target);
      return typeof v === "function" ? v.bind(target) : v;
    },
  });
}

describe("borrar alumno (REQ-DEL-T01)", () => {
  test("removeLink antes que nada: contadores del profesor bajan, rol intacto, tareas caen, otros alumnos intactos", async () => {
    const t = await profesor(2);
    const t2 = await profesor(1);
    const s = await alumno(1, { pendingUnlinks: [t2] });
    const otro = await alumno(1);
    await vincular(t, s);
    await vincular(t, otro);
    await tarea(s, "a1", t);
    await tarea(otro, "b1", t);
    await codigo("c-usado", { teacherUid: t, usedBy: s, usedAt: Timestamp.now() });
    await codigo("c-libre", { teacherUid: t });

    const res = await eraseUserData(deps, s, { deleteAuth: true });

    expect(res.deleted).toMatchObject({ teacherLinks: 1, teacherCodes: 1 });
    expect(await links("studentUid", s)).toBe(0);
    expect(await userDoc(t)).toMatchObject({ role: "teacher", studentCount: 1 });
    expect(await claims(t)).toMatchObject({ role: "teacher" });
    expect((await db.collection("users").doc(s).get()).exists).toBe(false);
    expect(await tareas(s)).toEqual([]);
    // El otro alumno y la tarea del profesor en su espacio siguen.
    expect(await links("studentUid", otro)).toBe(1);
    expect(await tareas(otro)).toEqual(["b1"]);
    // El codigo canjeado por el alumno cae; el libre del profesor no.
    expect((await db.collection("teacherCodes").doc("c-usado").get()).exists).toBe(false);
    expect((await db.collection("teacherCodes").doc("c-libre").get()).exists).toBe(true);
    // removeLink corre dentro del paso `collections`, antes de que se de por terminado.
    const iLink = logs.findIndex((l) => l.message === "removeLink");
    const iColecciones = logs.findIndex((l) => l.message === "erasure.step" && l.data.step === "collections");
    expect(iLink).toBeGreaterThanOrEqual(0);
    expect(iLink).toBeLessThan(iColecciones);
  });
});

describe("borrar profesor (REQ-DEL-T02, T04)", () => {
  test("vinculos fuera, alumnos a independent, codigos y todas sus tareas (en lotes), sin PII en logs", async () => {
    const t = await profesor(3);
    const t2 = await profesor(1);
    const s1 = await alumno(1);
    const s2 = await alumno(2); // tambien de t2: sigue siendo student
    const s3 = await alumno(0, { pendingUnlinks: [t] }); // vinculo ya borrado, limpieza pendiente
    await vincular(t, s1);
    await vincular(t, s2);
    await vincular(t2, s2);
    const w = db.bulkWriter();
    for (let i = 0; i < 450; i++) {
      void w.set(db.collection("users").doc(s1).collection("assignments").doc(`t${i}`), {
        teacherUid: t,
        status: i % 2 ? "done" : "open",
        title: "TituloSecreto",
      });
    }
    await w.close();
    await tarea(s2, "mia", t, "done");
    await tarea(s2, "ajena", t2);
    await db.collection("users").doc(s1).collection("practiceSessions").doc("p1").set({ durationSec: 60 });
    await codigo("c1", { teacherUid: t });
    await codigo("c2", { teacherUid: t, usedBy: s1, usedAt: Timestamp.now() });
    await codigo("c3", { teacherUid: t2 });

    const res = await eraseUserData(deps, t, { deleteAuth: true });

    expect(res.deleted).toMatchObject({ teacherCodes: 2, assignments: 451 });
    expect(await links("teacherUid", t)).toBe(0);
    expect(await userDoc(s1)).toMatchObject({ role: "independent", teacherCount: 0 });
    expect(await userDoc(s2)).toMatchObject({ role: "student", teacherCount: 1 });
    expect(await userDoc(s3)).toMatchObject({ role: "independent" });
    expect((await userDoc(s3)).pendingUnlinks ?? []).toEqual([]);
    expect(await claims(s1)).toMatchObject({ role: "independent" });
    expect(await claims(s2)).toMatchObject({ role: "student" });
    expect(await tareas(s1)).toEqual([]);
    expect(await tareas(s2)).toEqual(["ajena"]);
    expect((await db.collection("users").doc(s1).collection("practiceSessions").get()).size).toBe(1);
    expect((await db.collection("teacherCodes").where("teacherUid", "==", t).get()).size).toBe(0);
    expect((await db.collection("teacherCodes").doc("c3").get()).exists).toBe(true);
    expect(await userDoc(t2)).toMatchObject({ role: "teacher", studentCount: 1 });
    await expect(auth.getUser(t)).rejects.toMatchObject({ code: "auth/user-not-found" });

    const texto = JSON.stringify(logs);
    for (const secreto of [t, s1, s2, s3, "TituloSecreto", "NombreSecreto", "@example.com"]) expect(texto).not.toContain(secreto);
    expect(logs.some((l) => l.data.step === "shared")).toBe(true);
  });

  test("el paso shared se reintenta ante un fallo transitorio y converge; repetir es idempotente", async () => {
    const t = await profesor(1);
    const s = await alumno(1);
    await vincular(t, s);
    await tarea(s, "x1", t);
    await tarea(s, "x2", t, "done");

    await eraseUserData({ ...deps, db: dbFlaky(1) }, t, { deleteAuth: false });

    expect(logs.some((l) => l.message === "erasure.stepFailed" && l.data.step === "shared")).toBe(true);
    expect(await tareas(s)).toEqual([]);
    await expect(eraseUserData(deps, t, { deleteAuth: false })).resolves.toBeDefined();
    expect(await tareas(s)).toEqual([]);
  });

  test("el barrido final incluye shared y codigos: lo creado en la ventana previa a Auth no sobrevive", async () => {
    const t = await profesor(0);
    const s = await alumno(0);
    const conVentana = {
      ...deps,
      auth: new Proxy(auth, {
        get(target, prop) {
          const v = Reflect.get(target, prop, target);
          if (prop !== "deleteUser") return typeof v === "function" ? v.bind(target) : v;
          return async (uid: string) => {
            await tarea(s, "tarde", t);
            await codigo("c-tarde", { teacherUid: t });
            return target.deleteUser(uid);
          };
        },
      }),
    };

    await eraseUserData(conVentana, t, { deleteAuth: true });

    expect(await tareas(s)).toEqual([]);
    expect((await db.collection("teacherCodes").doc("c-tarde").get()).exists).toBe(false);
  });

  test("profesor y alumno en borrado a la vez convergen", async () => {
    const t = await profesor(1);
    const s = await alumno(1, { deletion: { state: "in_progress", startedAt: Timestamp.now() } });
    await vincular(t, s);
    await tarea(s, "a", t);

    await eraseUserData(deps, t, { deleteAuth: true });
    await eraseUserData(deps, s, { deleteAuth: true });

    expect(await links("studentUid", s)).toBe(0);
    expect((await db.collection("users").doc(s).get()).exists).toBe(false);
    expect(await tareas(s)).toEqual([]);
  });
});
