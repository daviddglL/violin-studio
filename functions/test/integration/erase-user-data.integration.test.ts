import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { COLLECTIONS } from "../../src/common/collections";
import { eraseUserData } from "../../src/erasure/erase-user-data";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const bucket = getStorage(app).bucket();
const deps = { db, auth, bucket };

async function usuarioCompleto() {
  const { uid } = await auth.createUser({ email: `er-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  const users = db.collection(COLLECTIONS.users).doc(uid);
  await users.set({ role: "independent", consentStatus: "granted", policyVersion: 1, isMinor: false });
  await users.collection(COLLECTIONS.consents).doc("terms_v1_self_e0").set({ type: "terms" });
  await users.collection("otraSub").doc("x").set({ v: 1 });
  await db.collection(COLLECTIONS.guardianRequests).doc(`r-${uid}`).set({ uid });
  await db.collection(COLLECTIONS.mail).doc(`m-${uid}`).set({ uid });
  await db.collection(COLLECTIONS.mail).doc(`n-${uid}`).set({ uid });
  await bucket.file(`users/${uid}/a.txt`).save("a");
  await bucket.file(`users/${uid}/sub/b.txt`).save("b");
  return uid;
}
const porUid = async (c: string, uid: string) => (await db.collection(c).where("uid", "==", uid).get()).size;

test("cascada completa: Auth, users + subcolecciones, docs por uid y Storage", async () => {
  const uid = await usuarioCompleto();
  const otro = await usuarioCompleto();
  const limite = await db.collection(COLLECTIONS.guardianEmailLimits).doc(`h-${uid}`).set({ count: 1 }).then(() => `h-${uid}`);

  const res = await eraseUserData(deps, uid, { deleteAuth: true });
  expect(res.deleted).toMatchObject({ guardianRequests: 1, mail: 2 });

  await expect(auth.getUser(uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
  const users = db.collection(COLLECTIONS.users).doc(uid);
  expect((await users.get()).exists).toBe(false);
  expect((await users.collection(COLLECTIONS.consents).get()).size).toBe(0);
  expect((await users.collection("otraSub").get()).size).toBe(0);
  expect(await porUid(COLLECTIONS.guardianRequests, uid)).toBe(0);
  expect(await porUid(COLLECTIONS.mail, uid)).toBe(0);
  expect((await bucket.getFiles({ prefix: `users/${uid}/` }))[0]).toHaveLength(0);

  // Lo ajeno y lo exento permanece.
  expect((await db.collection(COLLECTIONS.users).doc(otro).get()).exists).toBe(true);
  expect(await porUid(COLLECTIONS.mail, otro)).toBe(2);
  expect((await bucket.getFiles({ prefix: `users/${otro}/` }))[0]).toHaveLength(2);
  expect((await db.collection(COLLECTIONS.guardianEmailLimits).doc(limite).get()).exists).toBe(true);
  expect((await auth.getUser(otro)).uid).toBe(otro);
});

test("deleteAuth:false conserva la cuenta Auth; repetir es idempotente; usuario sin perfil se borra de Auth", async () => {
  const uid = await usuarioCompleto();
  await eraseUserData(deps, uid, { deleteAuth: false });
  expect((await auth.getUser(uid)).uid).toBe(uid);
  await eraseUserData(deps, uid, { deleteAuth: true });
  await expect(eraseUserData(deps, uid, { deleteAuth: true })).resolves.toBeDefined();

  const { uid: sinPerfil } = await auth.createUser({ email: `sp-${Date.now()}@example.com`, password: "Passw0rd!x" });
  await eraseUserData(deps, sinPerfil, { deleteAuth: true });
  await expect(auth.getUser(sinPerfil)).rejects.toMatchObject({ code: "auth/user-not-found" });
});

test("dos ejecuciones simultáneas convergen", async () => {
  const uid = await usuarioCompleto();
  await Promise.all([eraseUserData(deps, uid, { deleteAuth: true }), eraseUserData(deps, uid, { deleteAuth: true })]);
  await expect(auth.getUser(uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
  expect((await db.collection(COLLECTIONS.users).doc(uid).get()).exists).toBe(false);
});
