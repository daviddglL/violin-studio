import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import {
  grantTeacher,
  listTeacherRequests,
  rejectTeacherRequest,
  revokeTeacher,
  TeacherRoleDeps,
  TeacherRoleError,
} from "../../src/admin/teacher-role";
import { bucketStore } from "../../src/recordings/finalize";
import { requestTeacherRoleHandler } from "../../src/teacher/teacher-request";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const HOY = new Date("2026-10-22T12:00:00Z");
const logs: Array<{ message: string; data: Record<string, unknown> }> = [];
const roleDeps: TeacherRoleDeps = {
  db,
  auth,
  clock: () => HOY,
  store: bucketStore(getStorage(app).bucket(`${project}.appspot.com`)),
  log: (message, data) => logs.push({ message, data }),
};
const requestDeps = { db, clock: () => HOY, log: roleDeps.log };

async function adulto(perfil: Record<string, unknown> = {}) {
  const email = `treq-${Date.now()}-${Math.random()}@example.com`;
  const { uid } = await auth.createUser({ email, password: "Passw0rd!x", emailVerified: true });
  await db.collection("users").doc(uid).set({
    role: "independent",
    displayName: "Nombre Secreto",
    birthDate: "1990-04-02",
    consentStatus: "granted",
    policyVersion: CURRENT_POLICY_VERSION,
    ...perfil,
  });
  return uid;
}
const solicitud = async (uid: string) => (await db.collection("users").doc(uid).get()).get("teacherRequest");
const error = async (p: Promise<unknown>) => p.then(() => null, (e: unknown) => e as { details?: unknown });

beforeEach(() => {
  logs.length = 0;
});

describe("requestTeacherRole", () => {
  test("un adulto vigente queda pendiente y repetirlo no reescribe la fecha", async () => {
    const uid = await adulto();
    expect(await requestTeacherRoleHandler(requestDeps, uid, true)).toEqual({ status: "pending" });
    const primera = await solicitud(uid);
    expect(primera.status).toBe("pending");
    expect(primera.requestedAt).toBeInstanceOf(Timestamp);
    expect(await requestTeacherRoleHandler(requestDeps, uid, true)).toEqual({ status: "pending" });
    expect((await solicitud(uid)).requestedAt.isEqual(primera.requestedAt)).toBe(true);
    expect((await db.collection("users").doc(uid).get()).get("role")).toBe("independent");
  });

  test("un menor no puede pedirlo y no se escribe nada", async () => {
    const uid = await adulto({ birthDate: "2012-01-01" });
    const e = await error(requestTeacherRoleHandler(requestDeps, uid, true));
    expect(e?.details).toMatchObject({ reason: "NOT_ELIGIBLE", deny: "NOT_ADULT" });
    expect(await solicitud(uid)).toBeUndefined();
  });

  test("los logs no llevan el uid", async () => {
    const uid = await adulto();
    await requestTeacherRoleHandler(requestDeps, uid, true);
    expect(JSON.stringify(logs)).not.toContain(uid);
  });
});

describe("decision del administrador", () => {
  test("listar, aprobar con grantTeacher y la solicitud queda aprobada", async () => {
    const uid = await adulto();
    await requestTeacherRoleHandler(requestDeps, uid, true);
    expect((await listTeacherRequests(roleDeps, 500)).map((r) => r.uid)).toContain(uid);
    await grantTeacher(roleDeps, uid);
    const req = await solicitud(uid);
    expect(req.status).toBe("approved");
    expect(req.decidedAt).toBeInstanceOf(Timestamp);
    expect((await listTeacherRequests(roleDeps, 500)).map((r) => r.uid)).not.toContain(uid);
    expect(await requestTeacherRoleHandler(requestDeps, uid, true)).toEqual({ status: "approved" });
  });

  test("rechazar sella la decision y bloquea volver a pedirlo", async () => {
    const uid = await adulto();
    await requestTeacherRoleHandler(requestDeps, uid, true);
    await rejectTeacherRequest(roleDeps, uid);
    expect((await solicitud(uid)).status).toBe("rejected");
    expect(await requestTeacherRoleHandler(requestDeps, uid, true)).toEqual({ status: "rejected" });
  });

  test("rechazar sin solicitud pendiente falla sin escribir", async () => {
    const uid = await adulto();
    await expect(rejectTeacherRequest(roleDeps, uid)).rejects.toEqual(new TeacherRoleError("NO_PENDING_REQUEST"));
    expect(await solicitud(uid)).toBeUndefined();
  });

  test("un alta directa sin solicitud no crea ninguna y la baja la borra", async () => {
    const uid = await adulto();
    await grantTeacher(roleDeps, uid);
    expect(await solicitud(uid)).toBeUndefined();
    await requestTeacherRoleHandler(requestDeps, uid, true);
    await revokeTeacher(roleDeps, uid);
    expect(await solicitud(uid)).toBeUndefined();
  });
});
