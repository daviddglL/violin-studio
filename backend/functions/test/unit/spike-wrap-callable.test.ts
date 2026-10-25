import functionsTest from "firebase-functions-test";
import { onCall } from "firebase-functions/v2/https";

const fft = functionsTest();
afterAll(() => fft.cleanup());

// Contrato de la suite: comprueba que wrap() de callables v2 propaga request.auth.token al handler.
test("wrap() entrega request.auth.token al handler de un callable v2", async () => {
  const eco = onCall((request) => ({ token: request.auth?.token ?? null, uid: request.auth?.uid ?? null }));
  const authTime = 1_700_000_000;
  const wrapped = fft.wrap(eco);
  const res = await wrapped({
    data: {},
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    auth: { uid: "u1", token: { email_verified: false, auth_time: authTime } as any },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);
  expect(res).toEqual({ token: { email_verified: false, auth_time: authTime }, uid: "u1" });
});
