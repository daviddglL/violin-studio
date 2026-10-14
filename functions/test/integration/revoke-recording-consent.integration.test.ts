import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import functionsTest from "firebase-functions-test";
import { RECORDING_CONSENT_VERSION } from "../../src/config/identity";
import { grantRecordingConsentHandler, revokeRecordingConsentHandler } from "../../src/consent/recording-consent";
import { revokeRecordingConsent } from "../../src/index";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const fft = functionsTest();
afterAll(() => fft.cleanup());

const V = RECORDING_CONSENT_VERSION;
const POLICY = 2;
const flags = { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true };

async function usuario(perfil: Record<string, unknown> = {}) {
  const { uid } = await auth.createUser({ email: `rrc-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    role: "independent", isMinor: false, consentStatus: "granted", policyVersion: POLICY, birthDate: "1990-01-01", ...perfil,
  });
  return uid;
}
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;
const consents = async (uid: string) =>
  Object.fromEntries((await db.collection("users").doc(uid).collection("consents").get()).docs.map((x) => [x.id, x.data()]));
const concedido = (over: Record<string, unknown> = {}) =>
  usuario({ recordingConsent: "granted", recordingConsentVersion: V, blurRequired: false, ...over });
const revocar = (uid: string) => revokeRecordingConsentHandler({ db }, uid);

describe("revokeRecordingConsent", () => {
  test("granted -> revoked: epoch+1, purga pendiente y registro append-only con action=revoked", async () => {
    const uid = await concedido();
    await db.collection("users").doc(uid).collection("consents").doc(`recording_sharing_v${V}_self_e0`).set({
      type: "recording_sharing", version: V, grantedBy: "self", timestamp: Timestamp.now(),
    });
    const antes = Date.now();
    expect(await revocar(uid)).toEqual({ recordingConsent: "revoked" });
    const p = await perfil(uid);
    expect(p).toMatchObject({ recordingConsent: "revoked", recordingConsentEpoch: 1, consentStatus: "granted", policyVersion: POLICY });
    expect(p.recordingPurge.pending).toBe(true);
    expect(Math.abs((p.recordingPurge.since as Timestamp).toMillis() - antes)).toBeLessThan(60_000);
    const c = await consents(uid);
    expect(Object.keys(c).sort()).toEqual([`recording_revocation_v${V}_self_e0`, `recording_sharing_v${V}_self_e0`]);
    expect(c[`recording_revocation_v${V}_self_e0`]).toMatchObject({
      type: "recording_revocation", action: "revoked", version: V, grantedBy: "self",
    });
    expect(Object.keys(c[`recording_revocation_v${V}_self_e0`]).sort()).toEqual(["action", "grantedBy", "timestamp", "type", "version"]);
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBeUndefined();
  });

  test("no toca el consentimiento base: consentStatus, policyVersion y consentEpoch intactos", async () => {
    const uid = await concedido({ consentEpoch: 4 });
    await revocar(uid);
    expect(await perfil(uid)).toMatchObject({ consentStatus: "granted", policyVersion: POLICY, consentEpoch: 4 });
  });

  test("un menor de 14 revoca por sí solo (la revocación protege datos)", async () => {
    const uid = await concedido({ isMinor: true, birthDate: "2015-01-01", blurRequired: true });
    await db.collection("users").doc(uid).collection("consents").doc(`recording_sharing_v${V}_guardian_e0`).set({
      type: "recording_sharing", version: V, grantedBy: "guardian", timestamp: Timestamp.now(),
    });
    expect(await revocar(uid)).toEqual({ recordingConsent: "revoked" });
    expect((await perfil(uid)).recordingConsent).toBe("revoked");
    expect(await consents(uid)).toHaveProperty(`recording_revocation_v${V}_self_e0`);
  });

  test("sin consentimiento (ausente = none): no-op sin escribir nada", async () => {
    const uid = await usuario();
    const antes = await perfil(uid);
    expect(await revocar(uid)).toEqual({ recordingConsent: "none" });
    expect(await perfil(uid)).toEqual(antes);
    expect(await consents(uid)).toEqual({});
  });

  test("repetir tras revocar es no-op: no sube la época ni duplica registros", async () => {
    const uid = await concedido();
    await revocar(uid);
    const p1 = await perfil(uid);
    const c1 = await consents(uid);
    expect(await revocar(uid)).toEqual({ recordingConsent: "revoked" });
    expect(await perfil(uid)).toEqual(p1);
    expect(await consents(uid)).toEqual(c1);
  });

  test("dos revocaciones simultáneas convergen: una época, un registro", async () => {
    const uid = await concedido();
    await Promise.all([revocar(uid), revocar(uid)]);
    expect((await perfil(uid)).recordingConsentEpoch).toBe(1);
    expect(Object.keys(await consents(uid))).toEqual([`recording_revocation_v${V}_self_e0`]);
  });

  test("reconsentir tras revocar crea un consent nuevo en la época 1 y conserva la traza", async () => {
    const uid = await usuario();
    const deps = { db, clock: () => new Date("2026-09-30T12:00:00Z"), flags, currentPolicyVersion: POLICY };
    await grantRecordingConsentHandler(deps, uid, { version: V });
    await revocar(uid);
    await grantRecordingConsentHandler(deps, uid, { version: V });
    expect(Object.keys(await consents(uid)).sort()).toEqual([
      `recording_revocation_v${V}_self_e0`, `recording_sharing_v${V}_self_e0`, `recording_sharing_v${V}_self_e1`,
    ]);
    expect(await perfil(uid)).toMatchObject({ recordingConsent: "granted", recordingConsentEpoch: 1 });
  });

  test("versión guardada ilegible: se registra como 0 y la revocación no se bloquea", async () => {
    const uid = await concedido({ recordingConsentVersion: "x" });
    await revocar(uid);
    expect(await consents(uid)).toHaveProperty("recording_revocation_v0_self_e0");
  });

  test("borrado en curso o sin perfil: NO_PROFILE", async () => {
    const uid = await concedido({ deletion: { state: "in_progress" } });
    await expect(revocar(uid)).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "NO_PROFILE" } });
    await expect(revocar("no-existe")).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "NO_PROFILE" } });
  });
});

describe("callable revokeRecordingConsent", () => {
  const llamar = revokeRecordingConsent && fft.wrap(revokeRecordingConsent);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const req = (uid: string, token: Record<string, unknown>): any => ({ data: {}, auth: { uid, token } });

  test("sin sesión -> unauthenticated; email no verificado -> rechazo sin escrituras", async () => {
    await expect(llamar({ data: {} } as never)).rejects.toMatchObject({ code: "unauthenticated" });
    const uid = await concedido();
    await expect(llamar(req(uid, { email_verified: false }))).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "EMAIL_NOT_VERIFIED" },
    });
    expect((await perfil(uid)).recordingConsent).toBe("granted");
  });

  test("usuario verificado: revoked", async () => {
    const uid = await concedido();
    expect(await llamar(req(uid, { email_verified: true }))).toEqual({ recordingConsent: "revoked" });
  });
});
