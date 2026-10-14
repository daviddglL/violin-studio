import { Timestamp } from "firebase-admin/firestore";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { hmacEmail, sha256Hex } from "../../src/common/hashing";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { requestGuardianConsentHandler } from "../../src/guardian/request";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const PEPPER = "t".repeat(32);
let seq = 0;
const uniq = () => `t${Date.now()}${seq++}@example.com`;

let now = new Date("2026-03-01T10:00:00Z");
let flags = { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true };
let guardianFlowEnabled = true;
const logs: Array<[string, Record<string, unknown>]> = [];
const deps = () => ({
  db, pepper: PEPPER, linkBaseUrl: "https://x.app", guardianFlowEnabled, flags,
  clock: () => now, log: (m: string, d: Record<string, unknown>) => void logs.push([m, d]),
});

interface Minor { uid: string; email: string; guardian: string }
/** Menor con consentimiento base dado por un tutor (su HMAC consta en el consent `guardian_privacy_policy`). */
const minor = async (perfil: Record<string, unknown> = {}, withBase = true): Promise<Minor> => {
  const email = `m-${Date.now()}-${Math.random()}@example.com`;
  const { uid } = await auth.createUser({ email, password: "Passw0rd!x" });
  const guardian = uniq();
  await db.collection("users").doc(uid).set({
    displayName: "Ana", locale: "es", isMinor: true, birthDate: "2016-05-01", consentStatus: "granted",
    policyVersion: CURRENT_POLICY_VERSION, ...perfil,
  });
  if (withBase) {
    await db.collection("users").doc(uid).collection("consents").doc(`guardian_privacy_policy_v${CURRENT_POLICY_VERSION}_guardian_e0`).set({
      type: "guardian_privacy_policy", version: CURRENT_POLICY_VERSION, grantedBy: "guardian", guardianEmailHmac: hmacEmail(PEPPER, guardian),
    });
  }
  return { uid, email, guardian };
};
const ask = (u: Minor, data: Record<string, unknown> = {}) =>
  requestGuardianConsentHandler(deps(), u.uid, u.email, { guardianEmail: u.guardian, purpose: "recording_sharing", ...data });
const where = async (col: string, uid: string) => (await db.collection(col).where("uid", "==", uid).get()).docs;
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;
const sinEscrituras = async (u: Minor, before: Record<string, unknown>) => {
  expect(await where("guardianRequests", u.uid)).toHaveLength(0);
  expect(await where("mail", u.uid)).toHaveLength(0);
  expect(await perfil(u.uid)).toEqual(before);
};

beforeEach(() => {
  now = new Date("2026-03-01T10:00:00Z");
  flags = { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true };
  guardianFlowEnabled = true;
  logs.length = 0;
});

