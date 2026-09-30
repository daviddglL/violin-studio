import { HttpsError } from "firebase-functions/v2/https";
import { ErrorReason, fail } from "../../src/common/errors";

describe("fail", () => {
  test("produce un HttpsError con details.reason", () => {
    const err = fail("failed-precondition", ErrorReason.EMAIL_NOT_VERIFIED, { hint: "x" });
    expect(err).toBeInstanceOf(HttpsError);
    expect(err.code).toBe("failed-precondition");
    expect(err.details).toEqual({ reason: "EMAIL_NOT_VERIFIED", hint: "x" });
  });
  test("sin details extra solo lleva la reason", () => {
    expect(fail("invalid-argument", ErrorReason.INVALID_ARGUMENT).details).toEqual({ reason: "INVALID_ARGUMENT" });
  });
  test("el enum de razones es estable", () => {
    expect(Object.values(ErrorReason).sort()).toEqual(
      [
        "EMAIL_NOT_VERIFIED", "INVALID_BIRTH_DATE", "UNDERAGE_NOT_ALLOWED", "NO_PROFILE", "GUARDIAN_REQUIRED",
        "POLICY_OUTDATED", "NOT_MINOR", "GUARDIAN_EMAIL_INVALID", "RATE_LIMITED", "REAUTH_REQUIRED",
        "ERASURE_FAILED", "INVALID_ARGUMENT",
      ].sort(),
    );
  });
});
