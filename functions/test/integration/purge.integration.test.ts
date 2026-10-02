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

describe("revision 7b: carreras, exencion, presupuesto y fronteras", () => {
  const minor = (now: Date, requestedAgo: number) => user({ consentStatus: "parental_pending", isMinor: true, guardian: { requestedAt: ago(now, requestedAgo) } });

  test("W2 (a): si entre la consulta y el borrado el menor pasa a granted, NO se borra (skipped)", async () => {
    const now = new Date();
    const uid = await minor(now, 8 * DAY);
    const beforeErase = async (_c: string, u: string) => void (await users.doc(u).update({ consentStatus: "granted" }));
    const s = await purgeIdentityHandler(deps({ beforeErase }), now);
    expect(calls).toEqual([]);
    expect(s.parentalPending).toMatchObject({ found: 1, erased: 0, skipped: 1 });
    expect(uid).toBeTruthy();
  });

  test("W2 (b): si el borrado atascado deja de serlo (startedAt reciente), NO se reanuda (skipped)", async () => {
    const now = new Date();
    await user({ consentStatus: "granted", deletion: { state: "in_progress", startedAt: ago(now, 2 * HOUR) } });
    const beforeErase = async (_c: string, u: string) => void (await users.doc(u).update({ "deletion.startedAt": ago(now, MIN) }));
    const s = await purgeIdentityHandler(deps({ beforeErase }), now);
    expect(calls).toEqual([]);
    expect(s.stuckDeletion).toMatchObject({ found: 1, erased: 0, skipped: 1 });
  });

  test("W3 (a): D4 re-comprueba que no exista el perfil justo antes de borrar", async () => {
    const { uid } = await auth.createUser({ email: `race-${Date.now()}@example.com` });
    const beforeErase = async (_c: string, u: string) => void (await users.doc(u).set({ consentStatus: "pending", isMinor: false }));
    const s = await purgeIdentityHandler(deps({ beforeErase }), new Date(Date.now() + 8 * DAY));
    expect(calls).toEqual([]);
    expect(s.orphanAuth).toMatchObject({ found: 1, erased: 0, skipped: 1 });
    expect(uid).toBeTruthy();
  });

  test("W3 (b): una cuenta Auth con el claim purgeExempt:true nunca se borra por D4", async () => {
    const exenta = (await auth.createUser({ email: `ex-${Date.now()}@example.com` })).uid;
    await auth.setCustomUserClaims(exenta, { purgeExempt: true });
    const normal = (await auth.createUser({ email: `no-${Date.now()}@example.com` })).uid;
    await purgeIdentityHandler(deps(), new Date(Date.now() + 8 * DAY));
    expect(erased()).toEqual([normal]);
  });

  test("W4: el presupuesto de tiempo agotado deja de empezar borrados, registra purge.budgetExhausted y NO lanza", async () => {
    const now = new Date();
    await minor(now, 8 * DAY);
    let t = 0;
    const s = await purgeIdentityHandler(deps({ budgetMs: 0, nowMs: () => (t += 100) }), now);
    expect(calls).toEqual([]);
    expect(s.budgetExhausted).toBe(true);
    expect(logs.find((l) => l.message === "purge.budgetExhausted")?.data).toMatchObject({ parentalPending: { found: 0 } });
  });

  test("W4: orden de prioridad (b) atascados, (3b) huerfanos, (a) parental_pending, (D4) Auth sin perfil; con presupuesto para 2 borrados solo caen los dos primeros", async () => {
    const now = new Date(Date.now() + 8 * DAY);
    const atascado = await user({ consentStatus: "granted", deletion: { state: "in_progress", startedAt: ago(now, 2 * HOUR) } });
    const huerfano = await user({ consentStatus: "granted", createdAt: ago(now, 2 * HOUR) }, false);
    await minor(now, 8 * DAY);
    await auth.createUser({ email: `prio-${Date.now()}@example.com` });
    const order: string[] = [];
    let t = 0;
    await purgeIdentityHandler(deps({ budgetMs: 250, nowMs: () => (t += 100), beforeErase: async (c: string) => void order.push(c) }), now);
    expect(order).toEqual(["stuckDeletion", "orphanProfiles"]);
    expect(erased()).toEqual([atascado, huerfano].sort());
  });

  test("S5: el barrido cuenta cada escritura fallida, no solo la primera", async () => {
    const now = new Date();
    for (let i = 0; i < 3; i++) await db.collection(COLLECTIONS.mail).doc(`old${i}`).set({ expireAt: ago(now, DAY) });
    const writer = { delete: () => Promise.reject(Object.assign(new Error("x"), { code: "unavailable" })), close: async () => undefined };
    const proxied = new Proxy(db, {
      get: (t, p) => (p === "bulkWriter" ? () => writer : typeof (t as never)[p] === "function" ? ((t as never)[p] as () => unknown).bind(t) : (t as never)[p]),
    });
    const s = await purgeIdentityHandler(deps({ db: proxied }), now);
    expect(s.expired.mail).toBe(0);
    expect(s.expiredFailed.mail).toBe(3);
  });

  test("S11 fronteras inclusivas: exactamente 7 d y exactamente 1 h se procesan; expireAt == now no se barre; se registra scanned", async () => {
    const now = new Date();
    const pend = await minor(now, 7 * DAY);
    const atascado = await user({ consentStatus: "granted", deletion: { state: "in_progress", startedAt: ago(now, HOUR) } });
    const huerfano = await user({ consentStatus: "granted", createdAt: ago(now, HOUR) }, false);
    await db.collection(COLLECTIONS.mail).doc("justo").set({ expireAt: Timestamp.fromMillis(now.getTime()) });
    const s = await purgeIdentityHandler(deps(), now);
    expect(erased()).toEqual([pend, atascado, huerfano].sort());
    expect((await db.collection(COLLECTIONS.mail).doc("justo").get()).exists).toBe(true);
    expect(s.scannedProfiles).toBeGreaterThanOrEqual(1);
    expect(logs.find((l) => l.message === "purge.done")?.data).toMatchObject({ scannedProfiles: s.scannedProfiles });
  });

  test("S11 D4: una cuenta de exactamente 7 d se borra", async () => {
    const { uid } = await auth.createUser({ email: `edge-${Date.now()}@example.com` });
    const creado = Date.parse((await auth.getUser(uid)).metadata.creationTime);
    await purgeIdentityHandler(deps(), new Date(creado + 7 * DAY));
    expect(erased()).toEqual([uid]);
  });
});
