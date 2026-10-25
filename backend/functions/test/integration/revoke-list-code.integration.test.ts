import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import {
  CodeDeps,
  createTeacherCodeHandler,
  listTeacherCodesHandler,
  revokeActiveCodes,
  revokeTeacherCodeHandler,
} from "../../src/teacher/code-handlers";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const PEPPER = "ci-fixed-non-secret-pepper-0123456789abcdef";
let ahora = new Date("2026-10-07T12:00:00Z");
const deps = (): CodeDeps => ({ db, pepper: PEPPER, clock: () => ahora, log: () => undefined });
beforeEach(() => {
  ahora = new Date("2026-10-07T12:00:00Z");
});

async function profesor() {
  const { uid } = await auth.createUser({ email: `rl-${Date.now()}-${Math.random()}@example.com`, emailVerified: true });
  await db.collection("users").doc(uid).set({
    role: "teacher",
    birthDate: "1985-03-01",
    consentStatus: "granted",
    policyVersion: CURRENT_POLICY_VERSION,
    studentCount: 0,
  });
  return uid;
}
const doc = async (id: string) => (await db.collection("teacherCodes").doc(id).get()).data();
const rechazo = (p: Promise<unknown>) => p.then(() => null, (e: unknown) => e as { code: string; details?: { reason?: string } });

describe("revokeTeacherCode", () => {
  test("el dueno revoca y el doc queda con revokedAt", async () => {
    const uid = await profesor();
    const c = await createTeacherCodeHandler(deps(), uid);
    await expect(revokeTeacherCodeHandler(deps(), uid, { id: c.id })).resolves.toEqual({ revoked: true });
    expect((await doc(c.id))?.revokedAt.toMillis()).toBe(ahora.getTime());
  });

  test("idempotente: la segunda llamada no cambia revokedAt", async () => {
    const uid = await profesor();
    const c = await createTeacherCodeHandler(deps(), uid);
    await revokeTeacherCodeHandler(deps(), uid, { id: c.id });
    const antes = (await doc(c.id))?.revokedAt.toMillis();
    ahora = new Date(ahora.getTime() + 60_000);
    await expect(revokeTeacherCodeHandler(deps(), uid, { id: c.id })).resolves.toEqual({ revoked: false });
    expect((await doc(c.id))?.revokedAt.toMillis()).toBe(antes);
  });

  test("un codigo con usedAt y usedBy nulo se trata como usado", async () => {
    const uid = await profesor();
    const c = await createTeacherCodeHandler(deps(), uid);
    await db.collection("teacherCodes").doc(c.id).update({ usedAt: Timestamp.fromDate(ahora) });
    await expect(revokeTeacherCodeHandler(deps(), uid, { id: c.id })).resolves.toEqual({ revoked: false });
    expect((await doc(c.id))?.revokedAt).toBeNull();
    expect((await listTeacherCodesHandler(deps(), uid)).codes).toHaveLength(0);
  });

  test("codigo ajeno o inexistente -> mismo not-found CODE_NOT_FOUND sin efectos", async () => {
    const a = await profesor();
    const b = await profesor();
    const c = await createTeacherCodeHandler(deps(), a);
    expect(await rechazo(revokeTeacherCodeHandler(deps(), b, { id: c.id }))).toMatchObject({ code: "not-found", details: { reason: "CODE_NOT_FOUND" } });
    expect((await doc(c.id))?.revokedAt).toBeNull();
    expect(await rechazo(revokeTeacherCodeHandler(deps(), b, { id: "a".repeat(64) }))).toMatchObject({ code: "not-found", details: { reason: "CODE_NOT_FOUND" } });
  });

  test("un codigo ya canjeado no se modifica", async () => {
    const uid = await profesor();
    const c = await createTeacherCodeHandler(deps(), uid);
    await db.collection("teacherCodes").doc(c.id).update({ usedBy: "alumno", usedAt: Timestamp.fromDate(ahora) });
    await expect(revokeTeacherCodeHandler(deps(), uid, { id: c.id })).resolves.toEqual({ revoked: false });
    expect((await doc(c.id))?.revokedAt).toBeNull();
  });

  test.each([[{}], [{ id: 5 }], [{ id: "corto" }], [{ id: "G".repeat(64) }], [{ id: "A".repeat(64) }], [{ id: "a".repeat(63) }], [{ id: "a".repeat(65) }], [{ id: null }], [null], [undefined], ["x"], [[]]])("payload invalido %j -> invalid-argument", async (data) => {
    const uid = await profesor();
    expect(await rechazo(revokeTeacherCodeHandler(deps(), uid, data))).toMatchObject({ code: "invalid-argument" });
  });

  test("un codigo revocado libera cupo", async () => {
    const uid = await profesor();
    const ids: string[] = [];
    for (let i = 0; i < 5; i++) ids.push((await createTeacherCodeHandler(deps(), uid)).id);
    expect(await rechazo(createTeacherCodeHandler(deps(), uid))).toMatchObject({ code: "resource-exhausted" });
    await revokeTeacherCodeHandler(deps(), uid, { id: ids[0] });
    await expect(createTeacherCodeHandler(deps(), uid)).resolves.toBeDefined();
  });
});

