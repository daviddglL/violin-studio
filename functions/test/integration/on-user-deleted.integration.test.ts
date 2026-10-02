import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { COLLECTIONS } from "../../src/common/collections";
import { eraseUserData } from "../../src/erasure/erase-user-data";
import { onUserDeletedHandler } from "../../src/erasure/on-user-deleted";

// El emulador de Auth dispara onDelete (v1); el trigger se carga desde la codebase default (lib/).
const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const bucket = getStorage(app).bucket();

async function esperar(cond: () => Promise<boolean>, timeoutMs = 15_000): Promise<boolean> {
  const limite = Date.now() + timeoutMs;
  while (Date.now() < limite) {
    if (await cond()) return true;
    await new Promise((r) => setTimeout(r, 250));
  }
  return false;
}

async function usuarioCompleto() {
  const { uid } = await auth.createUser({ email: `ud-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  const ref = db.collection(COLLECTIONS.users).doc(uid);
  await ref.set({ role: "independent", consentStatus: "granted", policyVersion: 1, isMinor: false });
  await ref.collection(COLLECTIONS.consents).doc("terms_v1_self_e0").set({ type: "terms" });
  await db.collection(COLLECTIONS.guardianRequests).doc(`r-${uid}`).set({ uid });
  await db.collection(COLLECTIONS.mail).doc(`m-${uid}`).set({ uid });
  await bucket.file(`users/${uid}/a.txt`).save("a");
  return uid;
}
const restos = async (uid: string) => ({
  perfil: (await db.collection(COLLECTIONS.users).doc(uid).get()).exists,
  consents: (await db.collection(COLLECTIONS.users).doc(uid).collection(COLLECTIONS.consents).get()).size,
  guardianRequests: (await db.collection(COLLECTIONS.guardianRequests).where("uid", "==", uid).get()).size,
  mail: (await db.collection(COLLECTIONS.mail).where("uid", "==", uid).get()).size,
  ficheros: (await bucket.getFiles({ prefix: `users/${uid}/` }))[0].length,
});
const limpio = { perfil: false, consents: 0, guardianRequests: 0, mail: 0, ficheros: 0 };

test("borrado externo con Admin: el trigger limpia perfil, subcolecciones, guardianRequests, mail y Storage", async () => {
  const uid = await usuarioCompleto();
  const otro = await usuarioCompleto();
  await auth.deleteUser(uid);
  expect(await esperar(async () => JSON.stringify(await restos(uid)) === JSON.stringify(limpio))).toBe(true);
  expect((await restos(otro)).perfil).toBe(true);
  expect((await restos(otro)).mail).toBe(1);
});

test("el disparo tras deleteAccount (eraseUserData con deleteAuth:true) es un no-op idempotente", async () => {
  const uid = await usuarioCompleto();
  await eraseUserData({ db, auth, bucket }, uid, { deleteAuth: true }); // dispara además el trigger real
  await new Promise((r) => setTimeout(r, 2_000));
  expect(await restos(uid)).toEqual(limpio);
  await expect(onUserDeletedHandler({ db, auth, bucket }, uid)).resolves.toEqual({ deleted: { guardianRequests: 0, mail: 0 } });
});
