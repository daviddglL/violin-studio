import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { recordConsentHandler } from "../../src/consent/record-consent";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const deps = (currentVersion = 1) => ({ db, auth, currentVersion });

async function usuario(perfil: Record<string, unknown> = {}) {
  const { uid } = await auth.createUser({ email: `rc-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({
    role: "independent", isMinor: false, consentStatus: "pending", policyVersion: null, birthDate: "1990-01-01", ...perfil,
  });
  return uid;
}
const consents = async (uid: string) =>
  Object.fromEntries((await db.collection("users").doc(uid).collection("consents").get()).docs.map((d) => [d.id, d.data()]));
const perfil = async (uid: string) => (await db.collection("users").doc(uid).get()).data()!;

describe.each([["pending"], ["revoked"]])("adulto en estado %s", (estado) => {
  test("éxito: consents con id determinista, timestamp de servidor, granted y claim", async () => {
    const uid = await usuario({ consentStatus: estado, policyVersion: estado === "revoked" ? 1 : null });
    const antes = Date.now();
    const res = await recordConsentHandler(deps(), uid, { policyVersion: 1 });
    expect(res).toEqual({ consentStatus: "granted" });
    const c = await consents(uid);
    expect(Object.keys(c).sort()).toEqual(["privacy_policy_v1_self", "terms_v1_self"]);
    expect(c.privacy_policy_v1_self).toMatchObject({ type: "privacy_policy", version: 1, grantedBy: "self" });
    expect(Object.keys(c.terms_v1_self).sort()).toEqual(["grantedBy", "timestamp", "type", "version"]);
    const ts = c.terms_v1_self.timestamp as Timestamp;
    expect(ts).toBeInstanceOf(Timestamp);
    expect(Math.abs(ts.toMillis() - antes)).toBeLessThan(60_000);
    expect(await perfil(uid)).toMatchObject({ consentStatus: "granted", policyVersion: 1 });
    expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(true);
  });
});

test("el timestamp lo pone el servidor aunque el cliente mande otro", async () => {
  const uid = await usuario();
  await recordConsentHandler(deps(), uid, { policyVersion: 1, timestamp: "2000-01-01T00:00:00Z" });
  const ts = (await consents(uid)).terms_v1_self.timestamp as Timestamp;
  expect(ts.toDate().getUTCFullYear()).toBeGreaterThanOrEqual(2026);
});

test("idempotencia: repetir no duplica documentos ni cambia el estado", async () => {
  const uid = await usuario();
  await recordConsentHandler(deps(), uid, { policyVersion: 1 });
  const c1 = await consents(uid);
  const p1 = await perfil(uid);
  await recordConsentHandler(deps(), uid, { policyVersion: 1 });
  expect(await consents(uid)).toEqual(c1);
  expect(await perfil(uid)).toEqual(p1);
});

test("llamadas simultáneas convergen: dos documentos y granted", async () => {
  const uid = await usuario();
  await Promise.all([recordConsentHandler(deps(), uid, { policyVersion: 1 }), recordConsentHandler(deps(), uid, { policyVersion: 1 })]);
  expect(Object.keys(await consents(uid)).sort()).toEqual(["privacy_policy_v1_self", "terms_v1_self"]);
  expect((await perfil(uid)).consentStatus).toBe("granted");
});

test("re-consentimiento: granted v1 con vigente 2 añade consents nuevos y conserva los antiguos", async () => {
  const uid = await usuario();
  await recordConsentHandler(deps(1), uid, { policyVersion: 1 });
  const antiguos = await consents(uid);
  await recordConsentHandler(deps(2), uid, { policyVersion: 2 });
  const c = await consents(uid);
  expect(Object.keys(c).sort()).toEqual(["privacy_policy_v1_self", "privacy_policy_v2_self", "terms_v1_self", "terms_v2_self"]);
  expect(c.privacy_policy_v1_self).toEqual(antiguos.privacy_policy_v1_self);
  expect(await perfil(uid)).toMatchObject({ consentStatus: "granted", policyVersion: 2 });
  expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(true);
});

test("R-e: decide el doc, no el claim (claim consentOk=true obsoleto con doc de versión vieja)", async () => {
  const uid = await usuario({ consentStatus: "granted", policyVersion: 1 });
  await auth.setCustomUserClaims(uid, { role: "independent", consentOk: true });
  await recordConsentHandler(deps(2), uid, { policyVersion: 2 });
  expect(await perfil(uid)).toMatchObject({ consentStatus: "granted", policyVersion: 2 });
  expect(Object.keys(await consents(uid))).toContain("terms_v2_self");
});

test("menor: permission-denied/GUARDIAN_REQUIRED, sin consents y consentOk sigue falso", async () => {
  const uid = await usuario({ isMinor: true, birthDate: "2015-01-01" });
  await expect(recordConsentHandler(deps(), uid, { policyVersion: 1 })).rejects.toMatchObject({
    code: "permission-denied", details: { reason: "GUARDIAN_REQUIRED" },
  });
  expect(await consents(uid)).toEqual({});
  expect(await perfil(uid)).toMatchObject({ consentStatus: "pending", policyVersion: null });
  expect((await auth.getUser(uid)).customClaims?.consentOk ?? false).toBe(false);
});

test("sin perfil: NO_PROFILE y no se crea nada", async () => {
  const { uid } = await auth.createUser({ email: `rc-np-${Date.now()}@example.com`, password: "Passw0rd!x" });
  await expect(recordConsentHandler(deps(), uid, { policyVersion: 1 })).rejects.toMatchObject({
    code: "failed-precondition", details: { reason: "NO_PROFILE" },
  });
  expect((await db.collection("users").doc(uid).get()).exists).toBe(false);
});

test("versión antigua: POLICY_OUTDATED sin consent", async () => {
  const uid = await usuario();
  await expect(recordConsentHandler(deps(2), uid, { policyVersion: 1 })).rejects.toMatchObject({
    code: "failed-precondition", details: { reason: "POLICY_OUTDATED", currentVersion: 2 },
  });
  expect(await consents(uid)).toEqual({});
});
