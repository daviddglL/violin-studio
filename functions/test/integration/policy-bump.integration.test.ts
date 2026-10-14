import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { reevaluateAllConsents } from "../../src/consent/reevaluate-all";
import { reevaluateConsent } from "../../src/consent/reevaluate";
import { recordConsentHandler } from "../../src/consent/record-consent";
import { COLLECTIONS } from "../../src/common/collections";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { requestGuardianConsentHandler } from "../../src/guardian/request";
import { purgeIdentityHandler } from "../../src/maintenance/purge";
import { linkId } from "../../src/teacher/link-id";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const auth = getAuth(app);
const db = getFirestore(app);
const users = db.collection(COLLECTIONS.users);

const V2 = CURRENT_POLICY_VERSION;
const V1 = V2 - 1;
const DAY = 24 * 3600_000;
const MIN = 60_000;
const log = () => undefined;
let seq = 0;

async function wipe() {
  const fs = process.env.FIRESTORE_EMULATOR_HOST;
  const au = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  await fetch(`http://${fs}/emulator/v1/projects/${project}/databases/(default)/documents`, { method: "DELETE" });
  await fetch(`http://${au}/emulator/v1/projects/${project}/accounts`, { method: "DELETE" });
}
// La reevaluacion masiva recorre TODOS los granted: cada test parte de un emulador vacio.
beforeEach(wipe);

async function account(fields: Record<string, unknown>, claims: Record<string, unknown> = { consentOk: true }) {
  const { uid } = await auth.createUser({ email: `pb-${Date.now()}-${seq++}@example.com`, password: "Passw0rd!x" });
  await users.doc(uid).set({ role: "independent", isMinor: false, birthDate: "1990-01-01", displayName: "Ana", ...fields });
  await auth.setCustomUserClaims(uid, { role: fields.role ?? "independent", ...claims });
  return uid;
}
const grantedV1 = (fields: Record<string, unknown> = {}) => account({ consentStatus: "granted", policyVersion: V1, ...fields });
const perfil = async (uid: string) => (await users.doc(uid).get()).data()!;
const consentOk = async (uid: string) => (await auth.getUser(uid)).customClaims?.consentOk;

describe("A11.1 reevaluacion masiva tras el bump (REQ-CON-R04)", () => {
  test("granted de version anterior -> pending con claims a false; al dia, revocados y pendientes no se tocan", async () => {
    const adulto = await grantedV1();
    const alDia = await account({ consentStatus: "granted", policyVersion: V2 });
    const revocado = await account({ consentStatus: "revoked", policyVersion: V1 }, { consentOk: false });
    const antes = await perfil(alDia);

    const res = await reevaluateAllConsents({ db, auth, log });

    expect(res).toMatchObject({ scanned: 2, changed: 1, failed: 0 });
    expect((await perfil(adulto)).consentStatus).toBe("pending");
    expect(await consentOk(adulto)).toBe(false);
    expect(await perfil(alDia)).toEqual(antes);
    expect(await consentOk(alDia)).toBe(true);
    expect((await perfil(revocado)).consentStatus).toBe("revoked");
  });

  test("recorre varias paginas, es idempotente y un usuario que falla no detiene el lote", async () => {
    const uids = await Promise.all([1, 2, 3, 4, 5].map(() => grantedV1()));
    const roto = uids[1];
    const res = await reevaluateAllConsents({
      db,
      auth,
      log,
      pageSize: 2,
      reevaluate: async (deps, uid) => {
        if (uid === roto) throw new Error("boom");
        return reevaluateConsent(deps, uid);
      },
    });
    expect(res).toMatchObject({ changed: 4, failed: 1 });
    expect((await perfil(roto)).consentStatus).toBe("granted");
    for (const uid of uids.filter((u) => u !== roto)) expect((await perfil(uid)).consentStatus).toBe("pending");

    const again = await reevaluateAllConsents({ db, auth, log, pageSize: 2 });
    expect(again).toMatchObject({ changed: 1, failed: 0 });
    expect((await perfil(roto)).consentStatus).toBe("pending");
    expect(await reevaluateAllConsents({ db, auth, log })).toMatchObject({ changed: 0, failed: 0 });
  });

  test("el log no lleva uid ni correo", async () => {
    const uid = await grantedV1();
    const lines: string[] = [];
    await reevaluateAllConsents({
      db,
      auth,
      log: (m, d) => void lines.push(JSON.stringify([m, d])),
      reevaluate: async () => {
        throw new Error(`fallo ${uid} pb@example.com`);
      },
    });
    expect(lines.length).toBeGreaterThan(0);
    expect(lines.join("")).not.toContain(uid);
    expect(lines.join("")).not.toContain("@example.com");
  });

  test("alumno y profesor vinculados: el vinculo se conserva; ambos quedan pending hasta re-consentir", async () => {
    const teacher = await grantedV1({ role: "teacher", studentCount: 1 });
    const student = await grantedV1({ role: "student", teacherCount: 1 });
    const link = linkId(teacher, student);
    await db.collection(COLLECTIONS.teacherLinks).doc(link).set({ teacherUid: teacher, studentUid: student, ackVersion: 1 });

    await reevaluateAllConsents({ db, auth, log });

    expect((await perfil(teacher)).consentStatus).toBe("pending");
    expect((await perfil(student)).consentStatus).toBe("pending");
    expect(await consentOk(teacher)).toBe(false);
    expect((await db.collection(COLLECTIONS.teacherLinks).doc(link).get()).exists).toBe(true);
    expect(await perfil(student)).toMatchObject({ role: "student", teacherCount: 1 });

    await recordConsentHandler({ db, auth, log }, teacher, { policyVersion: V2 });
    await recordConsentHandler({ db, auth, log }, student, { policyVersion: V2 });
    expect(await perfil(teacher)).toMatchObject({ consentStatus: "granted", policyVersion: V2 });
    expect(await consentOk(student)).toBe(true);
    expect((await db.collection(COLLECTIONS.teacherLinks).doc(link).get()).exists).toBe(true);
  });
});

