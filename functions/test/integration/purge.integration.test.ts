import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { COLLECTIONS } from "../../src/common/collections";
import { eraseUserData } from "../../src/erasure/erase-user-data";
import { purgeIdentityHandler } from "../../src/maintenance/purge";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const bucket = getStorage(app).bucket();

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const users = db.collection(COLLECTIONS.users);

type Call = [string, { deleteAuth: boolean }];
let calls: Call[];
let logs: { message: string; data: Record<string, unknown> }[];
const erase = async (uid: string, opts: { deleteAuth: boolean }) => void calls.push([uid, opts]);
const log = (message: string, data: Record<string, unknown>) => void logs.push({ message, data });
const deps = (over: Record<string, unknown> = {}) => ({ db, auth, erase, log, ...over });
const ago = (now: Date, ms: number) => Timestamp.fromMillis(now.getTime() - ms);

async function wipe() {
  const fs = process.env.FIRESTORE_EMULATOR_HOST;
  const au = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  await fetch(`http://${fs}/emulator/v1/projects/${project}/databases/(default)/documents`, { method: "DELETE" });
  await fetch(`http://${au}/emulator/v1/projects/${project}/accounts`, { method: "DELETE" });
}
beforeEach(async () => {
  calls = [];
  logs = [];
  await wipe();
});

