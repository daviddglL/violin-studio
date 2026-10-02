import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { eraseUserData } from "../../src/erasure/erase-user-data";
import { guardianConsentHandler } from "../../src/guardian/confirm";
import { requestGuardianConsentHandler } from "../../src/guardian/request";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
export const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
export const auth = getAuth(app);
export const db = getFirestore(app);
export const bucket = getStorage(app).bucket();
export const PEPPER = "t".repeat(32);
export const state = { now: new Date("2026-03-01T10:00:00Z"), logs: [] as Array<[string, Record<string, unknown>]>, erase: undefined as undefined | ((uid: string) => Promise<unknown>) };
export const resetState = () => { state.now = new Date("2026-03-01T10:00:00Z"); state.logs.length = 0; state.erase = undefined; };
export const erased: string[] = [];

export const deps = () => ({
  db, auth, clock: () => state.now, currentVersion: 1,
  log: (m: string, d: Record<string, unknown>) => void state.logs.push([m, d]),
  erase: (uid: string) => { erased.push(uid); return (state.erase ?? ((u: string) => eraseUserData({ db, auth, bucket }, u, { deleteAuth: true })))(uid); },
});
export const post = (body: Record<string, unknown>) => guardianConsentHandler(deps(), { method: "POST", query: {}, body });
export const get = (query: Record<string, unknown>) => guardianConsentHandler(deps(), { method: "GET", query, body: {} });

/** Menor con solicitud real (vía requestGuardianConsentHandler); devuelve uid, id, token en claro y destino. */
export async function setup() {
  const email = `m-${Date.now()}-${Math.random()}@example.com`;
  const { uid } = await auth.createUser({ email, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({ displayName: "Ana", locale: "es", isMinor: true, consentStatus: "pending", policyVersion: null });
  await bucket.file(`users/${uid}/a.txt`).save("a");
  const guardian = `t${Date.now()}${Math.random()}@example.com`;
  await requestGuardianConsentHandler(
    { db, pepper: PEPPER, linkBaseUrl: "https://x.app", guardianFlowEnabled: true, clock: () => state.now, log: () => undefined },
    uid, email, { guardianEmail: guardian },
  );
  const [mail] = (await db.collection("mail").where("uid", "==", uid).get()).docs;
  const [, r, t] = mail.data().message.text.match(/\/tutor\?r=(\S+)#t=([\w-]+)/)!;
  return { uid, r: r as string, t: t as string, guardian };
}
export const req = async (r: string) => (await db.collection("guardianRequests").doc(r).get()).data()!;
export const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;
export const authExists = (uid: string) => auth.getUser(uid).then(() => true, () => false);
