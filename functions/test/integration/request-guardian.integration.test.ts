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
let seq = 0;
// Destino único por test: el límite por destino (3 / 24 h con reloj fijo) se comparte entre tests.
const uniq = () => `t${Date.now()}${seq++}@example.com`;

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
    await expect(ask(g, uniq())).rejects.toMatchObject({ code: "failed-precondition" });
    expect(await where("mail", g.uid)).toHaveLength(0);
    const adult = await user({ isMinor: false });
    await expect(ask(adult, uniq())).rejects.toMatchObject({ details: { reason: "NOT_MINOR" } });
    const none = { uid: "sin-perfil", email: "x@example.com" };
    await expect(ask(none, uniq())).rejects.toMatchObject({ details: { reason: "NO_PROFILE" } });
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
    const d4 = uniq();
    for (let i = 0; i < 3; i++) await ask(u, uniq());
    await expect(ask(u, d4)).rejects.toMatchObject({
      code: "resource-exhausted", details: { reason: "RATE_LIMITED", retryAfterSeconds: 86400 },
    });
    expect(await where("mail", u.uid)).toHaveLength(3);
    now = new Date(now.getTime() + 24 * 3600_000);
    await expect(ask(u, d4)).resolves.toMatchObject({ status: "sent" });
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
    await ask(u, uniq());
    const first = (await perfil(u.uid)).guardian.requestId;
    await ask(u, uniq());
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
    await expect(call(u.uid, { email: u.email, email_verified: false }, { guardianEmail: uniq() })).rejects.toMatchObject({
      details: { reason: "EMAIL_NOT_VERIFIED" },
    });
    expect(await where("mail", u.uid)).toHaveLength(0);
    await expect(call(u.uid, { email: u.email, email_verified: true }, { guardianEmail: uniq() })).resolves.toEqual({
      status: "sent", emailMasked: "t***@e***.com",
    });
    expect((await db.collection("mail").where("uid", "==", u.uid).get()).docs[0].data().message.text).toContain("/tutor?r=");
  });
});

describe("requestGuardianConsentHandler: casos límite (revisión 3a)", () => {
  const mails = async (uid: string) => (await where("mail", uid)).map((d) => d.data());

  test("deletion en curso -> NO_PROFILE sin escrituras", async () => {
    const u = await user({ deletion: { state: "in_progress" } });
    await expect(ask(u, uniq())).rejects.toMatchObject({ details: { reason: "NO_PROFILE" } });
    expect(await where("guardianRequests", u.uid)).toHaveLength(0);
    expect(await mails(u.uid)).toHaveLength(0);
    expect((await perfil(u.uid)).consentStatus).toBe("pending");
  });

  test("granted con policyVersion obsoleta -> permitido y pasa a parental_pending", async () => {
    const u = await user({ consentStatus: "granted", policyVersion: 0 });
    await expect(ask(u, uniq())).resolves.toMatchObject({ status: "sent" });
    expect((await perfil(u.uid)).consentStatus).toBe("parental_pending");
  });

  test("la solicitud anterior ya usada o ya sustituida no se vuelve a sustituir", async () => {
    const used = await user();
    await ask(used, uniq());
    const usedId = (await perfil(used.uid)).guardian.requestId;
    const usedAt = Timestamp.fromMillis(now.getTime() - 1000);
    await db.collection("guardianRequests").doc(usedId).update({ usedAt });
    await ask(used, uniq());
    const u1 = (await db.collection("guardianRequests").doc(usedId).get()).data()!;
    expect(u1.usedAt.toMillis()).toBe(usedAt.toMillis());
    expect(u1.supersededAt).toBeNull();

    const sup = await user();
    await ask(sup, uniq());
    const supId = (await perfil(sup.uid)).guardian.requestId;
    const t0 = Timestamp.fromMillis(now.getTime() - 5000);
    await db.collection("guardianRequests").doc(supId).update({ supersededAt: t0 });
    await ask(sup, uniq());
    expect((await db.collection("guardianRequests").doc(supId).get()).data()!.supersededAt.toMillis()).toBe(t0.toMillis());
  });

  test("el reenvío conserva y amplía users.guardian.sends", async () => {
    const u = await user();
    const d = uniq();
    await ask(u, d);
    const first = (await perfil(u.uid)).guardian.sends.map((t: Timestamp) => t.toMillis());
    now = new Date(now.getTime() + 60_000);
    await ask(u, d);
    const after = (await perfil(u.uid)).guardian.sends.map((t: Timestamp) => t.toMillis());
    expect(after).toEqual([...first, now.getTime()]);
  });

  test("locale desconocido cae a inglés", async () => {
    const u = await user({ locale: "fr" });
    await ask(u, uniq());
    expect((await mails(u.uid))[0].message.text).toContain("guardian");
  });

  test("displayName hostil se escapa en el HTML del mail", async () => {
    const u = await user({ displayName: `<script>alert(1)</script> "&'` });
    await ask(u, uniq());
    const html = (await mails(u.uid))[0].message.html as string;
    expect(html).not.toContain("<script");
    expect(html).toContain("&lt;script&gt;");
  });

  test("mail.to es exactamente el email normalizado que se hasheó; expireAt = +72 h", async () => {
    const u = await user();
    const dest = uniq();
    await ask(u, `  ${dest.toUpperCase()} `);
    const [m] = await mails(u.uid);
    expect(m.to).toBe(dest);
    expect(m.expireAt.toMillis()).toBe(now.getTime() + 72 * 3600_000);
    expect((await where("guardianRequests", u.uid))[0].data().guardianEmailHmac).toBe(hmacEmail(PEPPER, m.to));
  });

  test("4 llamadas concurrentes de la misma cuenta -> como mucho 3 mails", async () => {
    const u = await user();
    const res = await Promise.allSettled([1, 2, 3, 4].map(() => ask(u, uniq())));
    expect(res.filter((r) => r.status === "fulfilled")).toHaveLength(3);
    expect(await mails(u.uid)).toHaveLength(3);
    expect((await perfil(u.uid)).guardian.sends).toHaveLength(3);
  });
});
