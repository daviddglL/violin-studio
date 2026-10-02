import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { hmacEmail } from "../../src/common/hashing";
import * as token from "../../src/guardian/token";
import { guardianConsentHandler } from "../../src/guardian/confirm";
import { renderInvalidPage } from "../../src/guardian/page";
import { requestGuardianConsentHandler } from "../../src/guardian/request";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const PEPPER = "t".repeat(32);
let seq = 0;
let now = new Date("2026-03-01T10:00:00Z");
let currentVersion = 1;
const logs: Array<[string, Record<string, unknown>]> = [];
const deps = () => ({ db, auth, clock: () => now, currentVersion, log: (m: string, d: Record<string, unknown>) => void logs.push([m, d]) });

/** Menor + solicitud real (vía requestGuardianConsentHandler); devuelve uid, id de solicitud y token en claro del mail. */
async function setup(perfil: Record<string, unknown> = {}, despues: Record<string, unknown> = {}) {
  const email = `m-${Date.now()}-${Math.random()}@example.com`;
  const { uid } = await auth.createUser({ email, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    displayName: "Ana <b>", locale: "es", isMinor: true, consentStatus: "pending", policyVersion: null, ...perfil,
  });
  const guardian = `t${Date.now()}${seq++}@example.com`;
  await requestGuardianConsentHandler(
    { db, pepper: PEPPER, linkBaseUrl: "https://x.app", guardianFlowEnabled: true, clock: () => now, log: () => undefined },
    uid, email, { guardianEmail: guardian },
  );
  const [mail] = (await db.collection("mail").where("uid", "==", uid).get()).docs;
  const [, r, t] = mail.data().message.text.match(/\/tutor\?r=(\S+)#t=([\w-]+)/)!;
  if (Object.keys(despues).length) await db.collection("users").doc(uid).update(despues);
  return { uid, r: r as string, t: t as string, guardian };
}
const get = (query: Record<string, unknown>) => guardianConsentHandler(deps(), { method: "GET", query, body: {} });
const post = (body: Record<string, unknown>, method = "POST") => guardianConsentHandler(deps(), { method, query: {}, body });
const accept = (s: { r: string; t: string }, over: Record<string, unknown> = {}) =>
  post({ r: s.r, t: s.t, action: "accept", declaration: "on", ...over });
const req = async (r: string) => (await db.collection("guardianRequests").doc(r).get()).data()!;
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;
const consents = async (uid: string) => (await db.collection("users").doc(uid).collection("consents").get()).docs;
const INVALID = renderInvalidPage();

beforeEach(() => { now = new Date("2026-03-01T10:00:00Z"); currentVersion = 1; logs.length = 0; });

describe("GET", () => {
  test("solicitud vigente -> 200 con nombre escapado, política, casilla; sin token; y no cambia nada", async () => {
    const s = await setup();
    const antes = await req(s.r);
    const res = await get({ r: s.r, t: "hostil" });
    expect(res.status).toBe(200);
    expect(res.body).toContain("Ana &lt;b&gt;");
    expect(res.body).toContain('name="declaration"');
    expect(res.body).not.toContain(s.t);
    expect(res.body).not.toContain("hostil");
    for (let i = 0; i < 3; i++) await get({ r: s.r });
    expect(await req(s.r)).toEqual(antes);
    expect((await perfil(s.uid)).consentStatus).toBe("parental_pending");
    expect((await accept(s)).status).toBe(200);
  });

  test("inexistente, sin r, caducada, usada, sustituida, bloqueada y usuario borrado -> mismo código y cuerpo genérico", async () => {
    const caducada = await setup();
    const usada = await setup();
    await db.collection("guardianRequests").doc(usada.r).update({ usedAt: Timestamp.fromDate(now) });
    const sustituida = await setup();
    await db.collection("guardianRequests").doc(sustituida.r).update({ supersededAt: Timestamp.fromDate(now) });
    const bloqueada = await setup();
    await db.collection("guardianRequests").doc(bloqueada.r).update({ attempts: 5 });
    const borrado = await setup({}, { deletion: { state: "in_progress", startedAt: Timestamp.fromDate(now) } });
    const sinPerfil = await setup();
    await db.collection("users").doc(sinPerfil.uid).delete();
    const consultas = [{ r: "noexiste" }, {}, { r: ["a"] }, { r: usada.r }, { r: sustituida.r }, { r: bloqueada.r }, { r: borrado.r }, { r: sinPerfil.r }];
    for (const q of consultas) {
      const res = await get(q);
      expect([res.status, res.body]).toEqual([404, INVALID]);
    }
    now = new Date(now.getTime() + 72 * 3600_000); // now == expiresAt
    const res = await get({ r: caducada.r });
    expect([res.status, res.body]).toEqual([404, INVALID]);
  });
});

describe("métodos y cabeceras", () => {
  test("PUT/DELETE -> 405 sin efectos; POST sin token o sin r -> rechazo genérico", async () => {
    const s = await setup();
    const antes = await req(s.r);
    for (const m of ["PUT", "DELETE", "PATCH"]) {
      const res = await post({ r: s.r, t: s.t, action: "accept", declaration: "on" }, m);
      expect(res.status).toBe(405);
      expect(res.headers.Allow).toBe("GET, POST");
    }
    expect((await post({ r: s.r, action: "accept", declaration: "on" })).body).toBe(INVALID);
    expect((await post({ t: s.t, action: "accept", declaration: "on" })).body).toBe(INVALID);
    expect(await req(s.r)).toEqual(antes);
    expect((await perfil(s.uid)).consentStatus).toBe("parental_pending");
  });

  test("todas las respuestas llevan no-store, no-referrer, DENY y CSP con nonce, sin cookies", async () => {
    const s = await setup();
    for (const res of [await get({ r: s.r }), await get({}), await post({}, "PUT"), await accept(s, { t: "mal" }), await accept(s)]) {
      expect(res.headers["Cache-Control"]).toBe("no-store");
      expect(res.headers["Referrer-Policy"]).toBe("no-referrer");
      expect(res.headers["X-Frame-Options"]).toBe("DENY");
      expect(res.headers["Content-Security-Policy"]).toMatch(/^default-src 'none'; script-src 'nonce-[\w-]+';/);
      expect(Object.keys(res.headers).map((k) => k.toLowerCase())).not.toContain("set-cookie");
    }
  });
});

describe("POST aceptar", () => {
  test("camino feliz: usedAt, accepted, consent del tutor con epoch, granted y claim", async () => {
    const s = await setup({ consentEpoch: 2 });
    const res = await accept(s);
    expect(res.status).toBe(200);
    const r = await req(s.r);
    expect(r.usedAt).toBeInstanceOf(Timestamp);
    expect(r.outcome).toBe("accepted");
    expect(r.attempts).toBe(0);
    const docs = await consents(s.uid);
    expect(docs.map((d) => d.id)).toEqual(["guardian_privacy_policy_v1_guardian_e2"]);
    expect(docs[0].data()).toMatchObject({
      type: "guardian_privacy_policy", version: 1, grantedBy: "guardian", declaration: "legal_guardian",
      guardianEmailHmac: hmacEmail(PEPPER, s.guardian),
    });
    expect(docs[0].data().timestamp).toBeInstanceOf(Timestamp);
    expect(await perfil(s.uid)).toMatchObject({ consentStatus: "granted", policyVersion: 1 });
    expect((await auth.getUser(s.uid)).customClaims?.consentOk).toBe(true);
    expect(JSON.stringify(logs)).not.toContain(s.uid);
    expect(JSON.stringify(logs)).not.toContain(s.t);
  });

  test("sin declaración -> rechazo que no consume el token ni cuenta intentos; luego sí acepta", async () => {
    const s = await setup();
    const res = await accept(s, { declaration: undefined });
    expect(res.status).toBe(400);
    expect(res.body).not.toBe(INVALID);
    expect(await req(s.r)).toMatchObject({ usedAt: null, attempts: 0 });
    expect((await accept(s)).status).toBe(200);
  });

  test("reutilización y caducidad: now < expiresAt procede, now >= expiresAt rechaza y sigue parental_pending", async () => {
    const s = await setup();
    expect((await accept(s)).status).toBe(200);
    const usada = await req(s.r);
    const otra = await accept(s);
    expect([otra.status, otra.body]).toEqual([404, INVALID]);
    expect(await req(s.r)).toEqual(usada);
    expect(await consents(s.uid)).toHaveLength(1);

    const limite = await setup();
    now = new Date(now.getTime() + 72 * 3600_000 - 1);
    expect((await accept(limite)).status).toBe(200);
    now = new Date("2026-03-01T10:00:00Z");
    const exacta = await setup();
    now = new Date(now.getTime() + 72 * 3600_000);
    expect((await accept(exacta)).status).toBe(404);
    expect((await perfil(exacta.uid)).consentStatus).toBe("parental_pending");
    expect(await req(exacta.r)).toMatchObject({ usedAt: null, attempts: 0 });
  });

  test("token erróneo -> genérico y attempts++; tras 5 fallos queda bloqueada aunque llegue el token correcto", async () => {
    const s = await setup();
    for (let i = 1; i <= 5; i++) {
      const res = await accept(s, { t: `mal${i}` });
      expect([res.status, res.body]).toEqual([404, INVALID]);
      expect((await req(s.r)).attempts).toBe(i);
    }
    expect((await accept(s)).status).toBe(404);
    expect(await req(s.r)).toMatchObject({ usedAt: null, attempts: 5 });
    expect((await perfil(s.uid)).consentStatus).toBe("parental_pending");
  });

  test("token de la solicitud A con el r de B -> rechazo; A intacta, B solo cuenta el intento", async () => {
    const a = await setup();
    const b = await setup();
    const antesA = await req(a.r);
    expect((await post({ r: b.r, t: a.t, action: "accept", declaration: "on" })).status).toBe(404);
    expect(await req(a.r)).toEqual(antesA);
    expect(await req(b.r)).toMatchObject({ usedAt: null, outcome: null, attempts: 1 });
    expect((await perfil(b.uid)).consentStatus).toBe("parental_pending");
  });

  test("tres POST válidos simultáneos -> exactamente uno acepta y un solo consent", async () => {
    const s = await setup();
    const res = await Promise.all([accept(s), accept(s), accept(s)]);
    expect(res.map((x) => x.status).sort()).toEqual([200, 404, 404]);
    expect(await consents(s.uid)).toHaveLength(1);
  });

  test("cuenta ya borrada / en borrado, o fuera de parental_pending -> genérico y sin datos nuevos", async () => {
    const borrada = await setup();
    await db.collection("users").doc(borrada.uid).delete();
    expect((await accept(borrada)).body).toBe(INVALID);
    expect((await db.collection("users").doc(borrada.uid).get()).exists).toBe(false);
    expect(await consents(borrada.uid)).toHaveLength(0);

    const enBorrado = await setup({}, { deletion: { state: "in_progress", startedAt: Timestamp.fromDate(now) } });
    expect((await accept(enBorrado)).status).toBe(404);
    expect(await consents(enBorrado.uid)).toHaveLength(0);

    const revocada = await setup();
    await db.collection("users").doc(revocada.uid).update({ consentStatus: "revoked" });
    expect((await accept(revocada)).status).toBe(404);
    expect((await perfil(revocada.uid)).consentStatus).toBe("revoked");
    expect(await consents(revocada.uid)).toHaveLength(0);
  });

  test("la versión vigente al aceptar (no la del envío) va al consent y al perfil; guardian.sends intacto", async () => {
    const s = await setup();
    const sends = (await perfil(s.uid)).guardian.sends;
    currentVersion = 2;
    expect((await accept(s)).status).toBe(200);
    expect((await consents(s.uid))[0].id).toBe("guardian_privacy_policy_v2_guardian_e0");
    expect(await perfil(s.uid)).toMatchObject({ consentStatus: "granted", policyVersion: 2 });
    expect((await perfil(s.uid)).guardian.sends).toEqual(sends);
  });

  test("action distinto de accept (reject llega en 3c) -> genérico sin efectos", async () => {
    const s = await setup();
    expect((await accept(s, { action: "reject" })).status).toBe(404);
    expect(await req(s.r)).toMatchObject({ usedAt: null, attempts: 0 });
  });
});

describe("revisión 3b: entradas hostiles y consistencia", () => {
  const hostiles = [".", "..", "__x__", "a".repeat(2000), "a/b", "x".repeat(19), "x".repeat(21), "ñ".repeat(20), ""];

  test.each(hostiles)("r hostil %# -> genérico 404 en GET y POST, sin excepción", async (r) => {
    const g = await get({ r });
    expect([g.status, g.body]).toEqual([404, INVALID]);
    const p = await post({ r, t: "tok", action: "accept", declaration: "on" });
    expect([p.status, p.body]).toEqual([404, INVALID]);
  });

  test("un error inesperado del backend -> genérico 404 y log solo con el código", async () => {
    const boom = { collection: () => { throw Object.assign(new Error("ruta users/secreto"), { code: "boom" }); } } as never;
    const d = { ...deps(), db: boom };
    const r = "a".repeat(20);
    expect((await guardianConsentHandler(d, { method: "GET", query: { r }, body: {} })).body).toBe(INVALID);
    expect((await guardianConsentHandler(d, { method: "POST", query: {}, body: { r, t: "t", action: "accept", declaration: "on" } })).status).toBe(404);
    expect(logs.map(([m, x]) => [m, x])).toEqual([["guardianConsent.error", { code: "boom" }], ["guardianConsent.error", { code: "boom" }]]);
  });

  test.each([["false"], ["off"], ["1"], [true], [""]])("declaration %p no vale -> 400 sin consumir el token", async (declaration) => {
    const s = await setup();
    const res = await accept(s, { declaration });
    expect(res.status).toBe(400);
    expect(await req(s.r)).toMatchObject({ usedAt: null, attempts: 0 });
    expect(await consents(s.uid)).toHaveLength(0);
  });

  test('declaration "true" también vale', async () => {
    const s = await setup();
    expect((await accept(s, { declaration: "true" })).status).toBe(200);
  });

  test("solicitud inexistente o cerrada: igualmente se compara el token contra un hash ficticio (coste uniforme)", async () => {
    const spy = jest.spyOn(token, "verifyToken");
    try {
      await accept({ r: "b".repeat(20), t: "tok" });
      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0][0]).toBe("tok");
      expect(spy.mock.calls[0][1]).toMatch(/^[0-9a-f]{64}$/);
      spy.mockClear();
      const usada = await setup();
      await db.collection("guardianRequests").doc(usada.r).update({ usedAt: Timestamp.fromDate(now) });
      await accept(usada);
      expect(spy).toHaveBeenCalledTimes(1);
    } finally {
      spy.mockRestore();
    }
  });

  test("guardian.requestId del usuario apunta a otra solicitud -> genérico y sin escrituras", async () => {
    const s = await setup();
    await db.collection("users").doc(s.uid).update({ "guardian.requestId": "z".repeat(20) });
    expect((await accept(s)).body).toBe(INVALID);
    expect((await get({ r: s.r })).body).toBe(INVALID);
    expect(await req(s.r)).toMatchObject({ usedAt: null, outcome: null, attempts: 0 });
    expect(await consents(s.uid)).toHaveLength(0);
    expect((await perfil(s.uid)).consentStatus).toBe("parental_pending");
  });

  test("solicitud cuyo uid es el de otro usuario (con su propia solicitud) -> genérico y sin escrituras", async () => {
    const a = await setup();
    const b = await setup();
    await db.collection("guardianRequests").doc(a.r).update({ uid: b.uid });
    expect((await accept(a)).body).toBe(INVALID);
    expect(await consents(b.uid)).toHaveLength(0);
    expect(await consents(a.uid)).toHaveLength(0);
    expect((await perfil(b.uid)).consentStatus).toBe("parental_pending");
    expect(await req(b.r)).toMatchObject({ usedAt: null, attempts: 0 });
  });
});
