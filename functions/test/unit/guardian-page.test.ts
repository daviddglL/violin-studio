import { makeNonce, renderDonePage, renderInvalidPage, renderValidPage, securityHeaders } from "../../src/guardian/page";

const input = (over: Partial<Parameters<typeof renderValidPage>[0]> = {}) => ({
  requestId: "req123",
  displayName: "Ana",
  locale: "es",
  policyUrl: "https://x.app/politica/",
  policyVersion: 3,
  nonce: "NONCE1",
  ...over,
});

describe("renderValidPage", () => {
  test("escapa el displayName y el requestId", () => {
    const html = renderValidPage(input({ displayName: `<img src=x onerror=alert(1)>"'&`, requestId: `"><script>x</script>` }));
    expect(html).not.toContain("<img");
    expect(html).not.toContain("<script>x");
    expect(html).toContain("&lt;img src=x onerror=alert(1)&gt;&quot;&#39;&amp;");
  });

  test("formulario con declaración, acciones, política y campo de token oculto vacío", () => {
    const html = renderValidPage(input());
    expect(html).toContain('method="post"');
    expect(html).toContain('name="r" value="req123"');
    expect(html).toMatch(/<input type="hidden" name="t" id="t" value="">/);
    expect(html).toContain('name="declaration"');
    expect(html).toContain('name="action" value="accept"');
    expect(html).toContain('href="https://x.app/politica/"');
    expect(html).toContain("Ana");
  });

  test("el script lleva el nonce, lee el fragmento y <noscript> explica el JavaScript", () => {
    const html = renderValidPage(input());
    expect(html).toContain('<script nonce="NONCE1">');
    expect(html).toContain("location.hash");
    expect(html).toMatch(/<noscript>[\s\S]*JavaScript[\s\S]*<\/noscript>/);
  });

  test("el idioma sigue el locale del menor (es / resto en inglés)", () => {
    expect(renderValidPage(input({ locale: "es-ES" }))).toContain('lang="es"');
    expect(renderValidPage(input({ locale: "fr" }))).toContain('lang="en"');
  });
});

test("la página de enlace no válido es estática (idéntica siempre) y sin script", () => {
  const a = renderInvalidPage();
  expect(renderInvalidPage()).toBe(a);
  expect(a).not.toContain("<script");
  expect(a).not.toMatch(/Ana|req123/);
});

test("la página de éxito no contiene datos del menor", () => {
  expect(renderDonePage()).not.toContain("<script");
});

describe("securityHeaders", () => {
  test("CSP con nonce y resto de cabeceras, sin cookies", () => {
    const h = securityHeaders("NONCE1");
    expect(h["Content-Security-Policy"]).toBe(
      "default-src 'none'; script-src 'nonce-NONCE1'; style-src 'unsafe-inline'; form-action 'self'",
    );
    expect(h["Cache-Control"]).toBe("no-store");
    expect(h["Referrer-Policy"]).toBe("no-referrer");
    expect(h["X-Frame-Options"]).toBe("DENY");
    expect(h["Content-Type"]).toBe("text/html; charset=utf-8");
    expect(Object.keys(h).map((k) => k.toLowerCase())).not.toContain("set-cookie");
  });
});

test("makeNonce: base64url de 16 bytes y distinto cada vez", () => {
  const n = makeNonce();
  expect(n).toMatch(/^[\w-]{22}$/);
  expect(makeNonce()).not.toBe(n);
});
