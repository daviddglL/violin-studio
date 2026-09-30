import functionsTest from "firebase-functions-test";
import { identityConfig } from "../../src/index";
import * as identity from "../../src/config/identity";

const fft = functionsTest();
afterAll(() => fft.cleanup());
const llamar = identityConfig && fft.wrap(identityConfig);

test("exige sesión", async () => {
  await expect(llamar({ data: {} } as never)).rejects.toMatchObject({ code: "unauthenticated" });
});

test("no exige email verificado y devuelve la política tomada de identity.ts (C6)", async () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const res = await llamar({ data: {}, auth: { uid: "u1", token: { email_verified: false } } } as any);
  expect(res).toEqual({
    policyVersion: identity.CURRENT_POLICY_VERSION,
    policyUrl: identity.POLICY_URL,
    digitalConsentAge: identity.DIGITAL_CONSENT_AGE,
    guardianFlowEnabled: identity.GUARDIAN_FLOW_ENABLED,
  });
});
