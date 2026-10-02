import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { REGION } from "../../src/config/runtime";

const raiz = join(__dirname, "../../..");
const leer = (f: string) => readFileSync(join(raiz, f), "utf8");

const parseEnv = (texto: string): Record<string, string> =>
  Object.fromEntries(
    texto
      .split(/\r?\n/)
      .filter((l) => l.trim() !== "" && !l.trim().startsWith("#"))
      .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
  );

describe("firebase.json extensions (Trigger Email)", () => {
  const cfg = JSON.parse(leer("firebase.json"));

  test("declara firestore-send-email con versión fijada (sin latest ni rangos)", () => {
    const ref: string = cfg.extensions?.["firestore-send-email"];
    expect(ref).toMatch(/^firebase\/firestore-send-email@\d+\.\d+\.\d+$/);
  });

  test("no activa el emulador de extensiones por defecto", () => {
    expect(cfg.emulators?.extensions).toBeUndefined();
  });
});

describe("extensions/firestore-send-email.env", () => {
  const ruta = "extensions/firestore-send-email.env";

  test("MAIL_COLLECTION=mail y LOCATION en la región de las functions", () => {
    expect(existsSync(join(raiz, ruta))).toBe(true);
    const env = parseEnv(leer(ruta));
    expect(env.MAIL_COLLECTION).toBe("mail");
    expect(env.LOCATION).toBe(REGION);
    expect(env.LOCATION).toMatch(/^europe-/);
  });

  test("sin credenciales SMTP: ni contraseña ni usuario:clave en el URI", () => {
    const env = parseEnv(leer(ruta));
    expect(env.SMTP_PASSWORD).toBeUndefined();
    const uri = env.SMTP_CONNECTION_URI;
    if (uri !== undefined) expect(uri).not.toMatch(/\/\/[^/@]*:[^/@]+@/);
  });
});

describe("ficheros rastreados", () => {
  const rastreados = execFileSync("git", ["-C", raiz, "ls-files"], { encoding: "utf8" })
    .split("\n")
    .filter(Boolean);

  test("el .env.local de la extensión no está rastreado y está ignorado por git", () => {
    expect(rastreados.filter((f) => /firestore-send-email\.env\.local$/.test(f))).toEqual([]);
    const ignorado = execFileSync(
      "git",
      ["-C", raiz, "check-ignore", "extensions/firestore-send-email.env.local"],
      { encoding: "utf8" },
    );
    expect(ignorado.trim()).toBe("extensions/firestore-send-email.env.local");
  });

  test("el .secret.local de la extensión está ignorado por git", () => {
    const ignorado = execFileSync(
      "git",
      ["-C", raiz, "check-ignore", "extensions/firestore-send-email.secret.local"],
      { encoding: "utf8" },
    );
    expect(ignorado.trim()).toBe("extensions/firestore-send-email.secret.local");
  });

  test("ningún fichero rastreado de extensions/ ni de config contiene un SMTP URI con clave", () => {
    const candidatos = rastreados.filter(
      (f) => f.startsWith("extensions/") || f === "firebase.json" || f.startsWith(".github/"),
    );
    for (const f of candidatos) {
      expect(leer(f)).not.toMatch(/smtps?:\/\/[^\s/@:]+:[^\s/@]+@/i);
    }
  });
});

describe("CI y FINT no cargan la extensión", () => {
  const ci = leer(".github/workflows/ci.yml");

  test("el FINT usa functions,auth,firestore,storage,hosting y no la extensión", () => {
    const fint = ci
      .split(/\r?\n/)
      .filter((l) => l.includes("emulators:exec") && l.includes("test:integration"));
    expect(fint.length).toBeGreaterThan(0);
    for (const l of fint) {
      expect(l).toContain("--only functions,auth,firestore,storage,hosting");
      expect(l).not.toContain("extensions");
    }
  });

  // El emulador de extensiones exige `firebase login` para resolver la extensión
  // del registro, y el runner no está autenticado: la prueba es solo local.
  test("el CI nunca arranca el emulador de extensiones", () => {
    expect(ci).not.toContain("test:mail-extension");
    expect(ci).not.toMatch(/--only [a-z,]*extensions/);
  });
});

describe("firebase.json hosting (página del tutor y política)", () => {
  const cfg = JSON.parse(leer("firebase.json"));

  test("sirve hosting/public y reescribe /tutor a la función guardianConsent en la región", () => {
    expect(cfg.hosting.public).toBe("hosting/public");
    expect(cfg.hosting.rewrites).toContainEqual({ source: "/tutor", function: { functionId: "guardianConsent", region: REGION } });
    expect(cfg.emulators?.hosting?.port).toBeDefined();
  });

  test("la página estática de política existe y declara la versión vigente", () => {
    const html = leer("hosting/public/politica/index.html");
    expect(html).toContain(`data-policy-version="${CURRENT_POLICY_VERSION}"`);
  });

  test("el FINT incluye el emulador de hosting", () => {
    expect(leer(".github/workflows/ci.yml")).toMatch(/emulators:exec --only [a-z,]*hosting[a-z,]* .*test:integration/);
  });
});
