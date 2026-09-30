import { HttpsError } from "firebase-functions/v2/https";
import { CLOCK_SKEW_SECONDS, requireRecentAuth, requireVerifiedUser } from "../../src/common/auth-guard";

function fallo(fn: () => unknown): HttpsError {
  try {
    fn();
  } catch (e) {
    return e as HttpsError;
  }
  throw new Error("no lanzó");
}
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const req = (auth: unknown) => ({ auth }) as any;

describe("requireVerifiedUser", () => {
  test("sin auth -> unauthenticated", () => {
    expect(fallo(() => requireVerifiedUser(req(undefined))).code).toBe("unauthenticated");
  });
  test("email no verificado -> failed-precondition/EMAIL_NOT_VERIFIED", () => {
    const err = fallo(() => requireVerifiedUser(req({ uid: "u", token: { email_verified: false } })));
    expect(err.code).toBe("failed-precondition");
    expect(err.details).toMatchObject({ reason: "EMAIL_NOT_VERIFIED" });
  });
  test("email_verified ausente o no booleano true se rechaza", () => {
    expect(fallo(() => requireVerifiedUser(req({ uid: "u", token: {} }))).code).toBe("failed-precondition");
    expect(fallo(() => requireVerifiedUser(req({ uid: "u", token: { email_verified: "true" } }))).code).toBe(
      "failed-precondition",
    );
  });
  test("token verificado (p. ej. Google) pasa y devuelve uid y token", () => {
    const token = { email_verified: true, firebase: { sign_in_provider: "google.com" } };
    expect(requireVerifiedUser(req({ uid: "u1", token }))).toEqual({ uid: "u1", token });
  });
});

describe("requireRecentAuth", () => {
  const now = new Date("2026-01-01T12:00:00Z");
  const nowSec = now.getTime() / 1000;
  test("4 min 59 s pasa", () => {
    expect(() => requireRecentAuth({ auth_time: nowSec - 299 }, now)).not.toThrow();
  });
  test("exactamente 300 s rechaza con REAUTH_REQUIRED", () => {
    const err = fallo(() => requireRecentAuth({ auth_time: nowSec - 300 }, now));
    expect(err.code).toBe("failed-precondition");
    expect(err.details).toMatchObject({ reason: "REAUTH_REQUIRED" });
  });
  test("5 min 01 s rechaza", () => {
    expect(fallo(() => requireRecentAuth({ auth_time: nowSec - 301 }, now)).code).toBe("failed-precondition");
  });
  test("auth_time ausente o no numérico rechaza", () => {
    expect(fallo(() => requireRecentAuth({}, now)).details).toMatchObject({ reason: "REAUTH_REQUIRED" });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(fallo(() => requireRecentAuth({ auth_time: "1" } as any, now)).code).toBe("failed-precondition");
  });
  test("auth_time en el futuro dentro de la tolerancia (reloj desfasado) se acepta", () => {
    expect(() => requireRecentAuth({ auth_time: nowSec + 5 }, now)).not.toThrow();
    expect(() => requireRecentAuth({ auth_time: nowSec + CLOCK_SKEW_SECONDS }, now)).not.toThrow();
  });
  test("auth_time en el futuro más allá de la tolerancia rechaza", () => {
    const err = fallo(() => requireRecentAuth({ auth_time: nowSec + CLOCK_SKEW_SECONDS + 1 }, now));
    expect(err.code).toBe("failed-precondition");
    expect((err.details as { reason: string }).reason).toBe("REAUTH_REQUIRED");
  });
});
