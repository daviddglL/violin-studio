import { Timestamp } from "firebase-admin/firestore";
import { renderInvalidPage, renderRevokedPage } from "../../src/guardian/page";
import { eraseUserData } from "../../src/erasure/erase-user-data";
import { deleteAccountHandler } from "../../src/erasure/delete-account";
import { requestGuardianConsentHandler } from "../../src/guardian/request";
import { hashToken } from "../../src/guardian/token";
import { auth, authExists, bucket, db, erased, get, PEPPER, perfil, post, req, resetState, setup, state } from "./guardian-helpers";

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

const revoke = (s: { r: string; rev: string }, over: Record<string, unknown> = {}) => post({ r: s.r, t: s.rev, action: "revoke", ...over });
const consents = async (uid: string) => (await db.collection("users").doc(uid).collection("consents").get()).docs.map((d) => d.id).sort();

describe("3c.5 POST revoke", () => {
  test("token válido: revoked, epoch+1, consent revocation grantedBy guardian, consentOk=false, solicitud marcada y página de revocada", async () => {
    const s = await accepted();
    expect((await auth.getUser(s.uid)).customClaims?.consentOk).toBe(true);
    const antes = await consents(s.uid);
    const res = await revoke(s);
    expect([res.status, res.body]).toEqual([200, renderRevokedPage()]);
    const p = await perfil(s.uid);
    expect(p.consentStatus).toBe("revoked");
    expect(p.consentEpoch).toBe(1);
    const despues = await consents(s.uid);
    expect(despues).toEqual([...antes, "revocation_v1_guardian_e0"].sort()); // append-only: nada previo se toca
    const rev = (await db.collection("users").doc(s.uid).collection("consents").doc("revocation_v1_guardian_e0").get()).data()!;
    expect(rev).toMatchObject({ type: "revocation", grantedBy: "guardian", version: 1 });
    expect((await auth.getUser(s.uid)).customClaims?.consentOk).toBe(false);
    expect((await req(s.r)).revokedAt).toBeInstanceOf(Timestamp);
    expect(erased).toEqual([]);
    expect(await authExists(s.uid)).toBe(true);
    expect(state.logs.map(([m]) => m)).toContain("guardianConsent.revoked");
    expect(JSON.stringify(state.logs)).not.toContain(s.uid);
    expect(JSON.stringify(state.logs)).not.toContain(s.rev);
  });

  test("reuso -> genérico, sin segunda revocación", async () => {
    const s = await accepted();
    expect((await revoke(s)).status).toBe(200);
    expect((await revoke(s)).body).toBe(INVALID);
    expect((await perfil(s.uid)).consentEpoch).toBe(1);
  });

  test("token erróneo -> genérico y revokeAttempts++ (contador propio); 5 fallos bloquean aunque llegue el correcto", async () => {
    const s = await accepted();
    for (let i = 1; i <= 5; i++) {
      expect((await revoke({ r: s.r, rev: "mal" })).body).toBe(INVALID);
      const r = await req(s.r);
      expect([r.revokeAttempts, r.attempts]).toEqual([i, 0]);
    }
    expect((await revoke(s)).body).toBe(INVALID);
    expect((await perfil(s.uid)).consentStatus).toBe("granted");
  });

  test("los tokens no son intercambiables: el de aceptación no revoca (cuenta intento) y el de revocación no acepta ni rechaza", async () => {
    const s = await accepted();
    expect((await post({ r: s.r, t: s.t, action: "revoke" })).body).toBe(INVALID);
    expect((await req(s.r)).revokeAttempts).toBe(1);
    expect((await post({ r: s.r, t: s.rev, action: "accept", declaration: "on" })).body).toBe(INVALID);
    expect((await post({ r: s.r, t: s.rev, action: "reject", confirm: "yes" })).body).toBe(INVALID);
    expect(erased).toEqual([]);
    expect((await perfil(s.uid)).consentStatus).toBe("granted");
    expect((await req(s.r)).attempts).toBe(0);
  });

  test("solicitud pendiente (sin aceptar) o de otro usuario: el token no revoca nada", async () => {
    const pendiente = await setup();
    const a = await accepted();
    const b = await accepted();
    expect((await post({ r: pendiente.r, t: pendiente.t, action: "revoke" })).body).toBe(INVALID);
    expect((await post({ r: b.r, t: a.rev, action: "revoke" })).body).toBe(INVALID);
    expect((await req(b.r)).revokeAttempts).toBe(1);
    for (const x of [pendiente, a, b]) expect((await perfil(x.uid)).consentStatus).not.toBe("revoked");
  });

  test("caducado, ya revocado por el menor, estado no granted, deletion o solicitud nueva del menor -> genérico sin escribir ni contar", async () => {
    const casos: Array<(s: Awaited<ReturnType<typeof accepted>>) => Promise<void>> = [
      async () => { state.now = new Date(state.now.getTime() + 30 * DAY); },
      async (s) => void (await db.collection("users").doc(s.uid).update({ consentStatus: "revoked" })),
      async (s) => void (await db.collection("users").doc(s.uid).update({ consentStatus: "parental_pending" })),
      async (s) => void (await db.collection("users").doc(s.uid).update({ deletion: { state: "in_progress", startedAt: Timestamp.fromDate(state.now) } })),
      async (s) => void (await db.collection("users").doc(s.uid).update({ "guardian.requestId": "otraSolicitud1234567" })),
    ];
    for (const caso of casos) {
      state.now = new Date("2026-03-01T10:00:00Z");
      const s = await accepted();
      await caso(s);
      const antes = [await req(s.r), await perfil(s.uid)];
      expect((await revoke(s)).body).toBe(INVALID);
      expect([await req(s.r), await perfil(s.uid)]).toEqual(antes);
      expect(await consents(s.uid)).not.toContain("revocation_v1_guardian_e0");
    }
  });

  test("dos revocaciones simultáneas: exactamente una gana y solo hay una revocación", async () => {
    const s = await accepted();
    const res = await Promise.all([revoke(s), revoke(s)]);
    expect(res.map((r) => r.status).sort()).toEqual([200, 404]);
    expect((await perfil(s.uid)).consentEpoch).toBe(1);
    expect((await consents(s.uid)).filter((c) => c.startsWith("revocation"))).toHaveLength(1);
  });

  test("formato de r inválido o sin token -> genérico", async () => {
    expect((await post({ r: "../x", t: "t", action: "revoke" })).body).toBe(INVALID);
    expect((await post({ r: "a".repeat(20), action: "revoke" })).body).toBe(INVALID);
  });
});

