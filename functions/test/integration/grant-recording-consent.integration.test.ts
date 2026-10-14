import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import functionsTest from "firebase-functions-test";
import { CURRENT_POLICY_VERSION, RECORDING_CONSENT_VERSION } from "../../src/config/identity";
import { RecordingFlags } from "../../src/config/features";
import { grantRecordingConsentHandler } from "../../src/consent/recording-consent";
import { grantRecordingConsent } from "../../src/index";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const fft = functionsTest();
afterAll(() => fft.cleanup());

const HOY = new Date("2026-09-30T12:00:00Z");
const TODOS: RecordingFlags = { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true };
const POLICY = 2;
type Deps = Parameters<typeof grantRecordingConsentHandler>[0];
const d = (over: Partial<Deps> = {}): Deps => ({ db, clock: () => HOY, flags: TODOS, currentPolicyVersion: POLICY, ...over });
const V = RECORDING_CONSENT_VERSION;

async function usuario(perfil: Record<string, unknown> = {}) {
  const { uid } = await auth.createUser({ email: `grc-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    role: "independent", isMinor: false, consentStatus: "granted", policyVersion: POLICY, birthDate: "1990-01-01", ...perfil,
  });
  return uid;
}
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;
const consents = async (uid: string) =>
  Object.fromEntries((await db.collection("users").doc(uid).collection("consents").get()).docs.map((x) => [x.id, x.data()]));

async function sinEfectos(uid: string, antes: Record<string, unknown>) {
  expect(await perfil(uid)).toEqual(antes);
  expect(await consents(uid)).toEqual({});
}

describe("adulto y 14-17 con base vigente", () => {
  test("adulto: consent con id determinista, granted, versión, blurRequired=false; la base no cambia", async () => {
    const uid = await usuario();
    const antes = Date.now();
    expect(await grantRecordingConsentHandler(d(), uid, { version: V })).toEqual({ recordingConsent: "granted" });
    const c = await consents(uid);
    expect(Object.keys(c)).toEqual([`recording_sharing_v${V}_self_e0`]);
    const doc = c[`recording_sharing_v${V}_self_e0`];
    expect(Object.keys(doc).sort()).toEqual(["grantedBy", "timestamp", "type", "version"]);
    expect(doc).toMatchObject({ type: "recording_sharing", version: V, grantedBy: "self" });
    expect(Math.abs((doc.timestamp as Timestamp).toMillis() - antes)).toBeLessThan(60_000);
    expect(await perfil(uid)).toMatchObject({
      recordingConsent: "granted", recordingConsentVersion: V, blurRequired: false,
      consentStatus: "granted", policyVersion: POLICY,
    });
  });

  test("14-17 (U5): consiente por sí mismo y blurRequired=true", async () => {
    const uid = await usuario({ birthDate: "2010-03-01" });
    await grantRecordingConsentHandler(d(), uid, { version: V });
    expect(await perfil(uid)).toMatchObject({ recordingConsent: "granted", blurRequired: true });
  });

  test("recalcula blurRequired desde birthDate: un valor desfasado true pasa a false al ser adulto", async () => {
    const uid = await usuario({ blurRequired: true });
    await grantRecordingConsentHandler(d(), uid, { version: V });
    expect((await perfil(uid)).blurRequired).toBe(false);
  });

  test("idempotente: repetir no duplica consents ni cambia el perfil", async () => {
    const uid = await usuario();
    await grantRecordingConsentHandler(d(), uid, { version: V });
    const c1 = await consents(uid);
    const p1 = await perfil(uid);
    await grantRecordingConsentHandler(d(), uid, { version: V });
    expect(await consents(uid)).toEqual(c1);
    expect(await perfil(uid)).toEqual(p1);
  });

  test("llamadas simultáneas convergen en un solo consent", async () => {
    const uid = await usuario();
    await Promise.all([grantRecordingConsentHandler(d(), uid, { version: V }), grantRecordingConsentHandler(d(), uid, { version: V })]);
    expect(Object.keys(await consents(uid))).toHaveLength(1);
    expect((await perfil(uid)).recordingConsent).toBe("granted");
  });

  test("la época propia de grabación entra en el id (e3) sin mirar consentEpoch", async () => {
    const uid = await usuario({ recordingConsentEpoch: 3, consentEpoch: 9, recordingConsent: "revoked" });
    await grantRecordingConsentHandler(d(), uid, { version: V });
    expect(Object.keys(await consents(uid))).toEqual([`recording_sharing_v${V}_self_e3`]);
  });
});

describe("rechazos sin escrituras", () => {
  test("menor de 14: failed-precondition GUARDIAN_REQUIRED (solo el tutor)", async () => {
    const uid = await usuario({ isMinor: true, birthDate: "2015-01-01" });
    const antes = await perfil(uid);
    await expect(grantRecordingConsentHandler(d(), uid, { version: V })).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "GUARDIAN_REQUIRED" },
    });
    await sinEfectos(uid, antes);
  });

  test("isMinor ilegible: fail-closed como menor", async () => {
    const uid = await usuario({ isMinor: "no" });
    await expect(grantRecordingConsentHandler(d(), uid, { version: V })).rejects.toMatchObject({ code: "failed-precondition" });
    expect(await consents(uid)).toEqual({});
  });

  test.each([
    ["pending", { consentStatus: "pending", policyVersion: null }],
    ["revoked", { consentStatus: "revoked" }],
    ["parental_pending", { consentStatus: "parental_pending" }],
    ["granted con política antigua", { policyVersion: 1 }],
  ])("sin base de consentimiento vigente (%s): NO_ACTIVE_CONSENT", async (_n, parche) => {
    const uid = await usuario(parche);
    const antes = await perfil(uid);
    await expect(grantRecordingConsentHandler(d(), uid, { version: V })).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "NO_ACTIVE_CONSENT" },
    });
    await sinEfectos(uid, antes);
  });

  test("borrado en curso o sin perfil: NO_PROFILE", async () => {
    const uid = await usuario({ deletion: { state: "in_progress" } });
    await expect(grantRecordingConsentHandler(d(), uid, { version: V })).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "NO_PROFILE" },
    });
    await expect(grantRecordingConsentHandler(d(), "no-existe", { version: V })).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "NO_PROFILE" },
    });
  });

  test("RECORDINGS_ENABLED=false: RECORDING_DISABLED también para adultos", async () => {
    const uid = await usuario();
    await expect(
      grantRecordingConsentHandler(d({ flags: { ...TODOS, recordingsEnabled: false } }), uid, { version: V }),
    ).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "RECORDING_DISABLED" } });
    expect(await consents(uid)).toEqual({});
  });

  test("MINOR_RECORDING_ENABLED=false: rechaza a <18 (16 años) pero no a adultos", async () => {
    const flags = { ...TODOS, minorRecordingEnabled: false };
    const de16 = await usuario({ birthDate: "2010-03-01" });
    await expect(grantRecordingConsentHandler(d({ flags }), de16, { version: V })).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "RECORDING_DISABLED" },
    });
    expect(await consents(de16)).toEqual({});
    const adulto = await usuario();
    await grantRecordingConsentHandler(d({ flags }), adulto, { version: V });
    expect((await perfil(adulto)).recordingConsent).toBe("granted");
  });

  test("MINOR_RECORDING_ENABLED=false y birthDate ilegible: fail-closed (cuenta como <18)", async () => {
    const uid = await usuario({ birthDate: "basura" });
    await expect(
      grantRecordingConsentHandler(d({ flags: { ...TODOS, minorRecordingEnabled: false } }), uid, { version: V }),
    ).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "RECORDING_DISABLED" } });
  });

  test("versión distinta de la vigente: mayor invalid-argument, menor failed-precondition", async () => {
    const uid = await usuario();
    await expect(grantRecordingConsentHandler(d(), uid, { version: V + 1 })).rejects.toMatchObject({ code: "invalid-argument" });
    await expect(grantRecordingConsentHandler(d({ currentRecordingVersion: 3 }), uid, { version: 2 })).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "RECORDING_CONSENT_OUTDATED" },
    });
    for (const v of [0, -1, 1.5, "1", null, undefined]) {
      await expect(grantRecordingConsentHandler(d(), uid, { version: v })).rejects.toMatchObject({ code: "invalid-argument" });
    }
    await expect(grantRecordingConsentHandler(d(), uid, null)).rejects.toMatchObject({ code: "invalid-argument" });
    expect(await consents(uid)).toEqual({});
  });
});

describe("callable grantRecordingConsent", () => {
  const llamar = grantRecordingConsent && fft.wrap(grantRecordingConsent);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const req = (uid: string, token: Record<string, unknown>, data: unknown = { version: V }): any => ({ data, auth: { uid, token } });

  test("sin sesión -> unauthenticated", async () => {
    await expect(llamar({ data: { version: V } } as never)).rejects.toMatchObject({ code: "unauthenticated" });
  });

  test("email no verificado -> rechazo y cero escrituras", async () => {
    const uid = await usuario();
    await expect(llamar(req(uid, { email_verified: false }))).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "EMAIL_NOT_VERIFIED" },
    });
    expect(await consents(uid)).toEqual({});
  });

  test("usuario verificado: granted (los interruptores del emulador/test están encendidos)", async () => {
    const uid = await usuario({ policyVersion: CURRENT_POLICY_VERSION });
    expect(await llamar(req(uid, { email_verified: true }))).toEqual({ recordingConsent: "granted" });
    expect((await perfil(uid)).recordingConsent).toBe("granted");
  });
});
