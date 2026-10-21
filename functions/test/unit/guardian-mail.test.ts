import { buildGuardianMail, buildGuardianRevokeMail, maskEmail } from "../../src/guardian/mail";

const now = new Date("2026-03-01T10:00:00Z");
const base = { to: "tutor@example.com", link: "https://x.app/tutor?r=abc#t=tok", displayName: "Ana", uid: "u1", now };

describe("buildGuardianMail", () => {
  test("doc mail/ con to, message, uid, kind y expireAt = now + 72 h (validez del enlace)", () => {
    const m = buildGuardianMail({ ...base, locale: "es" });
    expect(m).toMatchObject({ to: "tutor@example.com", uid: "u1", kind: "guardian_consent" });
    expect(m.expireAt.getTime()).toBe(now.getTime() + 72 * 3600_000);
    expect(m.message.subject).toContain("Ana");
    for (const part of [m.message.text, m.message.html]) expect(part).toContain(base.link);
  });
  test("es / en según el idioma; otros caen a inglés", () => {
    expect(buildGuardianMail({ ...base, locale: "es-ES" }).message.text).toContain("tutor");
    expect(buildGuardianMail({ ...base, locale: "en" }).message.text).toContain("guardian");
    expect(buildGuardianMail({ ...base, locale: "fr" }).message.text).toContain("guardian");
  });
  test("nombre hostil: escapado en el HTML", () => {
    const m = buildGuardianMail({ ...base, locale: "en", displayName: "<script>alert(1)</script> \"&'" });
    expect(m.message.html).not.toContain("<script");
    expect(m.message.html).toContain("&lt;script&gt;");
    expect(m.message.html).toContain("&quot;&amp;&#39;");
  });
  test("escapa el HTML del nombre", () => {
    const m = buildGuardianMail({ ...base, locale: "en", displayName: `<img src=x onerror="a()">&` });
    expect(m.message.html).not.toContain("<img");
    expect(m.message.html).toContain("&lt;img");
    expect(m.message.html).toContain("&amp;");
  });
});

describe("buildGuardianMail purpose=recording_sharing", () => {
  test("kind y texto propios de la finalidad (grabaciones, voz, 90 días), mismo enlace y misma validez", () => {
    const es = buildGuardianMail({ ...base, locale: "es", purpose: "recording_sharing" });
    expect(es).toMatchObject({ to: "tutor@example.com", uid: "u1", kind: "guardian_recording" });
    expect(es.expireAt.getTime()).toBe(now.getTime() + 72 * 3600_000);
    for (const part of [es.message.text, es.message.html]) expect(part).toContain(base.link);
    expect(es.message.text).toContain("grabaciones");
    expect(es.message.text).toContain("90");
    expect(es.message.subject).toContain("Ana");
    expect(buildGuardianMail({ ...base, locale: "en", purpose: "recording_sharing" }).message.text).toContain("recordings");
  });
  test("sin finalidad sigue siendo el correo de política", () => {
    expect(buildGuardianMail({ ...base, locale: "es" }).kind).toBe("guardian_consent");
    expect(buildGuardianMail({ ...base, locale: "es", purpose: "privacy_policy" }).kind).toBe("guardian_consent");
  });
});

describe("maskEmail", () => {
  test.each([
    ["pepe@gmail.com", "p***@g***.com"],
    ["a@b.co.uk", "a***@b***.uk"],
    ["x@localhost", "x***@l***"],
  ])("%s -> %s", (e, masked) => expect(maskEmail(e)).toBe(masked));
});

describe("buildGuardianRevokeMail", () => {
  const link = "https://x.app/tutor?r=abc#t=rev&a=revoke";
  test("finalidad de grabación: texto propio, no menciona la cuenta del menor", () => {
    const m = buildGuardianRevokeMail({ ...base, link, locale: "es", purpose: "recording_sharing" });
    expect(m.kind).toBe("guardian_revoke");
    expect(m.message.text).toContain("grabaciones");
    expect(m.message.text).not.toContain("dejará de tener acceso");
    expect(m.message.text).toContain(link);
  });
  test("doc mail/ con uid, kind guardian_revoke, enlace de revocación y expireAt = now + 72 h (ventana de entrega, no validez del enlace)", () => {
    const m = buildGuardianRevokeMail({ ...base, link, locale: "es" });
    expect(m).toMatchObject({ to: "tutor@example.com", uid: "u1", kind: "guardian_revoke" });
    expect(m.expireAt.getTime()).toBe(now.getTime() + 72 * 3600_000);
    expect(m.message.text).toContain(link);
    expect(m.message.html).toContain(link.replace("&", "&amp;")); // el & del enlace va escapado en el HTML
    expect(m.message.text).toContain("30");
  });
  test("es / en y nombre hostil escapado", () => {
    expect(buildGuardianRevokeMail({ ...base, link, locale: "es" }).message.subject).toContain("Ana");
    expect(buildGuardianRevokeMail({ ...base, link, locale: "fr" }).message.text).toContain("revoke");
    expect(buildGuardianRevokeMail({ ...base, link, locale: "en", displayName: "<b>x</b>" }).message.html).not.toContain("<b>");
  });
});
