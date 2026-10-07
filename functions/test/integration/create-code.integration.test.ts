import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { createTeacherCodeHandler, CodeDeps, revokeActiveCodes } from "../../src/teacher/code-handlers";
import { CODE_ALPHABET, hashCode } from "../../src/teacher/codes";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const PEPPER = "ci-fixed-non-secret-pepper-0123456789abcdef";
const HOY = new Date("2026-10-07T12:00:00Z");
const logs: Array<{ message: string; data: Record<string, unknown> }> = [];
const deps = (parche: Partial<CodeDeps> = {}): CodeDeps => ({
  db,
  pepper: PEPPER,
  clock: () => HOY,
  log: (message, data) => logs.push({ message, data }),
  ...parche,
});

export async function profesor(perfil: Record<string, unknown> = {}) {
  const { uid } = await auth.createUser({ email: `cc-${Date.now()}-${Math.random()}@example.com`, emailVerified: true });
  await db.collection("users").doc(uid).set({
    role: "teacher",
    birthDate: "1985-03-01",
    consentStatus: "granted",
    policyVersion: CURRENT_POLICY_VERSION,
    studentCount: 0,
    ...perfil,
  });
  return uid;
}
const codigos = async (uid: string) => (await db.collection("teacherCodes").where("teacherUid", "==", uid).get()).docs;
const rechazo = (p: Promise<unknown>) => p.then(() => null, (e: unknown) => e as { code: string; details?: { reason?: string } });

async function sembrar(uid: string, n: number, extra: Record<string, unknown> = {}, creado = HOY) {
  for (let i = 0; i < n; i++) {
    await db.collection("teacherCodes").doc(`sembrado-${uid}-${Math.random()}`).set({
      teacherUid: uid,
      createdAt: Timestamp.fromDate(creado),
      expiresAt: Timestamp.fromMillis(creado.getTime() + 7 * 86400_000),
      expireAt: Timestamp.fromMillis(creado.getTime() + 14 * 86400_000),
      usedBy: null,
      usedAt: null,
      revokedAt: null,
      codeHint: "ZZ",
      ...extra,
    });
  }
}

beforeEach(() => {
  logs.length = 0;
});

