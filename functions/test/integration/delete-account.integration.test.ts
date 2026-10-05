import functionsTest from "firebase-functions-test";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { COLLECTIONS } from "../../src/common/collections";
import { deleteAccount } from "../../src/index";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const bucket = getStorage(app).bucket();
const fft = functionsTest();
afterAll(() => fft.cleanup());
const llamar = deleteAccount && fft.wrap(deleteAccount);

const ahora = () => Math.floor(Date.now() / 1000);
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const req = (uid: string, token: Record<string, unknown>): any => ({ data: {}, auth: { uid, token } });

async function usuario(perfil: Record<string, unknown> | null) {
  const { uid } = await auth.createUser({ email: `da-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  if (perfil) {
    const ref = db.collection(COLLECTIONS.users).doc(uid);
    await ref.set({ role: "independent", isMinor: false, birthDate: "1990-01-01", ...perfil });
    await ref.collection(COLLECTIONS.consents).doc("terms_v1_self_e0").set({ type: "terms" });
    for (const id of ["s1", "s2", "s3"]) await ref.collection(COLLECTIONS.practiceSessions).doc(id).set({ durationSec: 60 });
    await db.collection(COLLECTIONS.guardianRequests).doc(`r-${uid}`).set({ uid });
    await db.collection(COLLECTIONS.mail).doc(`m-${uid}`).set({ uid });
    await bucket.file(`users/${uid}/a.txt`).save("a");
  }
  return uid;
}

async function todoBorrado(uid: string) {
  await expect(auth.getUser(uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
  const ref = db.collection(COLLECTIONS.users).doc(uid);
  expect((await ref.get()).exists).toBe(false);
  expect((await ref.collection(COLLECTIONS.consents).get()).size).toBe(0);
  expect((await ref.collection(COLLECTIONS.practiceSessions).get()).size).toBe(0);
  expect((await db.collection(COLLECTIONS.guardianRequests).where("uid", "==", uid).get()).size).toBe(0);
  expect((await db.collection(COLLECTIONS.mail).where("uid", "==", uid).get()).size).toBe(0);
  expect((await bucket.getFiles({ prefix: `users/${uid}/` }))[0]).toHaveLength(0);
}

const casos: Array<[string, Record<string, unknown> | null, Record<string, unknown>]> = [
  ["token no verificado sin perfil", null, { email_verified: false }],
  ["pending", { consentStatus: "pending", policyVersion: null }, { email_verified: true }],
  ["parental_pending", { consentStatus: "parental_pending", isMinor: true, policyVersion: null }, { email_verified: true }],
  ["revoked", { consentStatus: "revoked", consentEpoch: 1, policyVersion: 1 }, { email_verified: true }],
  ["granted", { consentStatus: "granted", policyVersion: 1 }, { email_verified: true }],
];

test.each(casos)("D1: deleteAccount con reautenticación reciente borra todo en estado %s", async (_n, perfil, token) => {
  const uid = await usuario(perfil);
  const otro = await usuario({ consentStatus: "granted", policyVersion: 1 });
  expect(await llamar(req(uid, { ...token, auth_time: ahora() - 30 }))).toEqual({ deleted: true });
  await todoBorrado(uid);
  expect((await db.collection(COLLECTIONS.users).doc(otro).get()).exists).toBe(true);
  expect((await auth.getUser(otro)).uid).toBe(otro);
});

test("auth_time antiguo -> REAUTH_REQUIRED y no se borra nada", async () => {
  const uid = await usuario({ consentStatus: "granted", policyVersion: 1 });
  await expect(llamar(req(uid, { email_verified: true, auth_time: ahora() - 400 }))).rejects.toMatchObject({
    code: "failed-precondition",
    details: { reason: "REAUTH_REQUIRED" },
  });
  expect((await auth.getUser(uid)).uid).toBe(uid);
  expect((await db.collection(COLLECTIONS.users).doc(uid).get()).exists).toBe(true);
});
