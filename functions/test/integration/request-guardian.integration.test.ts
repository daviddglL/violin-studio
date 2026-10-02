import functionsTest from "firebase-functions-test";
import { requestGuardianConsent } from "../../src/index";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { eraseUserData } from "../../src/erasure/erase-user-data";
import { requestGuardianConsentHandler } from "../../src/guardian/request";
import { hmacEmail, sha256Hex } from "../../src/common/hashing";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const PEPPER = "t".repeat(32);

let now = new Date("2026-03-01T10:00:00Z");
const logs: Array<[string, Record<string, unknown>]> = [];
const deps = () => ({
  db, pepper: PEPPER, linkBaseUrl: "https://x.app", guardianFlowEnabled: true,
  clock: () => now, log: (m: string, d: Record<string, unknown>) => void logs.push([m, d]),
});
const user = async (perfil: Record<string, unknown> = {}) => {
  const email = `m-${Date.now()}-${Math.random()}@example.com`;
  const { uid } = await auth.createUser({ email, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    displayName: "Ana", locale: "es", isMinor: true, consentStatus: "pending", policyVersion: null, ...perfil,
  });
  return { uid, email };
};
const ask = (u: { uid: string; email: string }, guardianEmail: string) =>
  requestGuardianConsentHandler(deps(), u.uid, u.email, { guardianEmail });
const where = async (col: string, uid: string) => (await db.collection(col).where("uid", "==", uid).get()).docs;
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;

beforeEach(() => { now = new Date("2026-03-01T10:00:00Z"); logs.length = 0; });

