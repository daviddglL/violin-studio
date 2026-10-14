import { getApps } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { RECORDING_CONSENT_VERSION } from "../../src/config/identity";
import { grantRecordingConsentHandler, revokeRecordingByGuardian, revokeRecordingConsentHandler } from "../../src/consent/recording-consent";
import { revokeConsentCore } from "../../src/consent/revoke-consent";
import { finalizeRecordingHandler } from "../../src/recordings/finalize";
import { bucketSweepStore } from "../../src/recordings/orphan-sweep";
import { purgeRevokedRecordings } from "../../src/recordings/revoke-purge";
import { bucket, db, exists, idOf, NOW, recDoc, reviewCount, rnd, seedRecording, seedReview, seedUser, upload, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

const auth = getAuth(getApps()[0]);
const store = bucketSweepStore(bucket);
const V = RECORDING_CONSENT_VERSION;
const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
let logs: { message: string; data: Record<string, unknown> }[];
const log = (message: string, data: Record<string, unknown>) => void logs.push({ message, data });
const purge = (uid: string) => purgeRevokedRecordings({ db, store, log }, uid);
const finalize = (ev: { name: string; generation: string }) => finalizeRecordingHandler({ db, store, clock: () => NOW, flags, log }, ev);

/** Usuario con consentimiento de grabacion vigente (epoca 0) y una grabacion con revision. */
async function conGrabaciones(over: Record<string, unknown> = {}) {
  const uid = rnd("u");
  await seedUser(uid, { recordingConsentVersion: V, blurRequired: false, consentStatus: "granted", policyVersion: 2, birthDate: "1990-01-01", ...over });
  await auth.createUser({ uid }).catch(() => undefined);
  const ev = await upload(uid);
  const id = await seedRecording(uid, ev);
  await seedReview(uid, id);
  return { uid, ev, id };
}

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
  await bucket.deleteFiles({ force: true });
});

