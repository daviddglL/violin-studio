import { Timestamp } from "firebase-admin/firestore";
import { renderInvalidPage } from "../../src/guardian/page";
import { hashToken } from "../../src/guardian/token";
import { db, erased, get, perfil, post, req, resetState, setup, state } from "./guardian-helpers";

const DAY = 24 * 3600_000;
beforeEach(() => { resetState(); erased.length = 0; });

const accept = (s: { r: string; t: string }) => post({ r: s.r, t: s.t, action: "accept", declaration: "on" });
const mails = async (uid: string, kind: string) => (await db.collection("mail").where("uid", "==", uid).where("kind", "==", kind).get()).docs;
/** Acepta y devuelve el token de revocación leído del segundo correo (único sitio donde existe en claro). */
async function accepted() {
  const s = await setup();
  expect((await accept(s)).status).toBe(200);
  const [m] = await mails(s.uid, "guardian_revoke");
  const [, , rev] = m.data().message.text.match(/\/tutor\?r=(\S+)#t=([\w-]+)&a=revoke/)!;
  return { ...s, rev: rev as string, mail: m };
}

describe("3c.3 enlace de revocación al aceptar", () => {
  test("la solicitud guarda mailId; al aceptar guarda solo el hash del token de revocación (30 d) y amplía la TTL", async () => {
    const s = await setup();
    const [first] = await mails(s.uid, "guardian_consent");
    expect((await req(s.r)).mailId).toBe(first.id);
    await accept(s);
    const [m] = await mails(s.uid, "guardian_revoke");
    const rev = m.data().message.text.match(/#t=([\w-]+)&a=revoke/)![1];
    const r = await req(s.r);
    expect(r.revokeTokenHash).toBe(hashToken(rev));
    expect(r.revokeTokenHash).not.toBe(r.tokenHash);
    expect(rev).not.toBe(s.t);
    expect(r).toMatchObject({ revokeAttempts: 0, revokedAt: null, outcome: "accepted" });
    expect(r.revokeExpiresAt.toMillis()).toBe(state.now.getTime() + 30 * DAY);
    expect(r.expireAt.toMillis()).toBe(r.revokeExpiresAt.toMillis() + 7 * DAY);
    expect(JSON.stringify(r)).not.toContain(rev); // el token en claro solo vive en mail/
    expect(JSON.stringify(state.logs)).not.toContain(rev);
  });

  test("segundo mail/: mismo destinatario que el primero, uid, enlace con a=revoke y expireAt 72 h", async () => {
    const s = await setup();
    const [first] = await mails(s.uid, "guardian_consent");
    await accept(s);
    const docs = await mails(s.uid, "guardian_revoke");
    expect(docs).toHaveLength(1);
    const m = docs[0].data();
    expect(m).toMatchObject({ to: first.data().to, uid: s.uid, kind: "guardian_revoke" });
    expect(m.expireAt.toMillis()).toBe(state.now.getTime() + 72 * 3600_000);
    expect(m.message.text).toContain(`/tutor?r=${s.r}#t=`);
    expect(m.message.text).toMatch(/#t=[A-Za-z0-9_-]{43}&a=revoke/);
    expect(m.message.text).not.toContain(s.t); // no reutiliza el token de aceptación
    expect((await mails(s.uid, "guardian_consent"))).toHaveLength(1);
  });

  test("token erróneo o aceptación fallida: no hay token ni mail de revocación", async () => {
    const s = await setup();
    await post({ r: s.r, t: "mal", action: "accept", declaration: "on" });
    await post({ r: s.r, t: s.t, action: "accept" }); // sin declaración
    expect(await mails(s.uid, "guardian_revoke")).toHaveLength(0);
    expect((await req(s.r)).revokeTokenHash).toBeUndefined();
  });

  test("solicitud sin mailId (anterior a esta versión) o mail origen desaparecido: acepta igual, sin revocación, con log sin PII", async () => {
    const a = await setup();
    await db.collection("guardianRequests").doc(a.r).update({ mailId: null });
    const b = await setup();
    const [first] = await mails(b.uid, "guardian_consent");
    await first.ref.delete();
    for (const s of [a, b]) {
      expect((await accept(s)).status).toBe(200);
      expect(await mails(s.uid, "guardian_revoke")).toHaveLength(0);
      expect((await req(s.r)).revokeTokenHash).toBeUndefined();
      expect((await db.collection("users").doc(s.uid).get()).data()?.consentStatus).toBe("granted");
    }
    expect(state.logs.filter(([m]) => m === "guardianConsent.noRevokeLink")).toHaveLength(2);
    expect(JSON.stringify(state.logs)).not.toContain(a.uid);
  });
});

const INVALID = renderInvalidPage();
describe("3c.4 GET de revocación", () => {
  test("enlace de revocación vigente: 200 con la página de revocar (no la de aceptar), sin token en el HTML y sin cambiar nada", async () => {
    const s = await accepted();
    const antes = [await req(s.r), await perfil(s.uid)];
    const res = await get({ r: s.r });
    expect(res.status).toBe(200);
    expect(res.body).toContain('name="action" value="revoke"');
    expect(res.body).not.toContain('value="accept"');
    expect(res.body).not.toContain(s.rev);
    expect(res.headers["Cache-Control"]).toBe("no-store");
    expect([await req(s.r), await perfil(s.uid)]).toEqual(antes);
  });

  test("antes de aceptar sigue la página de aceptar/rechazar (el modo lo decide el servidor, no el fragmento)", async () => {
    const s = await setup();
    const res = await get({ r: s.r, a: "revoke" });
    expect(res.body).toContain('value="accept"');
    expect(res.body).not.toContain('value="revoke"');
  });

  test("inválido -> 404 genérico idéntico: caducado, bloqueado, ya revocado, usuario no granted, borrado en curso, solicitud sustituida, sin token de revocación", async () => {
    const casos: Array<(s: Awaited<ReturnType<typeof accepted>>) => Promise<void>> = [
      async () => { state.now = new Date(state.now.getTime() + 30 * DAY); },
      async (s) => void (await db.collection("guardianRequests").doc(s.r).update({ revokeAttempts: 5 })),
      async (s) => void (await db.collection("guardianRequests").doc(s.r).update({ revokedAt: Timestamp.fromDate(state.now) })),
      async (s) => void (await db.collection("users").doc(s.uid).update({ consentStatus: "revoked" })),
      async (s) => void (await db.collection("users").doc(s.uid).update({ consentStatus: "parental_pending" })),
      async (s) => void (await db.collection("users").doc(s.uid).update({ deletion: { state: "in_progress", startedAt: Timestamp.fromDate(state.now) } })),
      async (s) => void (await db.collection("users").doc(s.uid).update({ "guardian.requestId": "otraSolicitud1234567" })),
      async (s) => void (await db.collection("guardianRequests").doc(s.r).update({ revokeTokenHash: null })),
    ];
    for (const caso of casos) {
      state.now = new Date("2026-03-01T10:00:00Z");
      const s = await accepted();
      await caso(s);
      const res = await get({ r: s.r });
      expect([res.status, res.body]).toEqual([404, INVALID]);
    }
    expect((await get({ r: "a".repeat(20) })).body).toBe(INVALID);
  });

  test("HEAD se trata como GET en modo revocación", async () => {
    const s = await accepted();
    const { guardianConsentHandler } = await import("../../src/guardian/confirm");
    const { deps } = await import("./guardian-helpers");
    expect((await guardianConsentHandler(deps(), { method: "HEAD", query: { r: s.r }, body: {} })).status).toBe(200);
  });
});
