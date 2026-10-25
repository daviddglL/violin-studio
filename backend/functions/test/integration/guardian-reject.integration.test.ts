import { Timestamp } from "firebase-admin/firestore";
import { renderInvalidPage } from "../../src/guardian/page";
import { guardianConsentHandler } from "../../src/guardian/confirm";
import { authExists, bucket, db, deps, erased, perfil, post, req, resetState, setup, state } from "./guardian-helpers";

const INVALID = renderInvalidPage();
const reject = (s: { r: string; t: string }, over: Record<string, unknown> = {}) => post({ r: s.r, t: s.t, action: "reject", confirm: "yes", ...over });
beforeEach(() => { resetState(); erased.length = 0; });

describe("POST rechazar (válido)", () => {
  test("sin declaración: cascada completa (Auth, perfil, solicitud, mail, Storage) y página de confirmación", async () => {
    const s = await setup();
    const otro = await setup();
    const res = await reject(s);
    expect(res.status).toBe(200);
    expect(res.body).not.toBe(INVALID);
    expect(await authExists(s.uid)).toBe(false);
    expect((await db.collection("users").doc(s.uid).get()).exists).toBe(false);
    expect((await db.collection("guardianRequests").doc(s.r).get()).exists).toBe(false); // inutilizable: ya no existe
    expect((await db.collection("mail").where("uid", "==", s.uid).get()).size).toBe(0);
    expect((await bucket.getFiles({ prefix: `users/${s.uid}/` }))[0]).toHaveLength(0);
    expect(erased).toEqual([s.uid]);
    expect((await post({ r: s.r, t: s.t, action: "reject", confirm: "yes" })).body).toBe(INVALID);
    expect(await authExists(otro.uid)).toBe(true); // lo ajeno intacto
    expect(JSON.stringify(state.logs)).not.toContain(s.uid);
    expect(JSON.stringify(state.logs)).not.toContain(s.t);
  });

  test("si la cascada falla: sin 500 ni fuga, la solicitud queda usada ANTES (rejected) y el marcador deletion permite reanudar", async () => {
    const s = await setup();
    state.erase = () => Promise.reject(Object.assign(new Error(`ruta users/${s.uid}/x`), { code: "unavailable" }));
    const res = await reject(s);
    expect(res.status).toBe(200);
    expect(res.body).not.toContain(s.uid);
    const r = await req(s.r);
    expect(r.usedAt).toBeInstanceOf(Timestamp);
    expect(r.outcome).toBe("rejected");
    expect((await perfil(s.uid)).deletion).toMatchObject({ state: "in_progress" });
    expect(JSON.stringify(state.logs)).toContain('"code":"unavailable"');
    expect(JSON.stringify(state.logs)).not.toContain(s.uid);
    expect((await reject(s)).body).toBe(INVALID); // token de un solo uso
    expect(erased).toHaveLength(1);
  });
});

describe("POST rechazar (inválido): nada se borra", () => {
  const intacto = async (s: { uid: string }) => {
    expect(erased).toEqual([]);
    expect(await authExists(s.uid)).toBe(true);
    expect((await perfil(s.uid)).consentStatus).toBe("parental_pending");
  };

  test("token erróneo -> genérico y attempts++; 5 fallos bloquean aunque llegue el correcto", async () => {
    const s = await setup();
    for (let i = 1; i <= 5; i++) {
      expect((await reject(s, { t: "mal" })).body).toBe(INVALID);
      expect((await req(s.r)).attempts).toBe(i);
    }
    expect((await reject(s)).body).toBe(INVALID);
    await intacto(s);
  });

  test("usada, sustituida, caducada o con token de otra solicitud -> genérico sin borrar", async () => {
    const usada = await setup();
    await db.collection("guardianRequests").doc(usada.r).update({ usedAt: Timestamp.fromDate(state.now) });
    const sust = await setup();
    await db.collection("guardianRequests").doc(sust.r).update({ supersededAt: Timestamp.fromDate(state.now) });
    const a = await setup();
    const b = await setup();
    for (const x of [usada, sust]) expect((await reject(x)).body).toBe(INVALID);
    expect((await post({ r: b.r, t: a.t, action: "reject", confirm: "yes" })).status).toBe(404);
    expect((await req(b.r)).attempts).toBe(1);
    state.now = new Date(state.now.getTime() + 72 * 3600_000);
    expect((await reject(a)).body).toBe(INVALID);
    for (const x of [usada, sust, a, b]) await intacto(x);
  });

  test("tras aceptar, el mismo token no sirve para rechazar", async () => {
    const s = await setup();
    expect((await post({ r: s.r, t: s.t, action: "accept", declaration: "on" })).status).toBe(200);
    expect((await reject(s)).body).toBe(INVALID);
    expect(erased).toEqual([]);
    expect(await authExists(s.uid)).toBe(true);
  });
});