describe("listTeacherCodes", () => {
  test("lista solo activos y propios, sin hash ni codigo en claro, ordenados por caducidad", async () => {
    const uid = await profesor();
    const otro = await profesor();
    const c1 = await createTeacherCodeHandler(deps(), uid);
    ahora = new Date(ahora.getTime() + 1000);
    const c2 = await createTeacherCodeHandler(deps(), uid);
    const c3 = await createTeacherCodeHandler(deps(), uid);
    await createTeacherCodeHandler(deps(), otro);
    await revokeTeacherCodeHandler(deps(), uid, { id: c3.id });

    const { codes } = await listTeacherCodesHandler(deps(), uid);
    expect(codes).toEqual([
      { id: c1.id, codeHint: c1.codeHint, expiresAt: c1.expiresAt },
      { id: c2.id, codeHint: c2.codeHint, expiresAt: c2.expiresAt },
    ]);
    const texto = JSON.stringify(codes);
    expect(texto).not.toContain(c1.code);
    expect(Object.keys(codes[0]).sort()).toEqual(["codeHint", "expiresAt", "id"]);
  });

  test("excluye usados y caducados", async () => {
    const uid = await profesor();
    const usado = await createTeacherCodeHandler(deps(), uid);
    await db.collection("teacherCodes").doc(usado.id).update({ usedBy: "alumno", usedAt: Timestamp.fromDate(ahora) });
    await createTeacherCodeHandler(deps(), uid);
    expect((await listTeacherCodesHandler(deps(), uid)).codes).toHaveLength(1);
    ahora = new Date(ahora.getTime() + 8 * 86400_000);
    expect((await listTeacherCodesHandler(deps(), uid)).codes).toHaveLength(0);
  });

  test.each([
    ["consentimiento revocado", { consentStatus: "revoked" }],
    ["menor", { birthDate: "2015-01-01" }],
    ["borrado en curso", { deletion: { state: "in_progress" } }],
  ])("%s -> denegado", async (_n, parche) => {
    const uid = await profesor();
    await db.collection("users").doc(uid).update(parche);
    const e = await rechazo(listTeacherCodesHandler(deps(), uid));
    expect(e?.code).toMatch(/permission-denied|failed-precondition/);
  });

  test("no profesor -> permission-denied", async () => {
    const { uid } = await auth.createUser({ email: `rl-${Date.now()}-${Math.random()}@example.com` });
    await db.collection("users").doc(uid).set({ role: "independent", birthDate: "1990-01-01", consentStatus: "granted", policyVersion: CURRENT_POLICY_VERSION });
    expect(await rechazo(listTeacherCodesHandler(deps(), uid))).toMatchObject({ code: "permission-denied" });
  });
});

describe("revokeActiveCodes (hook de revoke-teacher)", () => {
  test("revoca solo los activos del profesor, devuelve cuantos y es idempotente", async () => {
    const uid = await profesor();
    const otro = await profesor();
    for (let i = 0; i < 3; i++) await createTeacherCodeHandler(deps(), uid);
    const ajeno = await createTeacherCodeHandler(deps(), otro);
    expect(await revokeActiveCodes(deps(), uid)).toBe(3);
    expect(await revokeActiveCodes(deps(), uid)).toBe(0);
    expect((await listTeacherCodesHandler(deps(), uid)).codes).toHaveLength(0);
    expect((await doc(ajeno.id))?.revokedAt).toBeNull();
  });
});
