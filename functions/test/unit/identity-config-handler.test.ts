import { identityConfigHandler } from "../../src/profile/identity-config";

/** Firestore/Auth falsos mínimos: un único documento de usuario y contadores de llamadas. */
function fakes(userDoc: Record<string, unknown> | null, claims: Record<string, unknown> = {}, falloTx = false) {
  const updates: Record<string, unknown>[] = [];
  const setClaims = jest.fn(async () => undefined);
  const ref = { path: "users/u1", get: async () => ({ exists: userDoc !== null, data: () => userDoc ?? undefined }) };
  const tx = {
    get: async () => ({ exists: userDoc !== null, data: () => userDoc ?? undefined }),
    update: (_r: unknown, d: Record<string, unknown>) => void updates.push(d),
  };
  const db = {
    collection: () => ({ doc: () => ref }),
    runTransaction: async (fn: (t: unknown) => unknown) => {
      if (falloTx) throw Object.assign(new Error("secreto@example.com"), { code: "unavailable" });
      return fn(tx);
    },
  };
  const auth = { getUser: async () => ({ customClaims: claims }), setCustomUserClaims: setClaims };
  return { db, auth, setClaims, updates };
}
const req = (consentOk?: boolean) => ({ auth: { uid: "u1", token: consentOk === undefined ? {} : { consentOk } } });
const granted = { role: "independent", isMinor: false, consentStatus: "granted", policyVersion: 1 };

test("si la reevaluación falla, identityConfig devuelve la política y registra solo un código sin PII", async () => {
  const { db, auth } = fakes(granted, {}, true);
  const log = jest.fn();
  const cfg = await identityConfigHandler(req(true), { db, auth, log, currentVersion: 1 } as never);
  expect(cfg.policyVersion).toBe(1);
  expect(log).toHaveBeenCalledTimes(1);
  expect(JSON.stringify(log.mock.calls)).not.toContain("secreto@example.com");
  expect(log.mock.calls[0][1]).toEqual({ code: "unavailable" });
});

test("sin cambios y con el claim del token ya correcto no se llama a setCustomUserClaims ni a getUser", async () => {
  const { db, auth, setClaims } = fakes(granted, { role: "independent", consentOk: false });
  const getUser = jest.spyOn(auth, "getUser");
  await identityConfigHandler(req(true), { db, auth, currentVersion: 1 } as never);
  expect(getUser).not.toHaveBeenCalled();
  expect(setClaims).not.toHaveBeenCalled();
});

test("sin cambios pero con el claim del token desfasado se sincroniza (curación)", async () => {
  const { db, auth, setClaims } = fakes(granted, { role: "independent", consentOk: false });
  await identityConfigHandler(req(false), { db, auth, currentVersion: 1 } as never);
  expect(setClaims).toHaveBeenCalledWith("u1", { role: "independent", consentOk: true });
});

test("con el claim ausente en el token también se sincroniza", async () => {
  const { db, auth, setClaims } = fakes(granted, {});
  await identityConfigHandler({ auth: { uid: "u1" } }, { db, auth, currentVersion: 1 } as never);
  expect(setClaims).toHaveBeenCalled();
});

test("si la reevaluación cambia el estado se sincroniza aunque el token coincidiera con el estado anterior", async () => {
  const { db, auth, setClaims, updates } = fakes(granted, { role: "independent", consentOk: true });
  await identityConfigHandler(req(true), { db, auth, currentVersion: 2 } as never);
  expect(updates[0]).toMatchObject({ consentStatus: "pending" });
  expect(setClaims).toHaveBeenCalledWith("u1", { role: "independent", consentOk: false });
});

test("un documento con deletion no se toca aunque tenga una versión vieja", async () => {
  const { db, auth, updates } = fakes({ ...granted, deletion: { state: "in_progress" } }, { role: "independent", consentOk: false });
  await identityConfigHandler(req(false), { db, auth, currentVersion: 2 } as never);
  expect(updates).toEqual([]);
});
