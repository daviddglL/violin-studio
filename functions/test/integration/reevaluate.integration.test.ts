import functionsTest from "firebase-functions-test";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { reevaluateConsent } from "../../src/consent/reevaluate";
import { identityConfig } from "../../src/index";
import { identityConfigHandler } from "../../src/profile/identity-config";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const fft = functionsTest();
afterAll(() => fft.cleanup());

async function granted(version: number, extra: Record<string, unknown> = {}) {
  const { uid } = await auth.createUser({ email: `re-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    role: "independent", isMinor: false, consentStatus: "granted", policyVersion: version,
    birthDate: "1990-01-01", displayName: "Ana", ...extra,
  });
  await db.collection("users").doc(uid).collection("consents").doc(`terms_v${version}_self`).set({ type: "terms", version, grantedBy: "self" });
  await auth.setCustomUserClaims(uid, { role: "independent", consentOk: true });
  return uid;
}
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;

test("identityConfig con versión vigente subida: granted v1 -> pending, consentOk=false, sin migrar datos (C4)", async () => {
  const uid = await granted(1);
  const cfg = await identityConfigHandler({ auth: { uid } }, { db, auth, currentVersion: 2 });
  expect(cfg.policyVersion).toBe(2);
  const d = await perfil(uid);
  expect(d).toMatchObject({ consentStatus: "pending", policyVersion: 1, birthDate: "1990-01-01", displayName: "Ana", isMinor: false });
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
  expect((await db.collection("users").doc(uid).collection("consents").get()).size).toBe(1);
});

test("menor granted con versión vieja también pasa a pending (D3)", async () => {
  const uid = await granted(1, { isMinor: true, birthDate: "2015-01-01" });
  await reevaluateConsent({ db, auth, currentVersion: 2 }, uid);
  expect(await perfil(uid)).toMatchObject({ consentStatus: "pending", isMinor: true });
  expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
});

test("versión vigente: sin cambios y claim intacto", async () => {
  const uid = await granted(2);
  const antes = await perfil(uid);
  await reevaluateConsent({ db, auth, currentVersion: 2 }, uid);
  expect(await perfil(uid)).toEqual(antes);
  expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(true);
});

test("R-e: decide el doc y no el claim obsoleto; corrige un claim desfasado", async () => {
  const uid = await granted(1);
  await reevaluateConsent({ db, auth, currentVersion: 2 }, uid);
  expect((await perfil(uid)).consentStatus).toBe("pending");
  // claim obsoleto a true con doc pending: la siguiente reevaluación lo cura
  await auth.setCustomUserClaims(uid, { role: "independent", consentOk: true });
  await reevaluateConsent({ db, auth, currentVersion: 2 }, uid);
  expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
});

test("usuario sin perfil: no-op sin error", async () => {
  const { uid } = await auth.createUser({ email: `re-np-${Date.now()}@example.com`, password: "Passw0rd!x" });
  await expect(reevaluateConsent({ db, auth, currentVersion: 2 }, uid)).resolves.toBeUndefined();
  expect((await db.collection("users").doc(uid).get()).exists).toBe(false);
});

test("el callable identityConfig reevalúa (granted con versión anterior a la vigente -> pending)", async () => {
  const uid = await granted(0);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const res: any = await fft.wrap(identityConfig)({ data: {}, auth: { uid, token: { email_verified: true } } } as never);
  expect(res).toMatchObject({ policyVersion: 1 });
  expect((await perfil(uid)).consentStatus).toBe("pending");
  expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
});