describe("requestGuardianConsentHandler", () => {
  test.each([["pending"], ["revoked"]])("menor en %s -> parental_pending con solicitud, mail y resumen", async (estado) => {
    const u = await user({ consentStatus: estado });
    const res = await ask(u, "Tutor@Example.com");
    expect(res).toEqual({ status: "sent", emailMasked: "t***@e***.com" });

    const p = await perfil(u.uid);
    expect(p.consentStatus).toBe("parental_pending");
    const [req] = await where("guardianRequests", u.uid);
    expect(req.id).toBe(p.guardian.requestId);
    expect(p.guardian).toMatchObject({ emailMasked: "t***@e***.com" });
    expect(p.guardian.requestedAt).toBeInstanceOf(Timestamp);
    expect(p.guardian.sends.map((t: Timestamp) => t.toMillis())).toEqual([now.getTime()]);
    const r = req.data();
    expect(r).toMatchObject({ uid: u.uid, usedAt: null, outcome: null, supersededAt: null, attempts: 0 });
    expect(r.guardianEmailHmac).toBe(hmacEmail(PEPPER, "tutor@example.com"));
    expect(r.expiresAt.toMillis()).toBe(now.getTime() + 72 * 3600_000);
    expect(r.expireAt.toMillis()).toBe(r.expiresAt.toMillis() + 7 * 24 * 3600_000);

    const [mail] = await where("mail", u.uid);
    expect(mail.data()).toMatchObject({ to: "tutor@example.com", kind: "guardian_consent", uid: u.uid });
    const link = mail.data().message.text.match(/https:\/\/x\.app\/tutor\?r=(\S+)#t=([\w-]+)/)!;
    expect(link[1]).toBe(req.id);
    expect(sha256Hex(link[2])).toBe(r.tokenHash);
  });

  test("granted vigente -> failed-precondition sin escrituras; no menor -> NOT_MINOR", async () => {
    const g = await user({ consentStatus: "granted", policyVersion: 1 });
    await expect(ask(g, "t@example.com")).rejects.toMatchObject({ code: "failed-precondition" });
    expect(await where("mail", g.uid)).toHaveLength(0);
    const adult = await user({ isMinor: false });
    await expect(ask(adult, "t@example.com")).rejects.toMatchObject({ details: { reason: "NOT_MINOR" } });
    const none = { uid: "sin-perfil", email: "x@example.com" };
    await expect(ask(none, "t@example.com")).rejects.toMatchObject({ details: { reason: "NO_PROFILE" } });
  });

  test("3a.7 token y email del tutor no aparecen en solicitud, perfil ni logs; solo en mail/", async () => {
    const u = await user();
    await ask(u, "secreto.tutor@example.com");
    const [mail] = await where("mail", u.uid);
    const token = mail.data().message.text.match(/#t=([\w-]+)/)![1];
    const visible = JSON.stringify([(await where("guardianRequests", u.uid))[0].data(), await perfil(u.uid), logs]);
    for (const s of [token, "secreto.tutor", "secreto"]) expect(visible).not.toContain(s);
    expect(JSON.stringify(logs)).not.toContain(u.uid);
    expect(logs.length).toBeGreaterThan(0);
  });

  test("3a.8 el 4.º envío por cuenta en 24 h -> RATE_LIMITED sin mail; con ventana expirada se permite", async () => {
    const u = await user();
    for (const i of [1, 2, 3]) await ask(u, `g${i}@example.com`);
    await expect(ask(u, "g4@example.com")).rejects.toMatchObject({
      code: "resource-exhausted", details: { reason: "RATE_LIMITED", retryAfterSeconds: 86400 },
    });
    expect(await where("mail", u.uid)).toHaveLength(3);
    now = new Date(now.getTime() + 24 * 3600_000);
    await expect(ask(u, "g4@example.com")).resolves.toMatchObject({ status: "sent" });
  });

  test("3a.8 el 4.º envío al mismo destino desde otras cuentas -> RATE_LIMITED", async () => {
    const dest = `dest-${Date.now()}@example.com`;
    for (let i = 0; i < 3; i++) await ask(await user(), dest);
    const extra = await user();
    await expect(ask(extra, dest)).rejects.toMatchObject({ details: { reason: "RATE_LIMITED" } });
    expect(await where("mail", extra.uid)).toHaveLength(0);
    expect((await perfil(extra.uid)).consentStatus).toBe("pending");
    const lim = await db.collection("guardianEmailLimits").doc(hmacEmail(PEPPER, dest)).get();
    expect(lim.data()!.sends).toHaveLength(3);
    expect(lim.data()!.expireAt).toBeInstanceOf(Timestamp);
  });

  test("3a.9 reenviar o cambiar de email sustituye la solicitud anterior (usedAt intacto) y cuenta para los límites", async () => {
    const u = await user();
    await ask(u, "a@example.com");
    const first = (await perfil(u.uid)).guardian.requestId;
    await ask(u, "b@example.com");
    const second = (await perfil(u.uid)).guardian.requestId;
    expect(second).not.toBe(first);
    const old = (await db.collection("guardianRequests").doc(first).get()).data()!;
    expect(old.supersededAt).toBeInstanceOf(Timestamp);
    expect(old.usedAt).toBeNull();
    expect((await db.collection("guardianRequests").doc(second).get()).data()!.supersededAt).toBeNull();
    expect((await perfil(u.uid)).guardian.sends).toHaveLength(2);
  });

  test("la cascada eraseUserData borra solicitudes y mail del uid y deja guardianEmailLimits", async () => {
    const u = await user();
    await ask(u, "cascada@example.com");
    const bucket = getStorage(app).bucket(`${project}.appspot.com`);
    await eraseUserData({ db, auth, bucket }, u.uid, { deleteAuth: true });
    expect(await where("guardianRequests", u.uid)).toHaveLength(0);
    expect(await where("mail", u.uid)).toHaveLength(0);
    expect((await db.collection("guardianEmailLimits").doc(hmacEmail(PEPPER, "cascada@example.com")).get()).exists).toBe(true);
  });
});

describe("callable requestGuardianConsent", () => {
  const fft = functionsTest();
  afterAll(() => fft.cleanup());
  beforeAll(() => { process.env.GUARDIAN_EMAIL_PEPPER = PEPPER; });
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const call = async (uid: string, token: Record<string, unknown>, data: unknown): Promise<any> =>
    fft.wrap(requestGuardianConsent)({ data, auth: { uid, token } } as never);

  test("sin email verificado -> EMAIL_NOT_VERIFIED; verificado -> sent", async () => {
    const u = await user();
    await expect(call(u.uid, { email: u.email, email_verified: false }, { guardianEmail: "t@example.com" })).rejects.toMatchObject({
      details: { reason: "EMAIL_NOT_VERIFIED" },
    });
    expect(await where("mail", u.uid)).toHaveLength(0);
    await expect(call(u.uid, { email: u.email, email_verified: true }, { guardianEmail: "t@example.com" })).resolves.toEqual({
      status: "sent", emailMasked: "t***@e***.com",
    });
    expect((await db.collection("mail").where("uid", "==", u.uid).get()).docs[0].data().message.text).toContain("/tutor?r=");
  });
});
