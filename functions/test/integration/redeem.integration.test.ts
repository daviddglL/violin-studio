import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { CURRENT_POLICY_VERSION, LINK_NOTICE_VERSION } from "../../src/config/identity";
import { createTeacherCodeHandler, revokeTeacherCodeHandler } from "../../src/teacher/code-handlers";
import { redeemTeacherCodeHandler, RedeemDeps } from "../../src/teacher/redeem";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const PEPPER = "ci-fixed-non-secret-pepper-0123456789abcdef";
let ahora = new Date("2026-10-07T12:00:00Z");
const logs: Array<{ message: string; data: Record<string, unknown> }> = [];
const log = (message: string, data: Record<string, unknown>) => logs.push({ message, data });
const deps = (): RedeemDeps => ({ db, auth, pepper: PEPPER, clock: () => ahora, log });
beforeEach(() => {
  ahora = new Date("2026-10-07T12:00:00Z");
  logs.length = 0;
});

async function crearUsuario(perfil: Record<string, unknown>) {
  const { uid } = await auth.createUser({ email: `rd-${Date.now()}-${Math.random()}@example.com`, emailVerified: true });
  await db.collection("users").doc(uid).set(perfil);
  await auth.setCustomUserClaims(uid, { role: perfil.role, consentOk: true });
  return uid;
}
const adulto = { birthDate: "1985-03-01", consentStatus: "granted", policyVersion: CURRENT_POLICY_VERSION };
const profesor = (extra: Record<string, unknown> = {}) =>
  crearUsuario({ role: "teacher", displayName: "Profe Ana", instrument: "violin", studentCount: 0, ...adulto, ...extra });
const alumno = (extra: Record<string, unknown> = {}) =>
  crearUsuario({ role: "independent", displayName: "Alum Bo", instrument: "viola", ...adulto, ...extra });
const nuevoCodigo = async (t: string) => (await createTeacherCodeHandler({ ...deps(), pepper: PEPPER }, t)).code;
const userDoc = async (uid: string) => (await db.collection("users").doc(uid).get()).data() ?? {};
const codeDoc = async (id: string) => (await db.collection("teacherCodes").doc(id).get()).data();
const linkDoc = async (t: string, s: string) => (await db.collection("teacherLinks").doc(`${t}_${s}`).get()).data();
const canjear = (s: string, code: unknown, ackVersion: unknown = LINK_NOTICE_VERSION) =>
  redeemTeacherCodeHandler(deps(), s, { code, ackVersion });
const rechazo = (p: Promise<unknown>) =>
  p.then(() => null, (e: unknown) => e as { code: string; message: string; details?: { reason?: string } });

describe("redeemTeacherCode - camino feliz (A3a.2)", () => {
  test("crea el vinculo, marca el codigo, sube contadores, pasa a student y sincroniza claims", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    const res = await canjear(s, code);
    expect(res).toEqual({ linked: true, teacherUid: t });

    expect(await linkDoc(t, s)).toMatchObject({
      teacherUid: t,
      studentUid: s,
      teacherDisplayName: "Profe Ana",
      studentDisplayName: "Alum Bo",
      studentInstrument: "viola",
      ackVersion: LINK_NOTICE_VERSION,
    });
    expect((await linkDoc(t, s))?.createdAt.toMillis()).toBe(ahora.getTime());
    const hash = (await db.collection("teacherCodes").where("teacherUid", "==", t).get()).docs[0].id;
    const c = await codeDoc(hash);
    expect(c?.usedBy).toBe(s);
    expect(c?.usedAt.toMillis()).toBe(ahora.getTime());
    expect((await userDoc(t)).studentCount).toBe(1);
    expect(await userDoc(s)).toMatchObject({ teacherCount: 1, role: "student" });
    expect((await auth.getUser(s)).customClaims).toMatchObject({ role: "student", consentOk: true });
  });

  test("tolera el codigo como lo teclea el alumno (minusculas, guiones, espacios)", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    const tecleado = `${code.slice(0, 4)}-${code.slice(4)}`.toLowerCase();
    await expect(canjear(s, ` ${tecleado} `)).resolves.toMatchObject({ linked: true });
  });
});
