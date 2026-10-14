import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { reevaluateConsent } from "../../src/consent/reevaluate";
import { revokeConsentCore } from "../../src/consent/revoke-consent";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const deps = { db, auth, currentVersion: 2 };
const RV = 1;

async function usuario(perfil: Record<string, unknown> = {}) {
  const { uid } = await auth.createUser({ email: `rcr-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    role: "independent", isMinor: false, consentStatus: "granted", policyVersion: 2, birthDate: "1990-01-01", ...perfil,
  });
  return uid;
}
const conGrabacion = (over: Record<string, unknown> = {}) =>
  usuario({ recordingConsent: "granted", recordingConsentVersion: RV, blurRequired: false, ...over });
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;
const consents = async (uid: string) =>
  Object.fromEntries((await db.collection("users").doc(uid).collection("consents").get()).docs.map((x) => [x.id, x.data()]));

describe("revokeConsentCore revoca también la grabación (REQ-CON-R03, 'Revocación')", () => {
  test("propio: política y grabación revocadas en la misma operación, con purga pendiente", async () => {
    const uid = await conGrabacion();
    await revokeConsentCore(deps, uid, "self");
    const p = await perfil(uid);
    expect(p).toMatchObject({ consentStatus: "revoked", consentEpoch: 1, recordingConsent: "revoked", recordingConsentEpoch: 1 });
    expect(p.recordingPurge.pending).toBe(true);
    expect((p.recordingPurge.since as Timestamp).toMillis()).toBeGreaterThan(Date.now() - 60_000);
    expect(Object.keys(await consents(uid)).sort()).toEqual([
      `recording_revocation_v${RV}_self_e0`, "revocation_v2_self_e0",
    ]);
    expect((await consents(uid))[`recording_revocation_v${RV}_self_e0`]).toMatchObject({
      type: "recording_revocation", action: "revoked", grantedBy: "self",
    });
  });

  test("del tutor: la revocación de grabación queda registrada como guardian", async () => {
    const uid = await conGrabacion({ isMinor: true, birthDate: "2015-01-01", blurRequired: true });
    await revokeConsentCore(deps, uid, "guardian");
    expect((await perfil(uid)).recordingConsent).toBe("revoked");
    expect(await consents(uid)).toHaveProperty(`recording_revocation_v${RV}_guardian_e0`);
  });

  test.each([
    ["ausente", {}],
    ["revoked", { recordingConsent: "revoked", recordingConsentEpoch: 3 }],
    ["parental_pending", { recordingConsent: "parental_pending" }],
  ])("recordingConsent %s: la revocación de política no escribe nada de grabación", async (_n, extra) => {
    const uid = await usuario(extra);
    const antes = await perfil(uid);
    await revokeConsentCore(deps, uid, "self");
    const p = await perfil(uid);
    expect(p.recordingConsent).toBe(antes.recordingConsent);
    expect(p.recordingConsentEpoch).toBe(antes.recordingConsentEpoch);
    expect(p.recordingPurge).toBeUndefined();
    expect(Object.keys(await consents(uid))).toEqual(["revocation_v2_self_e0"]);
  });

  test("atómico: si falla el registro de grabación no se revoca la política", async () => {
    const uid = await conGrabacion();
    // Colisión forzada: tx.create falla con ALREADY_EXISTS y la transacción entera se descarta.
    await db.collection("users").doc(uid).collection("consents").doc(`recording_revocation_v${RV}_self_e0`).set({ x: 1 });
    await expect(revokeConsentCore(deps, uid, "self")).rejects.toBeDefined();
    expect(await perfil(uid)).toMatchObject({ consentStatus: "granted", recordingConsent: "granted" });
    expect((await perfil(uid)).consentEpoch).toBeUndefined();
    expect(Object.keys(await consents(uid))).toEqual([`recording_revocation_v${RV}_self_e0`]);
  });
});

describe("versiones independientes (REQ-CON-R03)", () => {
  test("bump de la política: pasa a pending pero recordingConsent sigue granted y sin purga", async () => {
    const uid = await conGrabacion({ policyVersion: 1 });
    await reevaluateConsent({ db, auth, currentVersion: 2 }, uid);
    expect(await perfil(uid)).toMatchObject({ consentStatus: "pending", recordingConsent: "granted", recordingConsentVersion: RV });
    expect((await perfil(uid)).recordingPurge).toBeUndefined();
  });

  test("bump del consentimiento de grabación: recordingConsent pasa a none sin borrar nada ni tocar la política", async () => {
    const uid = await conGrabacion();
    await reevaluateConsent({ db, auth, currentVersion: 2, currentRecordingVersion: 2 }, uid);
    const p = await perfil(uid);
    expect(p).toMatchObject({ consentStatus: "granted", policyVersion: 2 });
    expect("recordingConsent" in p).toBe(false);
    expect(p.recordingPurge).toBeUndefined();
    expect(await consents(uid)).toEqual({});
  });

  test("versión de grabación vigente, ausente o futura: sin cambios; revoked/parental_pending no se tocan", async () => {
    for (const extra of [
      { recordingConsentVersion: 2 }, { recordingConsentVersion: 3 },
    ]) {
      const uid = await conGrabacion(extra);
      const antes = await perfil(uid);
      await reevaluateConsent({ db, auth, currentVersion: 2, currentRecordingVersion: 2 }, uid);
      expect(await perfil(uid)).toEqual(antes);
    }
    const revocado = await usuario({ recordingConsent: "revoked", recordingConsentVersion: 1, recordingConsentEpoch: 1 });
    const antes = await perfil(revocado);
    await reevaluateConsent({ db, auth, currentVersion: 2, currentRecordingVersion: 2 }, revocado);
    expect(await perfil(revocado)).toEqual(antes);
  });

  test("versión guardada ilegible con granted: fail-closed a none", async () => {
    const uid = await conGrabacion({ recordingConsentVersion: "x" });
    await reevaluateConsent({ db, auth, currentVersion: 2 }, uid);
    expect("recordingConsent" in (await perfil(uid))).toBe(false);
  });

  test("la reevaluación del bump de grabación también cubre a quien tiene la política en pending", async () => {
    const uid = await conGrabacion({ consentStatus: "pending", policyVersion: 1 });
    await reevaluateConsent({ db, auth, currentVersion: 2, currentRecordingVersion: 2 }, uid);
    expect("recordingConsent" in (await perfil(uid))).toBe(false);
    expect((await perfil(uid)).consentStatus).toBe("pending");
  });
});
