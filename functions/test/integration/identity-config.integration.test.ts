import functionsTest from "firebase-functions-test";
import { identityConfig } from "../../src/index";

const fft = functionsTest();
afterAll(() => fft.cleanup());
const llamar = identityConfig && fft.wrap(identityConfig);

test("exige sesión", async () => {
  await expect(llamar({ data: {} } as never)).rejects.toMatchObject({ code: "unauthenticated" });
});

test("no exige email verificado y devuelve la política vigente concreta (C6)", async () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const res = await llamar({ data: {}, auth: { uid: "u1", token: { email_verified: false } } } as any);
  expect(res).toEqual({
    policyVersion: 1,
    policyUrl: "https://violin-app-dev-f0b55.web.app/privacy",
    digitalConsentAge: 14,
    guardianFlowEnabled: true,
  });
});