describe("createTeacherCode", () => {
  test("el profesor recibe el codigo en claro y el doc solo guarda el HMAC y codeHint", async () => {
    const uid = await profesor();
    const r = await createTeacherCodeHandler(deps(), uid);
    expect(r.code).toMatch(new RegExp(`^[${CODE_ALPHABET}]{8}$`));
    expect(r.id).toBe(hashCode(PEPPER, r.code));
    expect(r.codeHint).toBe(r.code.slice(-2));
    expect(r.expiresAt).toBe(HOY.getTime() + 7 * 86400_000);

    const docs = await codigos(uid);
    expect(docs).toHaveLength(1);
    expect(docs[0].id).toBe(r.id);
    const d = docs[0].data();
    expect(d).toMatchObject({ teacherUid: uid, usedBy: null, usedAt: null, revokedAt: null, codeHint: r.code.slice(-2) });
    expect(d.expiresAt.toMillis()).toBe(HOY.getTime() + 7 * 86400_000);
    expect(d.expireAt.toMillis()).toBe(HOY.getTime() + 14 * 86400_000);
    expect(d.createdAt.toMillis()).toBe(HOY.getTime());
    expect(JSON.stringify(d)).not.toContain(r.code);
  });

  test("el codigo en claro no aparece en los logs", async () => {
    const uid = await profesor();
    const r = await createTeacherCodeHandler(deps(), uid);
    const texto = JSON.stringify(logs);
    expect(texto).not.toContain(r.code);
    expect(texto).not.toContain(uid);
    expect(logs.length).toBeGreaterThan(0);
    expect(logs[0].data.uidHash).toEqual(expect.any(String));
  });

  test("usa el generador inyectado", async () => {
    const uid = await profesor();
    const r = await createTeacherCodeHandler(deps({ random: () => 0 }), uid);
    expect(r.code).toBe("AAAAAAAA");
  });

  test.each([
    ["independent", { role: "independent" }],
    ["student", { role: "student" }],
  ])("%s -> permission-denied sin crear docs", async (_n, perfil) => {
    const uid = await profesor(perfil);
    expect(await rechazo(createTeacherCodeHandler(deps(), uid))).toMatchObject({ code: "permission-denied" });
    expect(await codigos(uid)).toHaveLength(0);
  });

  test("sin perfil -> failed-precondition NO_PROFILE", async () => {
    const { uid } = await auth.createUser({ email: `cc-${Date.now()}-${Math.random()}@example.com` });
    expect(await rechazo(createTeacherCodeHandler(deps(), uid))).toMatchObject({ code: "failed-precondition", details: { reason: "NO_PROFILE" } });
  });

  test.each([
    ["consentimiento revocado", { consentStatus: "revoked" }],
    ["politica caducada", { policyVersion: CURRENT_POLICY_VERSION - 1 }],
    ["menor (birthDate)", { birthDate: "2015-01-01" }],
    ["sin birthDate", { birthDate: null }],
    ["borrado en curso", { deletion: { state: "in_progress" } }],
  ])("%s -> denegado sin crear docs", async (_n, perfil) => {
    const uid = await profesor(perfil);
    const e = await rechazo(createTeacherCodeHandler(deps(), uid));
    expect(e?.code).toMatch(/permission-denied|failed-precondition/);
    expect(await codigos(uid)).toHaveLength(0);
  });

  test("hasta 5 activos; el 6.o -> resource-exhausted", async () => {
    const uid = await profesor();
    for (let i = 0; i < 5; i++) await createTeacherCodeHandler(deps(), uid);
    expect(await rechazo(createTeacherCodeHandler(deps(), uid))).toMatchObject({ code: "resource-exhausted" });
    expect(await codigos(uid)).toHaveLength(5);
  });

  test("usados, revocados o caducados no cuentan como activos", async () => {
    const uid = await profesor();
    await sembrar(uid, 1, { usedBy: "alumno", usedAt: Timestamp.fromDate(HOY) });
    await sembrar(uid, 1, { revokedAt: Timestamp.fromDate(HOY) });
    const viejo = new Date(HOY.getTime() - 8 * 86400_000);
    await sembrar(uid, 2, {}, viejo); // caducados
    await sembrar(uid, 4);
    await expect(createTeacherCodeHandler(deps(), uid)).resolves.toBeDefined();
    expect(await rechazo(createTeacherCodeHandler(deps(), uid))).toMatchObject({ code: "resource-exhausted" });
  });

  test("un codigo con usedAt y usedBy nulo cuenta como usado (no activo)", async () => {
    const uid = await profesor();
    await sembrar(uid, 5, { usedAt: Timestamp.fromDate(HOY) }, new Date(HOY.getTime() - 25 * 3600_000));
    await expect(createTeacherCodeHandler(deps(), uid)).resolves.toBeDefined();
  });

  test("tope de 20 codigos en 24 h aunque no haya activos", async () => {
    const uid = await profesor();
    await sembrar(uid, 20, { revokedAt: Timestamp.fromDate(HOY) }, new Date(HOY.getTime() - 3600_000));
    expect(await rechazo(createTeacherCodeHandler(deps(), uid))).toMatchObject({ code: "resource-exhausted" });
  });

  test("codigos creados hace mas de 24 h no cuentan para el tope diario", async () => {
    const uid = await profesor();
    await sembrar(uid, 20, { revokedAt: Timestamp.fromDate(HOY) }, new Date(HOY.getTime() - 25 * 3600_000));
    await expect(createTeacherCodeHandler(deps(), uid)).resolves.toBeDefined();
  });

  test("peticiones concurrentes nunca superan 5 activos", async () => {
    const uid = await profesor();
    const r = await Promise.allSettled(Array.from({ length: 8 }, () => createTeacherCodeHandler(deps(), uid)));
    expect(r.filter((x) => x.status === "fulfilled")).toHaveLength(5);
    expect(await codigos(uid)).toHaveLength(5);
  });

  test("el tope de 24 h no se supera con creaciones en paralelo y revocaciones intercaladas", async () => {
    const uid = await profesor();
    let creados = 0;
    for (let ronda = 0; ronda < 6; ronda++) {
      const r = await Promise.allSettled(Array.from({ length: 8 }, () => createTeacherCodeHandler(deps(), uid)));
      creados += r.filter((x) => x.status === "fulfilled").length;
      await revokeActiveCodes(deps(), uid);
    }
    expect(creados).toBeLessThanOrEqual(20);
    expect(creados).toBe(20);
    expect(await codigos(uid)).toHaveLength(20);
  });

  test("pepper corto -> error sin crear docs", async () => {
    const uid = await profesor();
    expect(await rechazo(createTeacherCodeHandler(deps({ pepper: "corto" }), uid))).toMatchObject({ code: "internal", details: { reason: "SERVER_MISCONFIGURED" } });
    expect(await codigos(uid)).toHaveLength(0);
  });
});