describe("requestGuardianConsent purpose=recording_sharing (REQ-GRD-R01)", () => {
  test("menor <14 granted: solicitud con purpose, mail con el enlace, consentStatus intacto y recordingConsent=parental_pending", async () => {
    const u = await minor();
    const before = await perfil(u.uid);
    await expect(ask(u)).resolves.toMatchObject({ status: "sent" });

    const p = await perfil(u.uid);
    expect(p.consentStatus).toBe("granted");
    expect(p.policyVersion).toBe(CURRENT_POLICY_VERSION);
    expect(p.guardian).toEqual(before.guardian); // la solicitud de política no se toca
    expect(p.recordingConsent).toBe("parental_pending");

    const [req] = await where("guardianRequests", u.uid);
    expect(req.data()).toMatchObject({ uid: u.uid, purpose: "recording_sharing", usedAt: null, outcome: null, supersededAt: null, attempts: 0 });
    expect(req.data().expiresAt.toMillis()).toBe(now.getTime() + 72 * 3600_000);
    expect(req.data().guardianEmailHmac).toBe(hmacEmail(PEPPER, u.guardian));
    expect(p.recordingGuardian.requestId).toBe(req.id);
    expect(p.recordingGuardian.requestedAt).toBeInstanceOf(Timestamp);

    const [mail] = await where("mail", u.uid);
    expect(mail.data()).toMatchObject({ to: u.guardian, uid: u.uid });
    const link = mail.data().message.text.match(/https:\/\/x\.app\/tutor\?r=(\S+)#t=([\w-]+)/)!;
    expect(link[1]).toBe(req.id);
    expect(sha256Hex(link[2])).toBe(req.data().tokenHash);
    expect(JSON.stringify(logs)).not.toContain(link[2]);
    expect(JSON.stringify(logs)).not.toContain(u.uid);
  });

  test("la solicitud de política (sin purpose) sigue guardando purpose=privacy_policy", async () => {
    const u = await minor({ consentStatus: "pending", policyVersion: null }, false);
    await requestGuardianConsentHandler(deps(), u.uid, u.email, { guardianEmail: u.guardian });
    const [req] = await where("guardianRequests", u.uid);
    expect(req.data().purpose).toBe("privacy_policy");
    expect((await perfil(u.uid)).consentStatus).toBe("parental_pending");
    expect((await perfil(u.uid)).recordingConsent).toBeUndefined();
  });

  test.each([["parental_pending"], ["pending"], ["revoked"]])("base %s -> failed-precondition sin escrituras", async (estado) => {
    const u = await minor({ consentStatus: estado });
    const before = await perfil(u.uid);
    await expect(ask(u)).rejects.toMatchObject({ code: "failed-precondition" });
    await sinEscrituras(u, before);
  });

  test("base granted con política obsoleta -> NO_ACTIVE_CONSENT sin escrituras", async () => {
    const u = await minor({ policyVersion: CURRENT_POLICY_VERSION - 1 });
    const before = await perfil(u.uid);
    await expect(ask(u)).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "NO_ACTIVE_CONSENT" } });
    await sinEscrituras(u, before);
  });

  test.each([[false], ["no"]])("isMinor=%s (14-17 o adulto) -> failed-precondition NOT_MINOR sin escrituras", async (isMinor) => {
    const u = await minor({ isMinor, birthDate: "2010-01-01" });
    const before = await perfil(u.uid);
    await expect(ask(u)).rejects.toMatchObject({ code: "failed-precondition", details: { reason: "NOT_MINOR" } });
    await sinEscrituras(u, before);
  });

  test("grabación ya concedida -> CONSENT_ALREADY_GRANTED; purga pendiente -> unavailable", async () => {
    const g = await minor({ recordingConsent: "granted" });
    await expect(ask(g)).rejects.toMatchObject({ details: { reason: "CONSENT_ALREADY_GRANTED" } });
    const p = await minor({ recordingConsent: "revoked", recordingPurge: { pending: true, since: Timestamp.fromDate(now), epoch: 0 } });
    await expect(ask(p)).rejects.toMatchObject({ code: "unavailable", details: { reason: "TEMPORARILY_UNAVAILABLE" } });
    expect(await where("mail", g.uid)).toHaveLength(0);
    expect(await where("mail", p.uid)).toHaveLength(0);
  });

  test("revocada de verdad (sin purga pendiente) puede volver a pedirse", async () => {
    const u = await minor({ recordingConsent: "revoked", recordingConsentEpoch: 1 });
    await expect(ask(u)).resolves.toMatchObject({ status: "sent" });
    expect((await perfil(u.uid)).recordingConsent).toBe("parental_pending");
  });

  test.each([
    ["RECORDINGS_ENABLED apagado", { recordingsEnabled: false }, true],
    ["MINOR_RECORDING_ENABLED apagado", { minorRecordingEnabled: false }, true],
    ["GUARDIAN_FLOW_ENABLED apagado", {}, false],
  ])("%s -> rechazo sin escribir mail/", async (_n, over, flow) => {
    flags = { ...flags, ...over };
    guardianFlowEnabled = flow;
    const u = await minor();
    const before = await perfil(u.uid);
    await expect(ask(u)).rejects.toMatchObject({ code: "failed-precondition" });
    await sinEscrituras(u, before);
  });

  test.each([["video"], [123], [null], [""]])("finalidad desconocida %p -> invalid-argument sin escrituras", async (purpose) => {
    const u = await minor();
    const before = await perfil(u.uid);
    await expect(ask(u, { purpose })).rejects.toMatchObject({ code: "invalid-argument" });
    await sinEscrituras(u, before);
  });
});