describe("revokeRecordingConsent borra de inmediato (REQ-REC-09)", () => {
  test("tras revocar no quedan objeto, doc ni revisiones, pending baja y la finalizacion posterior se rechaza (borrando el objeto)", async () => {
    const { uid, ev, id } = await conGrabaciones();
    const res = await revokeRecordingConsentHandler({ db, log, purgeRecordings: purge }, uid);
    expect(res).toEqual({ recordingConsent: "revoked" });
    expect(await exists(ev.name)).toBe(false);
    expect(await recDoc(uid, id)).toBeUndefined();
    expect(await reviewCount(uid, id)).toBe(0);
    expect((await userDoc(uid))?.recordingPurge).toMatchObject({ pending: false, epoch: 0 });

    // subida en vuelo (sellada con la epoca anterior) que finaliza despues: rechazada y sin objeto
    const late = await upload(uid);
    expect(await finalize(late)).toBe("rejected");
    expect(await exists(late.name)).toBe(false);
    expect(await recDoc(uid, idOf(late.name))).toMatchObject({ status: "rejected", rejectReason: "consent" });
  });

  test("el fallo de la purga no falla la revocacion (queda pending); repetir la llamada la reanuda y el resultado es el de un borrado sin fallos", async () => {
    const { uid, ev, id } = await conGrabaciones();
    const boom = async () => {
      throw Object.assign(new Error(`boom ${uid}`), { code: 503 });
    };
    const res = await revokeRecordingConsentHandler({ db, log, purgeRecordings: boom }, uid);
    expect(res).toEqual({ recordingConsent: "revoked" });
    expect((await userDoc(uid))?.recordingPurge.pending).toBe(true);
    expect(await recDoc(uid, id)).toBeDefined();
    expect(JSON.stringify(logs)).not.toContain(uid);

    // reintento del callable con el estado ya `revoked`: completa la purga pendiente
    expect(await revokeRecordingConsentHandler({ db, log, purgeRecordings: purge }, uid)).toEqual({ recordingConsent: "revoked" });
    expect(await exists(ev.name)).toBe(false);
    expect(await recDoc(uid, id)).toBeUndefined();
    expect((await userDoc(uid))?.recordingPurge.pending).toBe(false);
  });

  test("re-consentir esta bloqueado (TEMPORARILY_UNAVAILABLE) mientras la purga esta pendiente y se permite al terminar", async () => {
    const { uid } = await conGrabaciones();
    await revokeRecordingConsentHandler({ db, log, purgeRecordings: async () => undefined }, uid); // sin purgar
    const grant = () => grantRecordingConsentHandler({ db, clock: () => NOW, flags, log }, uid, { version: V });
    await expect(grant()).rejects.toMatchObject({ code: "unavailable" });
    await purge(uid);
    await expect(grant()).resolves.toEqual({ recordingConsent: "granted" });
    expect(await userDoc(uid)).toMatchObject({ recordingConsent: "granted", recordingConsentEpoch: 1 });
  });

  test("re-consentir y volver a revocar: cada purga alcanza solo hasta la epoca que cierra", async () => {
    const { uid } = await conGrabaciones();
    await revokeRecordingConsentHandler({ db, log, purgeRecordings: purge }, uid);
    await grantRecordingConsentHandler({ db, clock: () => NOW, flags, log }, uid, { version: V });
    const ev = await upload(uid, { meta: { recordingConsentEpoch: "1" } });
    const id = await seedRecording(uid, ev, { recordingConsentEpoch: 1 });
    expect(await purge(uid)).toMatchObject({ outcome: "none" }); // nada pendiente: la grabacion nueva no se toca
    expect(await recDoc(uid, id)).toBeDefined();
    await revokeRecordingConsentHandler({ db, log, purgeRecordings: purge }, uid);
    expect(await recDoc(uid, id)).toBeUndefined();
    expect((await userDoc(uid))?.recordingPurge).toMatchObject({ pending: false, epoch: 1 });
  });

  test("concurrencia: revocar a la vez que finaliza una subida deja siempre sin grabaciones vivas ni objetos", async () => {
    for (let i = 0; i < 4; i++) {
      const { uid } = await conGrabaciones();
      const ev = await upload(uid);
      await Promise.all([revokeRecordingConsentHandler({ db, log, purgeRecordings: purge }, uid), finalize(ev)]);
      await purge(uid); // si la finalizacion gano la carrera, el reintento/purga diaria la barre
      expect(await exists(ev.name)).toBe(false);
      const left = (await db.collection("users").doc(uid).collection("recordings").get()).docs.filter((d) => d.data().status === "ready");
      expect(left).toHaveLength(0);
      expect((await userDoc(uid))?.recordingPurge.pending).toBe(false);
    }
  });
});

describe("las otras vias de revocacion tambien purgan", () => {
  test("revocar el consentimiento base (revokeConsentCore) revoca y purga la grabacion", async () => {
    const { uid, ev, id } = await conGrabaciones();
    await revokeConsentCore({ db, auth, log, purgeRecordings: purge }, uid, "self");
    expect(await exists(ev.name)).toBe(false);
    expect(await recDoc(uid, id)).toBeUndefined();
    expect((await userDoc(uid))?.recordingPurge.pending).toBe(false);
  });

  test("sin grabacion concedida, revocar el consentimiento base no lanza la purga", async () => {
    const { uid } = await conGrabaciones({ recordingConsent: "none" });
    let called = 0;
    await revokeConsentCore({ db, auth, log, purgeRecordings: async () => void called++ }, uid, "self");
    expect(called).toBe(0);
  });

  test("la revocacion del tutor (revokeRecordingByGuardian) purga y un fallo de la purga no la deshace", async () => {
    const { uid, ev, id } = await conGrabaciones({ recordingGuardian: { requestId: "req1" } });
    await revokeRecordingByGuardian({ db, log, purgeRecordings: async () => { throw new Error("x"); } }, uid, "req1");
    expect((await userDoc(uid))?.recordingConsent).toBe("revoked");
    expect(await recDoc(uid, id)).toBeDefined();
    await purge(uid);
    expect(await exists(ev.name)).toBe(false);
  });
});
