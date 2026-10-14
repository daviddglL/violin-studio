import { Timestamp } from "firebase-admin/firestore";
import { RECORDING_CONSENT_VERSION } from "../../src/config/identity";
import { grantRecordingConsentHandler, revokeRecordingByGuardian, revokeRecordingConsentHandler } from "../../src/consent/recording-consent";
import { guardianConsentHandler } from "../../src/guardian/confirm";
import { hmacEmail } from "../../src/common/hashing";
import { renderInvalidPage, renderRetryPage } from "../../src/guardian/page";
import { hashToken } from "../../src/guardian/token";
import { auth, authExists, consentDocs, db, deps, erased, get, PEPPER, perfil, post, req, resetState, setup, setupRecording, state } from "./guardian-helpers";

const INVALID = renderInvalidPage();
beforeEach(() => { resetState(); erased.length = 0; });

const accept = (s: { r: string; t: string }, over: Record<string, unknown> = {}) =>
  post({ r: s.r, t: s.t, action: "accept", declaration: "on", ...over });
const reject = (s: { r: string; t: string }, over: Record<string, unknown> = {}) =>
  post({ r: s.r, t: s.t, action: "reject", confirm: "yes", ...over });
const mails = async (uid: string, kind: string) => (await db.collection("mail").where("uid", "==", uid).where("kind", "==", kind).get()).docs;
const withFlags = (flags: Record<string, boolean>) => ({ ...deps(), flags: { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true, ...flags } });
const recordingConsents = async (uid: string) => (await consentDocs(uid)).filter((d) => d.id.startsWith("recording_sharing"));

describe("GET de la página de grabación (REQ-GRD-R02)", () => {
  test("muestra la finalidad de grabación, no consume nada y repetirlo no cambia el estado", async () => {
    const s = await setupRecording();
    const before = { u: await perfil(s.uid), r: await req(s.r) };
    for (let i = 0; i < 3; i++) {
      const res = await get({ r: s.r });
      expect(res.status).toBe(200);
      expect(res.body).toContain("grabaciones");
      expect(res.body).toContain("90");
      expect(res.body).not.toContain(s.t);
    }
    expect(await perfil(s.uid)).toEqual(before.u);
    expect(await req(s.r)).toEqual(before.r);
    expect((await req(s.r)).usedAt).toBeNull();
  });

  test("una solicitud de política sigue mostrando su página de política", async () => {
    const s = await setup();
    const res = await get({ r: s.r });
    expect(res.status).toBe(200);
    expect(res.body).not.toContain("grabaciones");
  });
});