describe("rechazo en dos pasos (S4)", () => {
  test("sin confirm=yes: página de confirmación sin consumir nada, sin contar intentos y sin verificar el token", async () => {
    const s = await setup();
    const antes = await req(s.r);
    for (const t of [s.t, "token-erroneo"]) {
      const res = await post({ r: s.r, t, action: "reject" });
      expect(res.status).toBe(200);
      expect(res.body).toContain('name="confirm" value="yes"');
      expect(res.body).not.toBe(INVALID);
    }
    expect(await req(s.r)).toEqual(antes);
    expect(erased).toEqual([]);
    expect(await authExists(s.uid)).toBe(true);
  });

  test("enlace no válido (inexistente, usada, sustituida, caducada, usuario no pendiente) -> 404 genérico sin contar intentos", async () => {
    const usada = await setup();
    await db.collection("guardianRequests").doc(usada.r).update({ usedAt: Timestamp.fromDate(state.now) });
    const caducada = await setup();
    state.now = new Date(state.now.getTime() + 72 * 3600_000);
    for (const x of [usada, caducada, { r: "a".repeat(20), t: "x" }]) expect((await post({ r: x.r, t: x.t, action: "reject" })).body).toBe(INVALID);
    expect((await req(usada.r)).attempts).toBe(0);
    expect(erased).toEqual([]);
  });

  test("solo confirm=yes ejecuta (confirm distinto no)", async () => {
    const s = await setup();
    expect((await reject(s, { confirm: "no" })).body).toContain('name="confirm" value="yes"');
    expect(erased).toEqual([]);
    expect((await reject(s, { confirm: "yes" })).status).toBe(200);
    expect(erased).toEqual([s.uid]);
  });
});

describe("rechazo: estados del usuario y concurrencia (S6)", () => {
  const confirmado = (s: { r: string; t: string }) => reject(s, { confirm: "yes" });

  test("usuario granted, revoked o con deletion previo -> 404 y erase 0 veces", async () => {
    for (const patch of [{ consentStatus: "granted" }, { consentStatus: "revoked" }, { deletion: { state: "in_progress", startedAt: Timestamp.fromDate(state.now) } }]) {
      const s = await setup();
      await db.collection("users").doc(s.uid).update(patch);
      expect((await confirmado(s)).body).toBe(INVALID);
    }
    expect(erased).toEqual([]);
  });

  test("rechazar y aceptar a la vez: gana exactamente uno", async () => {
    const s = await setup();
    const [a, r] = await Promise.all([post({ r: s.r, t: s.t, action: "accept", declaration: "on" }), confirmado(s)]);
    expect([a.status, r.status].filter((c) => c === 200)).toHaveLength(1);
    const borrado = !(await authExists(s.uid));
    expect(borrado).toBe(r.status === 200);
    if (!borrado) expect((await perfil(s.uid)).consentStatus).toBe("granted");
    expect(erased).toHaveLength(borrado ? 1 : 0);
  });
});

describe("HEAD (S5)", () => {
  test("HEAD se trata como GET: 200 con cabeceras en enlace vigente y 404 genérico en el resto; sin efectos", async () => {
    const s = await setup();
    const antes = await req(s.r);
    expect((await guardianConsentHandler(deps(), { method: "HEAD", query: { r: s.r }, body: {} })).status).toBe(200);
    expect((await guardianConsentHandler(deps(), { method: "HEAD", query: { r: "no" }, body: {} })).status).toBe(404);
    expect(await req(s.r)).toEqual(antes);
    const put = await guardianConsentHandler(deps(), { method: "PUT", query: {}, body: {} });
    expect([put.status, put.headers.Allow]).toEqual([405, "GET, HEAD, POST"]);
  });
});
