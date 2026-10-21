import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { bucketStore } from "../../src/recordings/finalize";
import { CURRENT_POLICY_VERSION, LINK_NOTICE_VERSION } from "../../src/config/identity";
import { hashCode } from "../../src/teacher/codes";
import { createTeacherCodeHandler, revokeTeacherCodeHandler } from "../../src/teacher/code-handlers";
import { redeemTeacherCodeHandler, RedeemDeps } from "../../src/teacher/redeem";
import { removeLink } from "../../src/teacher/remove-link";

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

const idDe = async (t: string) => (await db.collection("teacherCodes").where("teacherUid", "==", t).get()).docs[0].id;
const fallos = async (s: string) => ((await userDoc(s)).linkRedeem?.failures ?? []).length;
const CODIGO_AJENO = "ABCDEFGH";

describe("redeemTeacherCode - errores genericos (A3a.3)", () => {
  test("inexistente, caducado, revocado y usado por otro dan el MISMO NOT_FOUND y suman un fallo", async () => {
    const t = await profesor();
    const caducado = await nuevoCodigo(t);
    const revocado = await nuevoCodigo(t);
    const usado = await nuevoCodigo(t);
    const otro = await alumno();
    await canjear(otro, usado);
    const idRevocado = (await db.collection("teacherCodes").where("teacherUid", "==", t).where("codeHint", "==", revocado.slice(-2)).get()).docs[0].id;
    await revokeTeacherCodeHandler(deps(), t, { id: idRevocado });
    const s = await alumno();
    const errores = [];
    errores.push(await rechazo(canjear(s, CODIGO_AJENO)));
    errores.push(await rechazo(canjear(s, revocado)));
    errores.push(await rechazo(canjear(s, usado)));
    expect(await fallos(s)).toBe(3);
    ahora = new Date(ahora.getTime() + 8 * 86_400_000);
    errores.push(await rechazo(canjear(s, caducado)));
    for (const e of errores) {
      expect(e).toMatchObject({ code: "not-found", message: errores[0]?.message, details: { reason: "CODE_NOT_FOUND" } });
    }
    expect(await fallos(s)).toBe(1); // los 3 anteriores salieron de la ventana de 1 h al avanzar 8 dias
    expect(await userDoc(s)).toMatchObject({ role: "independent" });
    expect((await userDoc(s)).teacherCount).toBeUndefined();
  });

  test("entrada invalida: invalid-argument sin sumar fallo", async () => {
    const s = await alumno();
    for (const code of [undefined, 42, "corto", "ABCDEFGH1", "AAAA-0OIL"]) {
      expect(await rechazo(canjear(s, code))).toMatchObject({ code: "invalid-argument" });
    }
    expect(await fallos(s)).toBe(0);
  });

  test("ackVersion obsoleto: NOTICE_OUTDATED, sin cambios y sin fallo", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    expect(await rechazo(canjear(s, code, 999))).toMatchObject({ code: "failed-precondition", details: { reason: "NOTICE_OUTDATED" } });
    expect(await fallos(s)).toBe(0);
    expect((await codeDoc(await idDe(t)))?.usedBy).toBeNull();
    await expect(canjear(s, code)).resolves.toMatchObject({ linked: true });
  });

  test("profesor lleno: TEACHER_FULL (resource-exhausted, cuenta como fallo) y el codigo sigue libre", async () => {
    const t = await profesor({ studentCount: 30 });
    const s = await alumno();
    expect(await rechazo(canjear(s, await nuevoCodigo(t)))).toMatchObject({ code: "resource-exhausted", details: { reason: "TEACHER_FULL" } });
    expect(await fallos(s)).toBe(1);
    expect((await codeDoc(await idDe(t)))?.usedBy).toBeNull();
    expect((await userDoc(s)).role).toBe("independent");
  });

  test("reintento del mismo alumno con el mismo codigo: exito idempotente sin duplicar contadores", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    await canjear(s, code);
    await expect(canjear(s, code)).resolves.toEqual({ linked: true, teacherUid: t });
    expect((await userDoc(t)).studentCount).toBe(1);
    expect((await userDoc(s)).teacherCount).toBe(1);
    expect(await fallos(s)).toBe(0);
  });

  test.each([
    ["ya no es profesor", { role: "independent" }],
    ["consentimiento caducado", { policyVersion: 0 }],
    ["consentimiento revocado", { consentStatus: "revoked" }],
    ["borrado en curso", { deletion: { requestedAt: Timestamp.now() } }],
    ["menor de edad", { birthDate: "2015-01-01" }],
  ])("profesor %s: mismo NOT_FOUND generico", async (_n, cambio) => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    await db.collection("users").doc(t).update(cambio);
    expect(await rechazo(canjear(s, code))).toMatchObject({ code: "not-found", details: { reason: "CODE_NOT_FOUND" } });
    expect((await codeDoc(await idDe(t)))?.usedBy).toBeNull();
    expect(await linkDoc(t, s)).toBeUndefined();
  });

  test("alumno con profesor, profesor, sin consentimiento o en borrado: rechazo tipado y sin cambios", async () => {
    const t = await profesor();
    const t2 = await profesor();
    const code = await nuevoCodigo(t);
    const vinculado = await alumno();
    await canjear(vinculado, await nuevoCodigo(t2));
    expect(await rechazo(canjear(vinculado, code))).toMatchObject({ details: { reason: "ALREADY_LINKED" } });
    const comoProfe = await profesor();
    expect(await rechazo(canjear(comoProfe, code))).toMatchObject({ details: { reason: "NOT_ELIGIBLE" } });
    const sinConsent = await alumno({ consentStatus: "revoked" });
    expect(await rechazo(canjear(sinConsent, code))).toMatchObject({ details: { reason: "NOT_ELIGIBLE" } });
    const borrando = await alumno({ deletion: { requestedAt: Timestamp.now() } });
    expect(await rechazo(canjear(borrando, code))).toMatchObject({ details: { reason: "NOT_ELIGIBLE" } });
    expect((await codeDoc(await idDe(t)))?.usedBy).toBeNull();
    expect((await userDoc(t)).studentCount).toBe(0);
  });
});