describe("POST aceptar grabación", () => {
  test("crea el consent del tutor y recordingConsent=granted de forma atómica sin tocar consentStatus; enlace de revocación propio", async () => {
    const s = await setupRecording();
    const before = await perfil(s.uid);
    state.now = new Date(state.now.getTime() + 3600_000);
    const res = await accept(s);
    expect(res.status).toBe(200);
    expect(res.body).not.toBe(INVALID);

    const p = await perfil(s.uid);
    expect(p).toMatchObject({ recordingConsent: "granted", recordingConsentVersion: RECORDING_CONSENT_VERSION, blurRequired: true, consentStatus: "granted", policyVersion: 1 });
    expect(p.guardian).toEqual(before.guardian);
    const c = (await consentDocs(s.uid)).find((d) => d.id === `recording_sharing_v${RECORDING_CONSENT_VERSION}_guardian_e0`)!;
    expect(c.data()).toMatchObject({ type: "recording_sharing", version: RECORDING_CONSENT_VERSION, grantedBy: "guardian", declaration: "legal_guardian", guardianEmailHmac: hmacEmail(PEPPER, s.guardian) });
    expect(c.data().timestamp).toBeInstanceOf(Timestamp);

    const r = await req(s.r);
    expect(r).toMatchObject({ outcome: "accepted", revokeAttempts: 0, revokedAt: null });
    expect(r.usedAt.toMillis()).toBe(state.now.getTime());
    const [m] = await mails(s.uid, "guardian_revoke");
    const rev = m.data().message.text.match(/\/tutor\?r=(\S+)#t=([\w-]+)&a=revoke/)!;
    expect(rev[1]).toBe(s.r);
    expect(r.revokeTokenHash).toBe(hashToken(rev[2]));
    expect(m.data().to).toBe(s.guardian);
    expect(m.data().message.text).toContain("grabaciones");
    expect(JSON.stringify(state.logs)).not.toContain(rev[2]);
    expect(JSON.stringify(state.logs)).not.toContain(s.t);
    expect(JSON.stringify(state.logs)).not.toContain(s.uid);
  });

  test("el id del consent usa la época de grabación propia", async () => {
    const s = await setupRecording({ recordingConsentEpoch: 2, consentEpoch: 5 });
    expect((await accept(s)).status).toBe(200);
    expect((await consentDocs(s.uid)).map((d) => d.id)).toContain(`recording_sharing_v${RECORDING_CONSENT_VERSION}_guardian_e2`);
  });

  test("sin declaración -> 400 que no consume el token; luego sí acepta", async () => {
    const s = await setupRecording();
    expect((await post({ r: s.r, t: s.t, action: "accept" })).status).toBe(400);
    expect((await req(s.r)).usedAt).toBeNull();
    expect((await perfil(s.uid)).recordingConsent).toBe("parental_pending");
    expect((await accept(s)).status).toBe(200);
  });

  test("reutilizado, caducado, erróneo (5 fallos bloquean) -> genérico y sin cambios", async () => {
    const s = await setupRecording();
    expect((await accept(s)).status).toBe(200);
    expect((await accept(s)).body).toBe(INVALID); // un solo uso

    const e = await setupRecording();
    state.now = new Date(state.now.getTime() + 72 * 3600_000);
    expect((await accept(e)).body).toBe(INVALID); // now >= expiresAt
    expect((await perfil(e.uid)).recordingConsent).toBe("parental_pending");
    expect(await recordingConsents(e.uid)).toHaveLength(0);

    state.now = new Date("2026-03-01T10:00:00Z");
    const w = await setupRecording();
    for (let i = 1; i <= 5; i++) {
      expect((await accept({ r: w.r, t: "mal" })).body).toBe(INVALID);
      expect((await req(w.r)).attempts).toBe(i);
    }
    expect((await accept(w)).body).toBe(INVALID);
    expect((await perfil(w.uid)).recordingConsent).toBe("parental_pending");
  });

  test("tres POST válidos simultáneos -> exactamente uno acepta y un solo consent", async () => {
    const s = await setupRecording();
    const res = await Promise.all([accept(s), accept(s), accept(s)]);
    expect(res.filter((r) => r.body !== INVALID)).toHaveLength(1);
    expect(await recordingConsents(s.uid)).toHaveLength(1);
    expect(await mails(s.uid, "guardian_revoke")).toHaveLength(1);
  });

  test("cruce de finalidades: el token de una no vale para la otra y ninguna cambia", async () => {
    const pol = await setup();
    const rec = await setupRecording();
    expect((await accept({ r: rec.r, t: pol.t })).body).toBe(INVALID);
    expect((await accept({ r: pol.r, t: rec.t })).body).toBe(INVALID);
    expect((await req(rec.r)).attempts).toBe(1);
    expect((await req(pol.r)).attempts).toBe(1);
    expect(await perfil(rec.uid)).toMatchObject({ recordingConsent: "parental_pending", consentStatus: "granted" });
    expect(await perfil(pol.uid)).toMatchObject({ consentStatus: "parental_pending" });
    expect((await perfil(pol.uid)).recordingConsent).toBeUndefined();
    expect((await req(rec.r)).usedAt).toBeNull();
    expect((await req(pol.r)).usedAt).toBeNull();
  });

  test("aceptar la política no concede grabación", async () => {
    const pol = await setup();
    expect((await accept(pol)).status).toBe(200);
    expect((await perfil(pol.uid)).recordingConsent).toBeUndefined();
    expect(await recordingConsents(pol.uid)).toHaveLength(0);
  });
});

describe("POST aceptar grabación: el estado del menor debe seguir siendo el de la solicitud", () => {
  const sinCambios = async (s: { uid: string; r: string }, expected: Record<string, unknown>) => {
    expect(await perfil(s.uid)).toMatchObject(expected);
    expect((await req(s.r)).usedAt).toBeNull();
    expect(await recordingConsents(s.uid)).toHaveLength(0);
    expect(await mails(s.uid, "guardian_revoke")).toHaveLength(0);
  };

  test("base revocada tras la solicitud (revokeConsentCore no limpia parental_pending de grabación) -> genérico y sin escrituras", async () => {
    const s = await setupRecording();
    await db.collection("users").doc(s.uid).update({ consentStatus: "revoked" });
    expect((await accept(s)).body).toBe(INVALID);
    await sinCambios(s, { recordingConsent: "parental_pending", consentStatus: "revoked" });
  });

  test("política obsoleta o base pendiente -> genérico", async () => {
    const a = await setupRecording();
    await db.collection("users").doc(a.uid).update({ policyVersion: 0 });
    expect((await accept(a)).body).toBe(INVALID);
    await sinCambios(a, { recordingConsent: "parental_pending" });
    const b = await setupRecording();
    await db.collection("users").doc(b.uid).update({ consentStatus: "parental_pending" });
    expect((await accept(b)).body).toBe(INVALID);
    await sinCambios(b, { recordingConsent: "parental_pending" });
  });

  test("otro tutor dio después el consentimiento base (HMAC distinto) -> genérico", async () => {
    const s = await setupRecording();
    await db.collection("users").doc(s.uid).collection("consents").doc("guardian_privacy_policy_v1_guardian_e0")
      .update({ guardianEmailHmac: hmacEmail(PEPPER, "otro@example.com") });
    expect((await accept(s)).body).toBe(INVALID);
    await sinCambios(s, { recordingConsent: "parental_pending" });
  });

  test("el menor cumplió 14 (isMinor=false): la solicitud pendiente queda invalidada", async () => {
    const s = await setupRecording();
    await db.collection("users").doc(s.uid).update({ isMinor: false });
    expect((await get({ r: s.r })).body).toBe(INVALID);
    expect((await accept(s)).body).toBe(INVALID);
    expect((await reject(s)).body).toBe(INVALID);
    await sinCambios(s, { recordingConsent: "parental_pending", isMinor: false });
  });

  test("si el propio usuario ya concedió (14+) el enlace no sobrescribe nada", async () => {
    const s = await setupRecording();
    await db.collection("users").doc(s.uid).update({ isMinor: false, recordingConsent: "granted", recordingConsentVersion: 1 });
    expect((await accept(s)).body).toBe(INVALID);
    expect(await perfil(s.uid)).toMatchObject({ recordingConsent: "granted", recordingConsentVersion: 1 });
    expect(await recordingConsents(s.uid)).toHaveLength(0);
  });

  test("solicitud sustituida por otra posterior (recordingGuardian.requestId distinto) -> genérico", async () => {
    const s = await setupRecording();
    await db.collection("users").doc(s.uid).update({ "recordingGuardian.requestId": "OTRAOTRAOTRAOTRAOTRA" });
    expect((await accept(s)).body).toBe(INVALID);
    await sinCambios(s, { recordingConsent: "parental_pending" });
  });

  test("cuenta en borrado o con recordingConsent revocado -> genérico", async () => {
    const d = await setupRecording();
    await db.collection("users").doc(d.uid).update({ deletion: { state: "in_progress" } });
    expect((await accept(d)).body).toBe(INVALID);
    const r = await setupRecording();
    await db.collection("users").doc(r.uid).update({ recordingConsent: "revoked" });
    expect((await accept(r)).body).toBe(INVALID);
    await sinCambios(r, { recordingConsent: "revoked" });
  });

  test("MINOR_RECORDING_ENABLED apagado -> genérico sin consumir el token; al encenderlo acepta", async () => {
    const s = await setupRecording();
    const off = withFlags({ minorRecordingEnabled: false });
    const body = { r: s.r, t: s.t, action: "accept", declaration: "on" };
    expect((await guardianConsentHandler(off, { method: "POST", query: {}, body })).body).toBe(INVALID);
    expect((await guardianConsentHandler(off, { method: "GET", query: { r: s.r }, body: {} })).body).toBe(INVALID);
    await sinCambios(s, { recordingConsent: "parental_pending" });
    expect((await accept(s)).status).toBe(200);
  });

  test("purga de una revocación anterior pendiente -> 503 reintentable sin consumir el token; al terminar la purga acepta", async () => {
    const s = await setupRecording();
    await db.collection("users").doc(s.uid).update({ recordingPurge: { pending: true, since: Timestamp.fromDate(state.now), epoch: 0 } });
    const res = await accept(s);
    expect(res.status).toBe(503);
    expect(res.body).toBe(renderRetryPage());
    await sinCambios(s, { recordingConsent: "parental_pending" });
    await db.collection("users").doc(s.uid).update({ recordingPurge: { pending: false, since: Timestamp.fromDate(state.now), epoch: 0 } });
    expect((await accept(s)).status).toBe(200);
  });
});

describe("POST rechazar grabación (REQ-GRD-R03)", () => {
  test("cierra la solicitud, recordingConsent queda none y la cuenta NO se borra", async () => {
    const s = await setupRecording();
    const res = await reject(s);
    expect(res.status).toBe(200);
    expect(res.body).not.toBe(INVALID);
    expect(res.body).toMatch(/no se elimina/);
    expect(erased).toEqual([]);
    expect(await authExists(s.uid)).toBe(true);
    const p = await perfil(s.uid);
    expect(p.recordingConsent).toBeUndefined();
    expect(p).toMatchObject({ consentStatus: "granted", policyVersion: 1 });
    expect(p.deletion).toBeUndefined();
    expect(await req(s.r)).toMatchObject({ outcome: "rejected" });
    expect((await req(s.r)).usedAt).toBeInstanceOf(Timestamp);
    expect(await recordingConsents(s.uid)).toHaveLength(0);
    expect((await accept(s)).body).toBe(INVALID); // inutilizable
    expect((await reject(s)).body).toBe(INVALID);
    expect(JSON.stringify(state.logs)).not.toContain(s.uid);
  });

  test("rechazo con token erróneo -> genérico, attempts++ y nada cambia", async () => {
    const s = await setupRecording();
    expect((await reject(s, { t: "mal" })).body).toBe(INVALID);
    expect((await req(s.r)).attempts).toBe(1);
    expect(await perfil(s.uid)).toMatchObject({ recordingConsent: "parental_pending" });
    expect(erased).toEqual([]);
  });

  test("rechazar la política sigue borrando la cuenta (comportamiento vigente)", async () => {
    const s = await setup();
    expect((await reject(s)).status).toBe(200);
    expect(erased).toEqual([s.uid]);
  });
});

describe("revocación del tutor para la finalidad de grabación", () => {
  async function granted() {
    const s = await setupRecording();
    await accept(s);
    const [m] = await mails(s.uid, "guardian_revoke");
    const [, , rev] = m.data().message.text.match(/\/tutor\?r=(\S+)#t=([\w-]+)&a=revoke/)!;
    return { ...s, rev: rev as string };
  }
  const revoke = (s: { r: string; rev: string }, over: Record<string, unknown> = {}) => post({ r: s.r, t: s.rev, action: "revoke", ...over });

  test("W1: un enlace viejo del tutor no revoca una concesión posterior del propio usuario (revocó y re-concedió a los 14)", async () => {
    const s = await granted();
    await revokeRecordingConsentHandler({ db, log: () => undefined }, s.uid); // el menor revoca
    await db.collection("users").doc(s.uid).update({ isMinor: false, "recordingPurge.pending": false }); // cumple 14, purga terminada
    await grantRecordingConsentHandler(
      { db, clock: () => state.now, flags: { recordingsEnabled: true, videoEnabled: true, minorRecordingEnabled: true }, currentPolicyVersion: 1, log: () => undefined },
      s.uid, { version: RECORDING_CONSENT_VERSION },
    );
    const before = await perfil(s.uid);
    expect(before.recordingConsent).toBe("granted");
    expect((await get({ r: s.r })).body).toBe(INVALID);
    expect((await revoke(s)).body).toBe(INVALID);
    expect(await perfil(s.uid)).toEqual(before);
  });

  test("B5b.3: la revocación del tutor lanza el borrado efectivo (purga) del menor; si la purga falla la revocación consta igual", async () => {
    const s = await granted();
    const purged: string[] = [];
    const res = await guardianConsentHandler(
      { ...deps(), purgeRecordings: async (uid: string) => { purged.push(uid); throw new Error("x"); } },
      { method: "POST", query: {}, body: { r: s.r, t: s.rev, action: "revoke" } },
    );
    expect(res.status).toBe(200);
    expect(purged).toEqual([s.uid]);
    expect((await perfil(s.uid)).recordingConsent).toBe("revoked");
  });

  test("W1 (revision fresca B5b): revocacion ya guardada pero respuesta perdida (503) -> el reintento del tutor reanuda la purga pendiente", async () => {
    const s = await granted();
    await revokeRecordingByGuardian({ db, log: () => undefined, purgeRecordings: async () => { throw new Error("x"); } }, s.uid, s.r);
    expect((await perfil(s.uid)).recordingPurge.pending).toBe(true);
    const purged: string[] = [];
    await guardianConsentHandler(
      { ...deps(), purgeRecordings: async (uid: string) => void purged.push(uid) },
      { method: "POST", query: {}, body: { r: s.r, t: s.rev, action: "revoke" } },
    );
    expect(purged).toEqual([s.uid]);
  });

  test("W1: la revocación del tutor sigue funcionando tras una concesión del tutor sin intermedios", async () => {
    const s = await granted();
    expect((await get({ r: s.r })).status).toBe(200);
    expect((await revoke(s)).status).toBe(200);
    expect((await perfil(s.uid)).recordingConsent).toBe("revoked");
  });

  test("GET con el enlace de revocación muestra la página de grabaciones sin cambiar nada", async () => {
    const s = await granted();
    const before = await perfil(s.uid);
    const res = await get({ r: s.r });
    expect(res.status).toBe(200);
    expect(res.body).toContain("grabaciones");
    expect(res.body).toContain('name="action" value="revoke"');
    expect(await perfil(s.uid)).toEqual(before);
  });

  test("revoca solo la grabación: registro del tutor, época+1, purga pendiente y consentStatus/cuenta intactos", async () => {
    const s = await granted();
    const res = await revoke(s);
    expect(res.status).toBe(200);
    expect(res.body).toContain("grabaciones");
    const p = await perfil(s.uid);
    expect(p).toMatchObject({ recordingConsent: "revoked", recordingConsentEpoch: 1, consentStatus: "granted", policyVersion: 1 });
    expect(p.recordingPurge).toMatchObject({ pending: true, epoch: 0 });
    const rv = (await consentDocs(s.uid)).find((d) => d.id === `recording_revocation_v${RECORDING_CONSENT_VERSION}_guardian_e0`)!;
    expect(rv.data()).toMatchObject({ type: "recording_revocation", action: "revoked", grantedBy: "guardian" });
    expect((await req(s.r)).revokedAt).toBeInstanceOf(Timestamp);
    expect(await authExists(s.uid)).toBe(true);
    expect((await revoke(s)).body).toBe(INVALID); // un solo uso
  });

  test("el token de aceptación no revoca; token erróneo cuenta intento; un menor que ya cumplió 14 también puede ser revocado por su tutor", async () => {
    const s = await granted();
    expect((await revoke(s, { t: s.t })).body).toBe(INVALID);
    expect((await revoke(s, { t: "mal" })).body).toBe(INVALID);
    expect((await req(s.r)).revokeAttempts).toBe(2);
    expect((await perfil(s.uid)).recordingConsent).toBe("granted");
    await db.collection("users").doc(s.uid).update({ isMinor: false });
    expect((await revoke(s)).status).toBe(200);
    expect((await perfil(s.uid)).recordingConsent).toBe("revoked");
  });

  test("si la grabación ya no está granted (revocada por el menor) el enlace es genérico y no escribe", async () => {
    const s = await granted();
    await db.collection("users").doc(s.uid).update({ recordingConsent: "revoked" });
    expect((await revoke(s)).body).toBe(INVALID);
    expect((await consentDocs(s.uid)).some((d) => d.id.startsWith("recording_revocation"))).toBe(false);
  });

  test("los claims no cambian: la revocación de grabación no cierra sesión", async () => {
    const s = await granted();
    await auth.setCustomUserClaims(s.uid, { consentOk: true });
    await revoke(s);
    expect((await auth.getUser(s.uid)).customClaims).toEqual({ consentOk: true });
  });
});
