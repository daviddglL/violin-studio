import functionsTest from "firebase-functions-test";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { recordConsent } from "../../src/index";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const fft = functionsTest();
afterAll(() => fft.cleanup());

const llamar = recordConsent && fft.wrap(recordConsent);
async function adulto() {
  const { uid } = await auth.createUser({ email: `rcc-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({ role: "independent", isMinor: false, consentStatus: "pending", policyVersion: null });
  return uid;
}
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const req = (uid: string, token: Record<string, unknown>, data: unknown = { policyVersion: 1 }): any => ({ data, auth: { uid, token } });

test("sin sesión -> unauthenticated", async () => {
  await expect(llamar({ data: { policyVersion: 1 } } as never)).rejects.toMatchObject({ code: "unauthenticated" });
});

test("email no verificado -> rechazo y cero escrituras (A2)", async () => {
  const uid = await adulto();
  await expect(llamar(req(uid, { email_verified: false }))).rejects.toMatchObject({
    code: "failed-precondition", details: { reason: "EMAIL_NOT_VERIFIED" },
  });
  expect((await db.collection("users").doc(uid).collection("consents").get()).size).toBe(0);
  expect((await db.collection("users").doc(uid).get()).data()).toMatchObject({ consentStatus: "pending", policyVersion: null });
  expect((await auth.getUser(uid)).customClaims).toBeUndefined();
});

test("usuario verificado: granted y claim consentOk=true", async () => {
  const uid = await adulto();
  expect(await llamar(req(uid, { email_verified: true }))).toEqual({ consentStatus: "granted" });
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: true });
});

test("el cliente que envía otra versión no prevalece (C6)", async () => {
  const uid = await adulto();
  await expect(llamar(req(uid, { email_verified: true }, { policyVersion: 2 }))).rejects.toMatchObject({ code: "invalid-argument" });
  await expect(llamar(req(uid, { email_verified: true }, { policyVersion: 0 }))).rejects.toMatchObject({ code: "invalid-argument" });
  expect((await db.collection("users").doc(uid).get()).data()?.consentStatus).toBe("pending");
});