describe("redeemTeacherCode - concurrencia (A3a.4)", () => {
  test("dos alumnos con el profesor en 29: exactamente uno entra", async () => {
    const t = await profesor({ studentCount: 29 });
    const [a, b] = [await alumno(), await alumno()];
    const [ca, cb] = [await nuevoCodigo(t), await nuevoCodigo(t)];
    const res = await Promise.all([rechazo(canjear(a, ca)), rechazo(canjear(b, cb))]);
    expect(res.filter((r) => r === null)).toHaveLength(1);
    expect(res.find((r) => r !== null)).toMatchObject({ details: { reason: "TEACHER_FULL" } });
    expect((await userDoc(t)).studentCount).toBe(30);
  });

  test("el mismo alumno con dos codigos a la vez: solo uno prospera", async () => {
    const t1 = await profesor();
    const t2 = await profesor();
    const s = await alumno();
    const [c1, c2] = [await nuevoCodigo(t1), await nuevoCodigo(t2)];
    const res = await Promise.all([rechazo(canjear(s, c1)), rechazo(canjear(s, c2))]);
    expect(res.filter((r) => r === null)).toHaveLength(1);
    expect(res.find((r) => r !== null)).toMatchObject({ details: { reason: "ALREADY_LINKED" } });
    expect((await userDoc(s)).teacherCount).toBe(1);
    expect((await db.collection("teacherLinks").where("studentUid", "==", s).get()).size).toBe(1);
    expect((await userDoc(t1)).studentCount + (await userDoc(t2)).studentCount).toBe(1);
  });
});

describe("redeemTeacherCode - limite de fallos (A3a.5)", () => {
  test("tras 5 fallos en 1 h el siguiente intento es resource-exhausted con retryAfterSeconds, aun con codigo valido", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    for (let i = 0; i < 5; i++) {
      expect(await rechazo(canjear(s, CODIGO_AJENO))).toMatchObject({ code: "not-found" });
    }
    ahora = new Date(ahora.getTime() + 60_000);
    const e = await rechazo(canjear(s, code));
    expect(e).toMatchObject({ code: "resource-exhausted", details: { reason: "RATE_LIMITED", retryAfterSeconds: 3540 } });
    expect(await linkDoc(t, s)).toBeUndefined();
    ahora = new Date(ahora.getTime() + 3_600_000);
    await expect(canjear(s, code)).resolves.toMatchObject({ linked: true });
  });

  test("el exito no suma, y los fallos antiguos salen de la ventana", async () => {
    const t = await profesor();
    const s = await alumno();
    for (let i = 0; i < 4; i++) await rechazo(canjear(s, CODIGO_AJENO));
    await canjear(s, await nuevoCodigo(t));
    expect(await fallos(s)).toBe(4);
    const s2 = await alumno();
    for (let i = 0; i < 5; i++) await rechazo(canjear(s2, CODIGO_AJENO));
    ahora = new Date(ahora.getTime() + 3_600_001);
    expect(await rechazo(canjear(s2, CODIGO_AJENO))).toMatchObject({ code: "not-found" });
    expect(await fallos(s2)).toBe(1);
  });

  test("los logs solo llevan uidHash: ni uid, ni email, ni nombres, ni codigos", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    await rechazo(canjear(s, CODIGO_AJENO));
    await canjear(s, code);
    const volcado = JSON.stringify(logs);
    expect(logs.length).toBeGreaterThan(0);
    for (const prohibido of [s, t, "@example.com", "Alum Bo", "Profe Ana", code, CODIGO_AJENO]) {
      expect(volcado).not.toContain(prohibido);
    }
    expect(logs.every((l) => typeof l.data.uidHash === "string")).toBe(true);
  });
});

