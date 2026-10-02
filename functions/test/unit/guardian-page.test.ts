import vm from "node:vm";
import { generateToken } from "../../src/guardian/token";
import { makeNonce, renderDonePage, renderRejectConfirmPage, renderInvalidPage, renderValidPage, securityHeaders } from "../../src/guardian/page";

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
    expect(html).toContain('name="action" value="reject"');
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
      "default-src 'none'; script-src 'nonce-NONCE1'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    );
    expect(h["Cache-Control"]).toBe("no-store");
    expect(h["Referrer-Policy"]).toBe("no-referrer");
    expect(h["X-Frame-Options"]).toBe("DENY");
    expect(h["X-Content-Type-Options"]).toBe("nosniff");
    expect(h["Content-Type"]).toBe("text/html; charset=utf-8");
    expect(Object.keys(h).map((k) => k.toLowerCase())).not.toContain("set-cookie");
  });
});

test("makeNonce: base64url de 16 bytes y distinto cada vez", () => {
  const n = makeNonce();
  expect(n).toMatch(/^[\w-]{22}$/);
  expect(makeNonce()).not.toBe(n);
});

describe("script inline (ejecutado de verdad)", () => {
  const run = (hash: string) => {
    const script = renderValidPage(input()).match(/<script nonce="NONCE1">([\s\S]*?)<\/script>/)![1];
    const field = { value: "" };
    const replaced: unknown[][] = [];
    vm.runInNewContext(script, {
      location: { hash, pathname: "/tutor", search: "?r=req123" },
      document: { getElementById: (id: string) => (id === "t" ? field : null) },
      history: { replaceState: (...a: unknown[]) => void replaced.push(a) },
    });
    return { field, replaced };
  };

  test("copia el token completo del fragmento y limpia la URL", () => {
    const t = generateToken();
    const { field, replaced } = run(`#t=${t}`);
    expect(field.value).toBe(t);
    expect(replaced).toEqual([[null, "", "/tutor?r=req123"]]);
  });

  test("con otros parámetros en el fragmento (#a=reject&t=...) también", () => {
    const t = generateToken();
    expect(run(`#a=reject&t=${t}&x=1`).field.value).toBe(t);
  });

  test("sin token en el fragmento deja el campo vacío", () => {
    expect(run("").field.value).toBe("");
  });
});

test("policyUrl debe ser https (error de configuración si no)", () => {
  expect(() => renderValidPage(input({ policyUrl: "http://x.app/p" }))).toThrow(/https/);
  expect(() => renderValidPage(input({ policyUrl: "javascript:alert(1)" }))).toThrow(/https/);
});

describe("renderRejectConfirmPage (rechazo en dos pasos, sin JS)", () => {
  const html = renderRejectConfirmPage({ requestId: "req123", token: 'tok"><b>', displayName: "Ana <i>", locale: "es" });

  test("advierte del borrado, escapa nombre y token, reenvía r/t/action/confirm y ofrece cancelar", () => {
    expect(html).toContain("Ana &lt;i&gt;");
    expect(html).not.toContain("<i>");
    expect(html).toContain('name="t" value="tok&quot;&gt;&lt;b&gt;"');
    expect(html).toContain('name="r" value="req123"');
    expect(html).toContain('name="action" value="reject"');
    expect(html).toContain('name="confirm" value="yes"');
    expect(html).toContain('href="/tutor?r=req123"');
    expect(html).toMatch(/elimin/i);
    expect(html).not.toContain("<script");
  });
});