/** Usuario con cuenta Auth (o sin ella) y perfil con los campos dados. */
async function user(fields: Record<string, unknown>, withAuth = true): Promise<string> {
  const uid = withAuth
    ? (await auth.createUser({ email: `p-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" })).uid
    : `ghost-${Math.random().toString(36).slice(2)}`;
  await users.doc(uid).set({ role: "independent", isMinor: false, ...fields });
  return uid;
}
const erased = () => calls.map(([uid]) => uid).sort();
const erasedWith = (deleteAuth: boolean) => calls.filter(([, o]) => o.deleteAuth === deleteAuth).map(([uid]) => uid).sort();

describe("7b.1 parental_pending caducado (mide desde guardian.requestedAt)", () => {
  test("7 d 1 min si, 6 d 23 h no; granted, adulto, otros estados y menor antiguo que re-pide tutor no", async () => {
    const now = new Date();
    const pending = (ms: number) =>
      user({ consentStatus: "parental_pending", isMinor: true, createdAt: ago(now, 30 * DAY), guardian: { requestedAt: ago(now, ms) } });
    const viejo = await pending(7 * DAY + MIN);
    await pending(6 * DAY + 23 * HOUR);
    await pending(HOUR); // createdAt de hace 30 d, pero pidio tutor hace 1 h tras un bump
    await user({ consentStatus: "granted", isMinor: true, guardian: { requestedAt: ago(now, 10 * DAY) } });
    await user({ consentStatus: "revoked", isMinor: true, guardian: { requestedAt: ago(now, 10 * DAY) } });
    await user({ consentStatus: "pending", createdAt: ago(now, 30 * DAY) });
    await purgeIdentityHandler(deps(), now);
    expect(calls).toEqual([[viejo, { deleteAuth: true }]]);
  });

  test("pagina: con pageSize 2 se procesan los 5 caducados", async () => {
    const now = new Date();
    const ids = [];
    for (let i = 0; i < 5; i++) ids.push(await user({ consentStatus: "parental_pending", isMinor: true, guardian: { requestedAt: ago(now, 8 * DAY + i * MIN) } }));
    await purgeIdentityHandler(deps({ pageSize: 2 }), now);
    expect(erased()).toEqual([...ids].sort());
  });
});

test("7b.2 integracion real: borra Auth, perfil, Storage, guardianRequests y mail del caducado; granted intacto", async () => {
  const now = new Date();
  const viejo = await user({ consentStatus: "parental_pending", isMinor: true, guardian: { requestedAt: ago(now, 8 * DAY) } });
  const sano = await user({ consentStatus: "granted", policyVersion: 1 });
  for (const uid of [viejo, sano]) {
    await db.collection(COLLECTIONS.guardianRequests).doc(`r-${uid}`).set({ uid });
    await db.collection(COLLECTIONS.mail).doc(`m-${uid}`).set({ uid });
    await bucket.file(`users/${uid}/a.txt`).save("a");
  }
  await purgeIdentityHandler({ db, auth, log, erase: (uid, o) => eraseUserData({ db, auth, bucket }, uid, o) }, now);
  await expect(auth.getUser(viejo)).rejects.toMatchObject({ code: "auth/user-not-found" });
  expect((await users.doc(viejo).get()).exists).toBe(false);
  expect((await db.collection(COLLECTIONS.guardianRequests).doc(`r-${viejo}`).get()).exists).toBe(false);
  expect((await db.collection(COLLECTIONS.mail).doc(`m-${viejo}`).get()).exists).toBe(false);
  expect((await bucket.getFiles({ prefix: `users/${viejo}/` }))[0]).toHaveLength(0);
  expect((await auth.getUser(sano)).uid).toBe(sano);
  expect((await users.doc(sano).get()).exists).toBe(true);
  expect((await db.collection(COLLECTIONS.mail).doc(`m-${sano}`).get()).exists).toBe(true);
  expect((await bucket.getFiles({ prefix: `users/${sano}/` }))[0]).toHaveLength(1);
});

test("7b.3 borrados atascados: in_progress de > 1 h se reanuda con deleteAuth:true; de < 1 h no", async () => {
  const now = new Date();
  const atascado = await user({ consentStatus: "granted", deletion: { state: "in_progress", startedAt: ago(now, HOUR + MIN) } });
  await user({ consentStatus: "granted", deletion: { state: "in_progress", startedAt: ago(now, 59 * MIN) } });
  await user({ consentStatus: "granted" });
  await purgeIdentityHandler(deps(), now);
  expect(calls).toEqual([[atascado, { deleteAuth: true }]]);
});

test("7b.3b perfiles huerfanos: sin cuenta Auth y creados hace > 1 h -> deleteAuth:false; con Auth, recientes o con deletion no", async () => {
  const now = new Date();
  const huerfano = await user({ consentStatus: "granted", createdAt: ago(now, 2 * HOUR) }, false);
  await user({ consentStatus: "granted", createdAt: ago(now, 30 * MIN) }, false);
  await user({ consentStatus: "granted", createdAt: ago(now, 2 * HOUR), deletion: { state: "in_progress", startedAt: ago(now, 10 * MIN) } }, false);
  await user({ consentStatus: "granted", createdAt: ago(now, 2 * HOUR) });
  await purgeIdentityHandler(deps({ pageSize: 2 }), now);
  expect(calls).toEqual([[huerfano, { deleteAuth: false }]]);
});

test("7b.4 barrido: expireAt < now se borra (paginado) en mail, guardianRequests y guardianEmailLimits; vigentes y solicitud aceptada con enlace de revocacion no", async () => {
  const now = new Date();
  const cols = [COLLECTIONS.mail, COLLECTIONS.guardianRequests, COLLECTIONS.guardianEmailLimits];
  for (const c of cols) {
    for (let i = 0; i < 5; i++) await db.collection(c).doc(`old${i}`).set({ expireAt: ago(now, DAY + i * MIN) });
    await db.collection(c).doc("vigente").set({ expireAt: Timestamp.fromMillis(now.getTime() + HOUR) });
  }
  // Aceptada: su expireAt se amplio a revokeExpiresAt + 7 d (3c.3), aun vigente.
  await db.collection(COLLECTIONS.guardianRequests).doc("aceptada").set({
    outcome: "accepted",
    revokeExpiresAt: Timestamp.fromMillis(now.getTime() + 29 * DAY),
    expireAt: Timestamp.fromMillis(now.getTime() + 36 * DAY),
  });
  await purgeIdentityHandler(deps({ pageSize: 2 }), now);
  for (const c of cols) {
    const ids = (await db.collection(c).get()).docs.map((d) => d.id).filter((id) => id !== "aceptada");
    expect(ids).toEqual(["vigente"]);
  }
  expect((await db.collection(COLLECTIONS.guardianRequests).doc("aceptada").get()).exists).toBe(true);
  expect(logs.find((l) => l.message === "purge.done")?.data).toMatchObject({ expired: { mail: 5, guardianRequests: 5, guardianEmailLimits: 5 } });
});

describe("7b.5 D4 cuentas Auth sin perfil", () => {
  test("a los 7 d se borra con deleteAuth:true (paginado); a 6 d 23 h no; con perfil no", async () => {
    const huerfanas: string[] = [];
    for (let i = 0; i < 5; i++) huerfanas.push((await auth.createUser({ email: `d4-${i}-${Date.now()}@example.com` })).uid);
    const conPerfil = await user({ consentStatus: "granted", createdAt: Timestamp.now() });
    const t0 = Date.now();
    await purgeIdentityHandler(deps({ pageSize: 2 }), new Date(t0 + 6 * DAY + 23 * HOUR));
    expect(calls).toEqual([]);
    await purgeIdentityHandler(deps({ pageSize: 2 }), new Date(t0 + 7 * DAY + MIN));
    expect(erasedWith(true)).toEqual([...huerfanas].sort());
    expect(erased()).not.toContain(conPerfil);
  });
});

test("7b.6 un fallo en un usuario no detiene el lote: se registra sin PII y el resumen cuenta por categoria", async () => {
  const now = new Date();
  const ids = [];
  for (let i = 0; i < 3; i++) ids.push(await user({ consentStatus: "parental_pending", isMinor: true, guardian: { requestedAt: ago(now, 8 * DAY + i * MIN) } }));
  const roto = [...ids].sort()[1];
  const flaky = async (uid: string, o: { deleteAuth: boolean }) => {
    if (uid === roto) throw Object.assign(new Error(`boom ${uid} ana@example.com`), { code: "unavailable" });
    await erase(uid, o);
  };
  await purgeIdentityHandler(deps({ erase: flaky }), now);
  expect(erased()).toEqual([...ids].filter((u) => u !== roto).sort());
  const todo = JSON.stringify(logs);
  for (const uid of ids) expect(todo).not.toContain(uid);
  expect(todo).not.toContain("ana@example.com");
  expect(logs.find((l) => l.message === "purge.userFailed")?.data).toMatchObject({ category: "parentalPending", code: "unavailable", uidHash: expect.any(String) });
  expect(logs.find((l) => l.message === "purge.done")?.data).toMatchObject({ parentalPending: { found: 3, erased: 2, failed: 1 } });
});
