import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { syncClaims } from "../../src/identity/claims";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

async function nuevoUsuario() {
  return auth.createUser({ email: `claims-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
}

test("lee users/{uid} y fija los claims con setCustomUserClaims", async () => {
  const { uid } = await nuevoUsuario();
  await db.collection("users").doc(uid).set({
    role: "independent",
    consentStatus: "granted",
    policyVersion: CURRENT_POLICY_VERSION,
    birthDate: "2000-01-01",
  });
  await syncClaims({ db, auth }, uid);
  const user = await auth.getUser(uid);
  expect(user.customClaims).toEqual({ role: "independent", consentOk: true });
});

test("es idempotente: no reescribe si los claims ya coinciden", async () => {
  const { uid } = await nuevoUsuario();
  await db.collection("users").doc(uid).set({ role: "independent", consentStatus: "pending", policyVersion: 0 });
  const espia = jest.spyOn(auth, "setCustomUserClaims");
  try {
    await syncClaims({ db, auth }, uid);
    await syncClaims({ db, auth }, uid);
    expect(espia).toHaveBeenCalledTimes(1);
    expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
  } finally {
    espia.mockRestore();
  }
});

test("un cambio en el documento se propaga a los claims", async () => {
  const { uid } = await nuevoUsuario();
  const ref = db.collection("users").doc(uid);
  await ref.set({ role: "independent", consentStatus: "granted", policyVersion: CURRENT_POLICY_VERSION });
  await syncClaims({ db, auth }, uid);
  await ref.update({ deletion: { state: "in_progress" } });
  await syncClaims({ db, auth }, uid);
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
});

test("un usuario sin documento no falla ni recibe claims", async () => {
  const { uid } = await nuevoUsuario();
  await expect(syncClaims({ db, auth }, uid)).resolves.toBeUndefined();
  expect((await auth.getUser(uid)).customClaims).toBeUndefined();
});
