import { buildGuardianMail, maskEmail } from "../../src/guardian/mail";

const now = new Date("2026-03-01T10:00:00Z");
const base = { to: "tutor@example.com", link: "https://x.app/tutor?r=abc#t=tok", displayName: "Ana", uid: "u1", now };

describe("buildGuardianMail", () => {
  test("doc mail/ con to, message, uid, kind y expireAt = now + 24 h", () => {
    const m = buildGuardianMail({ ...base, locale: "es" });
    expect(m).toMatchObject({ to: "tutor@example.com", uid: "u1", kind: "guardian_consent" });
    expect(m.expireAt.getTime()).toBe(now.getTime() + 24 * 3600_000);
    expect(m.message.subject).toContain("Ana");
    for (const part of [m.message.text, m.message.html]) expect(part).toContain(base.link);
  });
  test("es / en según el idioma; otros caen a inglés", () => {
    expect(buildGuardianMail({ ...base, locale: "es-ES" }).message.text).toContain("tutor");
    expect(buildGuardianMail({ ...base, locale: "en" }).message.text).toContain("guardian");
    expect(buildGuardianMail({ ...base, locale: "fr" }).message.text).toContain("guardian");
  });
  test("escapa el HTML del nombre", () => {
    const m = buildGuardianMail({ ...base, locale: "en", displayName: `<img src=x onerror="a()">&` });
    expect(m.message.html).not.toContain("<img");
    expect(m.message.html).toContain("&lt;img");
    expect(m.message.html).toContain("&amp;");
  });
});

describe("maskEmail", () => {
  test.each([
    ["pepe@gmail.com", "p***@g***.com"],
    ["a@b.co.uk", "a***@b***.uk"],
    ["x@localhost", "x***@l***"],
  ])("%s -> %s", (e, masked) => expect(maskEmail(e)).toBe(masked));
});
