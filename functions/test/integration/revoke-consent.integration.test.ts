import functionsTest from "firebase-functions-test";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { recordConsentHandler } from "../../src/consent/record-consent";
import { revokeConsentCore } from "../../src/consent/revoke-consent";
import { revokeConsent } from "../../src/index";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const fft = functionsTest();
afterAll(() => fft.cleanup());

const deps = { db, auth, currentVersion: 1 };
const llamar = revokeConsent && fft.wrap(revokeConsent);
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const req = (uid: string, token: Record<string, unknown>): any => ({ data: {}, auth: { uid, token } });

async function usuario(perfil: Record<string, unknown> = {}) {
  const { uid } = await auth.createUser({ email: `rv-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    role: "independent", isMinor: false, consentStatus: "pending", policyVersion: null, birthDate: "1990-01-01", ...perfil,
  });
  return uid;
}
const consents = async (uid: string) =>
  Object.fromEntries((await db.collection("users").doc(uid).collection("consents").get()).docs.map((d) => [d.id, d.data()]));
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;

describe("revokeConsentCore", () => {
  test("revoca: estado, consent revocation, época 1, claim consentOk=false y consents previos intactos", async () => {
    const uid = await usuario();
    await recordConsentHandler(deps, uid, { policyVersion: 1 });
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(true);
    const antes = await consents(uid);

    expect(await revokeConsentCore(deps, uid, "self")).toEqual({ consentStatus: "revoked" });

    const c = await consents(uid);
    expect(Object.keys(c).sort()).toEqual(["privacy_policy_v1_self_e0", "revocation_v1_self_e0", "terms_v1_self_e0"]);
    expect(c.privacy_policy_v1_self_e0).toEqual(antes.privacy_policy_v1_self_e0);
    expect(c.terms_v1_self_e0).toEqual(antes.terms_v1_self_e0);
    expect(c.revocation_v1_self_e0).toMatchObject({ type: "revocation", version: 1, grantedBy: "self" });
    expect(c.revocation_v1_self_e0.timestamp).toBeInstanceOf(Timestamp);
    expect(await perfil(uid)).toMatchObject({ consentStatus: "revoked", consentEpoch: 1 });
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
  });

  test("by=guardian queda registrado en el consent revocation", async () => {
    const uid = await usuario({ isMinor: true, consentStatus: "granted", policyVersion: 1 });
    await revokeConsentCore(deps, uid, "guardian");
    expect((await consents(uid)).revocation_v1_guardian_e0).toMatchObject({ grantedBy: "guardian" });
  });

  test.each([["pending"], ["revoked"], ["parental_pending"]])("estado %s -> failed-precondition sin escrituras", async (estado) => {
    const uid = await usuario({ consentStatus: estado, policyVersion: 1 });
    const antes = await perfil(uid);
    await expect(revokeConsentCore(deps, uid, "self")).rejects.toMatchObject({ code: "failed-precondition" });
    expect(await consents(uid)).toEqual({});
    expect(await perfil(uid)).toEqual(antes);
  });

  test("sin perfil y con borrado en curso -> NO_PROFILE", async () => {
    const { uid } = await auth.createUser({ email: `rv-np-${Date.now()}@example.com`, password: "Passw0rd!x" });
    await expect(revokeConsentCore(deps, uid, "self")).rejects.toMatchObject({ details: { reason: "NO_PROFILE" } });
    const u2 = await usuario({ consentStatus: "granted", policyVersion: 1, deletion: { state: "in_progress" } });
    await expect(revokeConsentCore(deps, u2, "self")).rejects.toMatchObject({ details: { reason: "NO_PROFILE" } });
    expect(await consents(u2)).toEqual({});
  });

  test("dos revocaciones simultáneas: una gana, la otra falla, un solo consent revocation", async () => {
    const uid = await usuario({ consentStatus: "granted", policyVersion: 1 });
    const r = await Promise.allSettled([revokeConsentCore(deps, uid, "self"), revokeConsentCore(deps, uid, "self")]);
    expect(r.filter((x) => x.status === "fulfilled")).toHaveLength(1);
    expect(Object.keys(await consents(uid))).toEqual(["revocation_v1_self_e0"]);
    expect((await perfil(uid)).consentEpoch).toBe(1);
  });

  test("decide el documento, no el claim: token viejo con consentOk=true sobre un doc pending no revoca", async () => {
    const uid = await usuario();
    await auth.setCustomUserClaims(uid, { role: "independent", consentOk: true });
    await expect(llamar(req(uid, { email_verified: true, consentOk: true }))).rejects.toMatchObject({ code: "failed-precondition" });
    expect(await consents(uid)).toEqual({});
  });
});

describe("callable revokeConsent", () => {
  test("sin sesión -> unauthenticated", async () => {
    await expect(llamar({ data: {} } as never)).rejects.toMatchObject({ code: "unauthenticated" });
  });
  test("email no verificado -> rechazo y cero escrituras (A2)", async () => {
    const uid = await usuario({ consentStatus: "granted", policyVersion: 1 });
    await expect(llamar(req(uid, { email_verified: false }))).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "EMAIL_NOT_VERIFIED" },
    });
    expect(await consents(uid)).toEqual({});
    expect((await perfil(uid)).consentStatus).toBe("granted");
  });
  test("verificado: revoca y el claim pasa a consentOk=false aunque el token viejo diga true", async () => {
    const uid = await usuario({ consentStatus: "granted", policyVersion: 1 });
    expect(await llamar(req(uid, { email_verified: true, consentOk: true }))).toEqual({ consentStatus: "revoked" });
    expect((await perfil(uid)).consentStatus).toBe("revoked");
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
  });
});

describe("claims obsoletos tras revocar (W1)", () => {
  test("el sync falla tras el commit: el reintento rechaza con NO_ACTIVE_CONSENT, no escribe y cura consentOk=false", async () => {
    const uid = await usuario({ consentStatus: "granted", policyVersion: 1 });
    await auth.setCustomUserClaims(uid, { role: "independent", consentOk: true });
    let falla = true;
    const authFlaky = {
      getUser: (u: string) => auth.getUser(u),
      setCustomUserClaims: async (u: string, c: object) => {
        if (falla) { falla = false; throw new Error("auth caído"); }
        return auth.setCustomUserClaims(u, c);
      },
    } as unknown as typeof auth;
    await expect(revokeConsentCore({ db, auth: authFlaky, currentVersion: 1 }, uid, "self")).rejects.toThrow("auth caído");
    expect((await perfil(uid)).consentStatus).toBe("revoked");
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(true);
    const antes = await consents(uid);

    await expect(llamar(req(uid, { email_verified: true, consentOk: true }))).rejects.toMatchObject({
      code: "failed-precondition", details: { reason: "NO_ACTIVE_CONSENT" },
    });
    expect(await consents(uid)).toEqual(antes);
    expect((await perfil(uid)).consentEpoch).toBe(1);
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
  });
  test("token viejo consentOk=true + doc revoked: revokeConsent rechaza y cura el claim", async () => {
    const uid = await usuario({ consentStatus: "revoked", policyVersion: 1, consentEpoch: 1 });
    await auth.setCustomUserClaims(uid, { role: "independent", consentOk: true });
    await expect(llamar(req(uid, { email_verified: true, consentOk: true }))).rejects.toMatchObject({
      details: { reason: "NO_ACTIVE_CONSENT" },
    });
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
    expect(await consents(uid)).toEqual({});
  });
});

describe("menor con consentimiento del tutor (W2)", () => {
  test("puede auto-revocar: la revocación lleva grantedBy=self (revocó el propio menor)", async () => {
    const uid = await usuario({ isMinor: true, consentStatus: "granted", policyVersion: 1 });
    expect(await llamar(req(uid, { email_verified: true }))).toEqual({ consentStatus: "revoked" });
    expect((await consents(uid)).revocation_v1_self_e0).toMatchObject({ type: "revocation", grantedBy: "self" });
    expect(await perfil(uid)).toMatchObject({ consentStatus: "revoked", consentEpoch: 1 });
  });
});

describe("reconsentir tras revocar (2b.4)", () => {
  test("adulto revoked -> recordConsent(actual) vuelve a granted con consents nuevos (época 1) y traza intacta", async () => {
    const uid = await usuario();
    await recordConsentHandler(deps, uid, { policyVersion: 1 });
    await revokeConsentCore(deps, uid, "self");
    expect(await recordConsentHandler(deps, uid, { policyVersion: 1 })).toEqual({ consentStatus: "granted" });
    expect(Object.keys(await consents(uid)).sort()).toEqual([
      "privacy_policy_v1_self_e0", "privacy_policy_v1_self_e1", "revocation_v1_self_e0", "terms_v1_self_e0", "terms_v1_self_e1",
    ]);
    expect(await perfil(uid)).toMatchObject({ consentStatus: "granted", consentEpoch: 1 });
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(true);
  });
  test("ciclo repetido: la segunda revocación cierra la época 1 sin colisionar", async () => {
    const uid = await usuario();
    for (let i = 0; i < 2; i++) {
      await recordConsentHandler(deps, uid, { policyVersion: 1 });
      await revokeConsentCore(deps, uid, "self");
    }
    expect(Object.keys(await consents(uid))).toEqual(
      expect.arrayContaining(["revocation_v1_self_e0", "revocation_v1_self_e1"]),
    );
    expect((await perfil(uid)).consentEpoch).toBe(2);
  });
  test("menor revoked -> recordConsent sigue GUARDIAN_REQUIRED", async () => {
    const uid = await usuario({ isMinor: true, consentStatus: "revoked", policyVersion: 1 });
    await expect(recordConsentHandler(deps, uid, { policyVersion: 1 })).rejects.toMatchObject({
      code: "permission-denied", details: { reason: "GUARDIAN_REQUIRED" },
    });
    expect(await consents(uid)).toEqual({});
  });
});

// 2b.5: el borrado tras revocar sigue disponible (D1). Se completa en 7a.14 y 7a.15.
describe("borrado tras revocar (D1)", () => {
  it.todo("deleteAccount procede con consentStatus=revoked (ver 7a.14, 7a.15)");
});
