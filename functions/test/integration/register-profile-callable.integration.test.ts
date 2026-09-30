import functionsTest from "firebase-functions-test";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { registerProfile } from "../../src/index";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const fft = functionsTest();
afterAll(() => fft.cleanup());

const llamar = registerProfile && fft.wrap(registerProfile);
const payload = { birthDate: "1996-05-10", displayName: "Ana Secreta", instrument: "violin", locale: "es-ES" };

async function nuevoUsuario() {
  return auth.createUser({ email: `rpc-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
}
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const req = (uid: string, token: Record<string, unknown>, data: unknown = payload): any => ({ data, auth: { uid, token } });

test("sin sesión -> unauthenticated", async () => {
  await expect(llamar({ data: payload } as never)).rejects.toMatchObject({ code: "unauthenticated" });
});

test("token con email no verificado -> rechazo sin escrituras (A2)", async () => {
  const { uid } = await nuevoUsuario();
  await expect(llamar(req(uid, { email_verified: false }))).rejects.toMatchObject({
    code: "failed-precondition",
    details: { reason: "EMAIL_NOT_VERIFIED" },
  });
  expect((await db.collection("users").doc(uid).get()).exists).toBe(false);
  expect((await auth.getUser(uid)).customClaims).toBeUndefined();
});

test("token verificado (p. ej. Google) -> procesa igual y fija los claims (A7,P5)", async () => {
  const { uid } = await nuevoUsuario();
  const res = await llamar(req(uid, { email_verified: true, firebase: { sign_in_provider: "google.com" } }));
  expect(res).toEqual({ isMinor: false, consentStatus: "pending", requiredPolicyVersion: 1 });
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
});
