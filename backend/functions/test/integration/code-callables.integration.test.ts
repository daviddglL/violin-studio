import functionsTest from "firebase-functions-test";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { createTeacherCode, listTeacherCodes, revokeTeacherCode } from "../../src/index";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const fft = functionsTest();
afterAll(() => fft.cleanup());

// App Check lo aplica la plataforma (`enforceAppCheck`, ver function-options.test): firebase-functions-test
// no lo simula. Aqui se cubre la sesion y el email verificado, que si ejecuta el wrapper.
const llamadas = [
  ["createTeacherCode", fft.wrap(createTeacherCode)],
  ["revokeTeacherCode", fft.wrap(revokeTeacherCode)],
  ["listTeacherCodes", fft.wrap(listTeacherCodes)],
] as const;

describe.each(llamadas)("%s", (_n, llamar) => {
  test("sin sesion -> unauthenticated", async () => {
    await expect(llamar({ data: {} } as never)).rejects.toMatchObject({ code: "unauthenticated" });
  });
  test("email sin verificar -> failed-precondition EMAIL_NOT_VERIFIED", async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const req: any = { data: {}, auth: { uid: "u", token: { email_verified: false } } };
    await expect(llamar(req)).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "EMAIL_NOT_VERIFIED" } });
  });
});

test("un usuario verificado que no es profesor recibe permission-denied al listar", async () => {
  const { uid } = await auth.createUser({ email: `cw-${Date.now()}-${Math.random()}@example.com` });
  await db.collection("users").doc(uid).set({ role: "independent", birthDate: "1990-01-01", consentStatus: "granted", policyVersion: 1 });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const req: any = { data: {}, auth: { uid, token: { email_verified: true } } };
  await expect(fft.wrap(listTeacherCodes)(req)).rejects.toMatchObject({ code: "permission-denied" });
});
