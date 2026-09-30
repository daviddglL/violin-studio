import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { registerProfileHandler, RegisterProfileDeps } from "../../src/profile/register-profile";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);

const HOY = new Date("2026-09-30T12:00:00Z");
const deps = (parche: Partial<RegisterProfileDeps> = {}): RegisterProfileDeps => ({
  db, auth, clock: () => HOY, guardianFlowEnabled: true, ...parche,
});

const payload = (parche: Record<string, unknown> = {}) => ({
  birthDate: "1996-05-10", displayName: "Ana", instrument: "violin", locale: "es-ES", ...parche,
});

async function nuevoUsuario() {
  return auth.createUser({ email: `rp-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
}
const leer = async (uid: string) => (await db.collection("users").doc(uid).get());

test("alta de adulto: rol independent, isMinor=false, consentStatus=pending, policyVersion=null (P3)", async () => {
  const { uid } = await nuevoUsuario();
  const res = await registerProfileHandler(deps(), uid, payload());
  const snap = await leer(uid);
  expect(snap.exists).toBe(true);
  const d = snap.data()!;
  expect(d).toMatchObject({
    role: "independent", isMinor: false, consentStatus: "pending", policyVersion: null,
    birthDate: "1996-05-10", displayName: "Ana", instrument: "violin", locale: "es-ES",
  });
  expect(d.createdAt).toBeInstanceOf(Timestamp);
  expect(d.updatedAt).toBeInstanceOf(Timestamp);
  expect(res).toEqual({ isMinor: false, consentStatus: "pending", requiredPolicyVersion: 1 });
});

test("alta de menor con flujo activo: isMinor=true, pending y sin consentOk", async () => {
  const { uid } = await nuevoUsuario();
  const res = await registerProfileHandler(deps(), uid, payload({ birthDate: "2013-06-01" }));
  expect(res).toMatchObject({ isMinor: true, consentStatus: "pending" });
  expect((await leer(uid)).data()).toMatchObject({ isMinor: true, consentStatus: "pending" });
  expect((await auth.getUser(uid)).customClaims?.consentOk).toBe(false);
});

test("alta de menor con flujo desactivado: UNDERAGE_NOT_ALLOWED y no se crea doc", async () => {
  const { uid } = await nuevoUsuario();
  const e = await registerProfileHandler(deps({ guardianFlowEnabled: false }), uid, payload({ birthDate: "2013-06-01" }))
    .catch((x: HttpsError) => x);
  expect(e).toBeInstanceOf(HttpsError);
  expect((e as HttpsError).code).toBe("failed-precondition");
  expect((e as HttpsError).details).toMatchObject({ reason: "UNDERAGE_NOT_ALLOWED" });
  expect((await leer(uid)).exists).toBe(false);
});

test("idempotencia: la segunda llamada devuelve el perfil existente sin modificar birthDate/role/isMinor", async () => {
  const { uid } = await nuevoUsuario();
  await registerProfileHandler(deps(), uid, payload({ birthDate: "1990-01-01" }));
  const antes = (await leer(uid)).data()!;
  const res = await registerProfileHandler(deps(), uid, payload({ birthDate: "2015-01-01", displayName: "Otro", instrument: "cello" }));
  const despues = (await leer(uid)).data()!;
  expect(res).toEqual({ isMinor: false, consentStatus: "pending", requiredPolicyVersion: 1 });
  expect(despues).toEqual(antes);
});

test("idempotencia: una segunda llamada con flujo desactivado no falla si el perfil ya existe", async () => {
  const { uid } = await nuevoUsuario();
  await registerProfileHandler(deps(), uid, payload({ birthDate: "2013-06-01" }));
  const res = await registerProfileHandler(deps({ guardianFlowEnabled: false }), uid, payload({ birthDate: "2013-06-01" }));
  expect(res).toMatchObject({ isMinor: true });
});

test("un role enviado por el cliente se ignora: role=independent (U2)", async () => {
  const { uid } = await nuevoUsuario();
  await registerProfileHandler(deps(), uid, payload({ role: "teacher", isMinor: false, consentStatus: "granted" }));
  expect((await leer(uid)).data()).toMatchObject({ role: "independent", consentStatus: "pending" });
  expect((await auth.getUser(uid)).customClaims).toEqual({ role: "independent", consentOk: false });
});
