import { guardianHttpAdapter } from "../../src/guardian/http";
import { GuardianHttpRequest, GuardianHttpResponse } from "../../src/guardian/confirm";

const fakeRes = () => {
  const r = { code: 0, headers: {} as Record<string, string>, body: "", cookies: 0 };
  const res = {
    status: (c: number) => ((r.code = c), res),
    set: (h: Record<string, string>) => ((r.headers = { ...r.headers, ...h }), res),
    setHeader: (k: string, v: string) => ((r.headers[k] = v), res),
    send: (b: string) => ((r.body = b), res),
    cookie: () => void r.cookies++,
  };
  return { res: res as never, r };
};
const run = async (req: Record<string, unknown>, handler: (q: GuardianHttpRequest) => Promise<GuardianHttpResponse>) => {
  const f = fakeRes();
  await guardianHttpAdapter(handler)(req as never, f.res);
  return f.r;
};
let seen: GuardianHttpRequest | undefined;
const ok = async (q: GuardianHttpRequest): Promise<GuardianHttpResponse> => {
  seen = q;
  return { status: 201, headers: { "Cache-Control": "no-store", "X-Frame-Options": "DENY" }, body: "<p>ok</p>" };
};

test("copia estado, TODAS las cabeceras y cuerpo; no usa cookies", async () => {
  const r = await run({ method: "GET", query: { r: "abc" }, body: undefined }, ok);
  expect([r.code, r.headers, r.body, r.cookies]).toEqual([201, { "Cache-Control": "no-store", "X-Frame-Options": "DENY" }, "<p>ok</p>", 0]);
  expect(seen).toEqual({ method: "GET", query: { r: "abc" }, body: {} });
});

test("cuerpos: objeto ya parseado (urlencoded/JSON), cadena urlencoded, Buffer y basura", async () => {
  await run({ method: "POST", query: {}, body: { r: "x", t: "y" } }, ok);
  expect(seen?.body).toEqual({ r: "x", t: "y" });
  await run({ method: "POST", query: {}, body: "r=x&t=y&declaration=on" }, ok);
  expect(seen?.body).toEqual({ r: "x", t: "y", declaration: "on" });
  await run({ method: "POST", query: {}, body: Buffer.from("r=x") }, ok);
  expect(seen?.body).toEqual({ r: "x" });
  for (const raro of [null, 42, [1, 2], true]) {
    await run({ method: "POST", query: {}, body: raro }, ok);
    expect(seen?.body).toEqual({});
  }
});

test("sin query ni método no rompe; el método se normaliza a mayúsculas", async () => {
  await run({ body: {} }, ok);
  expect(seen).toEqual({ method: "", query: {}, body: {} });
  await run({ method: "post", query: {}, body: {} }, ok);
  expect(seen?.method).toBe("POST");
});

test("si el handler lanza: 404 genérico con las cabeceras de seguridad, jamás 500", async () => {
  const r = await run({ method: "POST", query: {}, body: {} }, () => Promise.reject(new Error("uid-secreto")));
  expect(r.code).toBe(404);
  expect(r.headers["Cache-Control"]).toBe("no-store");
  expect(r.body).not.toContain("uid-secreto");
});

test("cuerpos hostiles: __proto__ y parámetros duplicados no contaminan ni rompen", async () => {
  await run({ method: "POST", query: {}, body: "__proto__=x&constructor=y&a=1&a=2" }, ok);
  expect(Object.getPrototypeOf(seen?.body)).toBe(Object.prototype);
  expect(({} as Record<string, unknown>).a).toBeUndefined();
  expect((({}) as { __proto__: unknown }).__proto__).toBe(Object.prototype);
  expect(seen?.body.a).toBe("2");
  const json = JSON.parse('{"__proto__":{"polluted":true},"r":"x"}');
  const r = await run({ method: "POST", query: { r: ["a", "b"] }, body: json }, ok);
  expect(r.code).toBe(201);
  expect(({} as Record<string, unknown>).polluted).toBeUndefined();
});