describe("A11.1 menores tras el bump (REQ-GRD-R04)", () => {
  const PEPPER = "t".repeat(32);
  const erase = jest.fn(async () => undefined);
  const purge = (now: Date) => purgeIdentityHandler({ db, auth, erase, log }, now);
  beforeEach(() => erase.mockClear());

  test("menor granted con requestedAt de hace 30 d: pending y no se borra; al pedir tutor se reinicia el plazo de 7 d", async () => {
    const t0 = new Date();
    const menor = await grantedV1({
      isMinor: true,
      birthDate: "2018-01-01",
      guardian: { requestedAt: Timestamp.fromMillis(t0.getTime() - 30 * DAY), emailMasked: "t***@e***.com" },
    });

    await reevaluateAllConsents({ db, auth, log });
    expect(await perfil(menor)).toMatchObject({ consentStatus: "pending", isMinor: true });

    // La purga mide parental_pending desde requestedAt y el menor no esta en ese estado.
    await purge(t0);
    expect(erase).not.toHaveBeenCalledWith(menor, expect.anything());

    // requestGuardianConsent esta permitido (la version ya no es la vigente) y reinicia requestedAt.
    const askedAt = new Date(t0.getTime() + MIN);
    await requestGuardianConsentHandler(
      { db, pepper: PEPPER, linkBaseUrl: "https://x.app", guardianFlowEnabled: true, clock: () => askedAt, log },
      menor,
      undefined,
      { guardianEmail: `tutor-${Date.now()}@example.com` },
    );
    const p = await perfil(menor);
    expect(p.consentStatus).toBe("parental_pending");
    expect((p.guardian.requestedAt as Timestamp).toMillis()).toBe(askedAt.getTime());

    await purge(new Date(askedAt.getTime() + 7 * DAY - MIN));
    expect(erase).not.toHaveBeenCalledWith(menor, expect.anything());
    // Sin confirmacion 7 d y 1 min despues de pedir al tutor: comportamiento vigente (se borra).
    await purge(new Date(askedAt.getTime() + 7 * DAY + MIN));
    expect(erase).toHaveBeenCalledWith(menor, { deleteAuth: true });
  });

  test("el bump no borra ninguna cuenta de menor, aunque la purga corra 60 dias despues sin que pidan tutor", async () => {
    const t0 = new Date();
    const menor = await grantedV1({ isMinor: true, birthDate: "2018-01-01" });
    await reevaluateAllConsents({ db, auth, log });
    await purge(new Date(t0.getTime() + 60 * DAY));
    expect(erase).not.toHaveBeenCalledWith(menor, expect.anything());
    expect((await users.doc(menor).get()).exists).toBe(true);
  });
});