describe("redeemTeacherCode - revision fresca A3a", () => {
  test("usuario sin perfil: respuesta identica con codigo valido e invalido, 8 veces, sin oraculo", async () => {
    const t = await profesor();
    const valido = await nuevoCodigo(t);
    const { uid } = await auth.createUser({ email: `np-${Date.now()}@example.com`, emailVerified: true });
    const respuestas = [];
    for (let i = 0; i < 8; i++) respuestas.push(await rechazo(canjear(uid, i % 2 ? valido : CODIGO_AJENO)));
    for (const r of respuestas) expect(r).toMatchObject({ code: "permission-denied", details: { reason: "NOT_ELIGIBLE" }, message: respuestas[0]?.message });
    expect((await codeDoc(await idDe(t)))?.usedBy).toBeNull();
  });

  test("alumno ya vinculado con OTRO codigo: ALREADY_LINKED igual para valido e inexistente, sin fallos", async () => {
    const [t, t2] = [await profesor(), await profesor()];
    const s = await alumno();
    await canjear(s, await nuevoCodigo(t));
    const valido = await nuevoCodigo(t2);
    for (const c of [valido, CODIGO_AJENO]) {
      expect(await rechazo(canjear(s, c))).toMatchObject({ details: { reason: "ALREADY_LINKED" } });
    }
    expect(await fallos(s)).toBe(0);
  });

  test("rafaga paralela de 12 intentos invalidos: como mucho 5 NOT_FOUND y el resto resource-exhausted", async () => {
    const s = await alumno();
    const res = await Promise.all(Array.from({ length: 12 }, () => rechazo(canjear(s, CODIGO_AJENO))));
    const reasons = res.map((r) => r?.code);
    expect(reasons.filter((c) => c === "not-found").length).toBeLessThanOrEqual(5);
    expect(reasons.filter((c) => c === "not-found").length + reasons.filter((c) => c === "resource-exhausted").length).toBe(12);
    expect(await fallos(s)).toBeLessThanOrEqual(5);
  });

  test("codigo con expiresAt corrupto: NOT_FOUND contado, no internal", async () => {
    const s = await alumno();
    await db.collection("teacherCodes").doc(hashCode(PEPPER, "ZZZZ2222")).set({ teacherUid: "x", expiresAt: "ayer", usedBy: null, usedAt: null, revokedAt: null });
    expect(await rechazo(canjear(s, "ZZZZ2222"))).toMatchObject({ code: "not-found", details: { reason: "CODE_NOT_FOUND" } });
    expect(await fallos(s)).toBe(1);
  });

  test("si syncClaims falla tras el commit, el error sube y el reintento recupera claims sin duplicar contadores", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    let fallar = true;
    const authRoto = Object.create(auth) as typeof auth;
    authRoto.setCustomUserClaims = async (u, c) => {
      if (fallar) { fallar = false; throw new Error("boom"); }
      return auth.setCustomUserClaims(u, c);
    };
    await expect(redeemTeacherCodeHandler({ ...deps(), auth: authRoto }, s, { code, ackVersion: LINK_NOTICE_VERSION })).rejects.toThrow("boom");
    expect((await auth.getUser(s)).customClaims).toMatchObject({ role: "independent" });
    await expect(canjear(s, code)).resolves.toEqual({ linked: true, teacherUid: t });
    expect((await auth.getUser(s)).customClaims).toMatchObject({ role: "student" });
    expect((await userDoc(t)).studentCount).toBe(1);
    expect((await userDoc(s)).teacherCount).toBe(1);
  });

  test("el reintento idempotente no pasa por el limite de fallos", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    await canjear(s, code);
    await db.collection("users").doc(s).update({ "linkRedeem.failures": Array.from({ length: 5 }, () => Timestamp.fromMillis(ahora.getTime())) });
    await expect(canjear(s, code)).resolves.toMatchObject({ linked: true });
  });

  test("repetir el codigo tras desvincular (vinculo borrado, contadores a 0): NOT_FOUND", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    await canjear(s, code);
    await db.collection("teacherLinks").doc(`${t}_${s}`).delete();
    await db.collection("users").doc(t).update({ studentCount: 0 });
    await db.collection("users").doc(s).update({ teacherCount: 0, role: "independent" });
    expect(await rechazo(canjear(s, code))).toMatchObject({ code: "not-found" });
  });

  test("repetir el codigo tras desvincular de verdad (removeLink): NOT_FOUND y no revincula", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    await canjear(s, code);
    await removeLink({ ...deps(), store: bucketStore(getStorage(app).bucket(`${project}.appspot.com`)) }, t, s);
    expect(await rechazo(canjear(s, code))).toMatchObject({ code: "not-found" });
    expect(await linkDoc(t, s)).toBeUndefined();
    expect(await userDoc(s)).toMatchObject({ teacherCount: 0, role: "independent" });
    expect((await userDoc(t)).studentCount).toBe(0);
  });

  test("repetir el codigo tras perder el profesor su rol (vinculo vigente): sigue siendo exito", async () => {
    const t = await profesor();
    const s = await alumno();
    const code = await nuevoCodigo(t);
    await canjear(s, code);
    await db.collection("users").doc(t).update({ role: "independent" });
    await expect(canjear(s, code)).resolves.toEqual({ linked: true, teacherUid: t });
  });

  test("un profesor no puede canjear su propio codigo", async () => {
    const t = await profesor();
    expect(await rechazo(canjear(t, await nuevoCodigo(t)))).toMatchObject({ details: { reason: "NOT_ELIGIBLE" } });
  });
});
