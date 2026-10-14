import { readFileSync } from "node:fs";
import { join } from "node:path";
import { CURRENT_POLICY_VERSION, POLICY_URL } from "../../src/config/identity";

const html = readFileSync(join(__dirname, "../../../hosting/public/politica/index.html"), "utf8");
const firebase = JSON.parse(readFileSync(join(__dirname, "../../../firebase.json"), "utf8"));
const section = (lang: "es" | "en") => {
  const m = html.match(new RegExp(String.raw`<section lang="${lang}"[^>]*>([\s\S]*?)</section>`));
  expect(m).not.toBeNull();
  return m![1].toLowerCase();
};

describe("A11.2 politica v2 publicada (REQ-CON-R04)", () => {
  test("la version vigente es 2", () => {
    expect(CURRENT_POLICY_VERSION).toBe(2);
  });

  test("el marcador de version del hosting coincide con la vigente y la URL publica la sirve", () => {
    expect(html).toContain(`data-policy-version="${CURRENT_POLICY_VERSION}"`);
    const rewrite = firebase.hosting.rewrites.find((r: { source: string }) => r.source === new URL(POLICY_URL).pathname);
    expect(rewrite?.destination).toBe("/politica/index.html");
  });

  test.each([
    ["es", ["profesor", "vínculo", "tareas", "grabaciones", "vídeo", "difumin", "20 ", "90 días", "revocar"]],
    ["en", ["teacher", "link", "assignments", "recordings", "video", "blur", "20 ", "90 days", "revoke"]],
  ] as const)("el texto %s cubre la fase 4 (vinculo, tareas, grabaciones, video, blur, cupo, retencion, revocacion)", (lang, terms) => {
    const text = section(lang);
    for (const t of terms) expect(text).toContain(t);
  });

  test("ya no es un marcador provisional", () => {
    expect(html.toLowerCase()).not.toContain("marcador provisional");
    expect(html.toLowerCase()).not.toContain("placeholder");
  });
});