describe("3c.6 tras revocar el tutor", () => {
  const ask = async (uid: string) => {
    const email = (await auth.getUser(uid)).email;
    return requestGuardianConsentHandler(
      { db, pepper: PEPPER, linkBaseUrl: "https://x.app", guardianFlowEnabled: true, clock: () => state.now, log: () => undefined },
      uid, email, { guardianEmail: `n${Date.now()}${Math.random()}@example.com` },
    );
  };

  test("el menor puede volver a pedir tutor desde revoked; el enlace viejo sigue muerto y el ciclo completo se repite", async () => {
    const s = await accepted();
    expect((await revoke(s)).status).toBe(200);
    expect((await ask(s.uid)).status).toBe("sent");
    const p = await perfil(s.uid);
    expect(p.consentStatus).toBe("parental_pending");
    expect(p.guardian.requestId).not.toBe(s.r);
    expect((await get({ r: s.r })).body).toBe(INVALID);
    expect((await revoke(s)).body).toBe(INVALID);

    // El nuevo tutor acepta la nueva solicitud y obtiene su propio enlace de revocación.
    const [, r2, t2] = (await mails(s.uid, "guardian_consent")).map((m) => m.data()).find((m) => m.message.text.includes(p.guardian.requestId))!.message.text.match(/\/tutor\?r=(\S+)#t=([\w-]+)/)!;
    expect((await post({ r: r2, t: t2, action: "accept", declaration: "on" })).status).toBe(200);
    expect((await get({ r: s.r })).body).toBe(INVALID); // granted de nuevo, pero el enlace viejo ya no es el de la solicitud activa
    expect((await revoke(s)).body).toBe(INVALID);
    const m2 = (await mails(s.uid, "guardian_revoke")).map((m) => m.data()).find((m) => m.message.text.includes(`r=${r2}#`))!;
    const rev2 = m2.message.text.match(/#t=([\w-]+)&a=revoke/)![1];
    expect((await post({ r: r2, t: rev2, action: "revoke" })).status).toBe(200);
    const p2 = await perfil(s.uid);
    expect([p2.consentStatus, p2.consentEpoch]).toEqual(["revoked", 2]);
    expect(await consents(s.uid)).toEqual(expect.arrayContaining(["revocation_v1_guardian_e0", "revocation_v1_guardian_e1"]));
  });

  test("la solicitud aceptada no se sustituye (supersede) al volver a pedir tutor", async () => {
    const s = await accepted();
    await revoke(s);
    await ask(s.uid);
    const r = await req(s.r);
    expect(r.supersededAt).toBeNull();
    expect(r.outcome).toBe("accepted");
  });

  test("el menor revocado por su tutor puede borrar su cuenta (D1)", async () => {
    const s = await accepted();
    await revoke(s);
    const res = await deleteAccountHandler(
      { erase: (uid) => eraseUserData({ db, auth, bucket }, uid, { deleteAuth: true }), clock: () => state.now, log: () => undefined },
      { auth: { uid: s.uid, token: { auth_time: Math.floor(state.now.getTime() / 1000) - 10 } } } as never,
    );
    expect(res).toEqual({ deleted: true });
    expect(await authExists(s.uid)).toBe(false);
  });
});

describe("3c.7 borrado interno", () => {
  test("la cascada por uid borra solicitud (con revokeTokenHash) y los dos mails; lo ajeno, intacto", async () => {
    const s = await accepted();
    const otro = await accepted();
    expect((await req(s.r)).revokeTokenHash).toBeDefined();
    const res = await eraseUserData({ db, auth, bucket }, s.uid, { deleteAuth: true });
    expect(res.deleted).toMatchObject({ guardianRequests: 1, mail: 2 });
    expect((await db.collection("guardianRequests").where("uid", "==", s.uid).get()).size).toBe(0);
    expect((await db.collection("mail").where("uid", "==", s.uid).get()).size).toBe(0);
    expect((await db.collection("guardianRequests").doc(otro.r).get()).exists).toBe(true);
    expect((await mails(otro.uid, "guardian_revoke"))).toHaveLength(1);
  });
});
