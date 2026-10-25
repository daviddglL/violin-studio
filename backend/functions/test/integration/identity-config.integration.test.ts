import functionsTest from "firebase-functions-test";
import { CURRENT_POLICY_VERSION, RECORDING_CONSENT_VERSION } from "../../src/config/identity";
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
    policyVersion: CURRENT_POLICY_VERSION,
    policyUrl: "https://violin-app-dev-f0b55.web.app/privacy",
    digitalConsentAge: 14,
    guardianFlowEnabled: true,
    // Emulador/test: interruptores encendidos por defecto (en producción salen del .env y apagados).
    recordingsEnabled: true,
    videoEnabled: true,
    minorRecordingEnabled: true,
    recordingConsentVersion: RECORDING_CONSENT_VERSION,
    chatEnabled: true,
    notificationsEnabled: true,
  });
});
