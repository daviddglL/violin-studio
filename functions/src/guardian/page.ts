import { randomBytes } from "node:crypto";
import { escapeHtml as esc } from "./mail";

export interface ValidPageInput {
  requestId: string;
  displayName: string;
  locale: string;
  policyUrl: string;
  policyVersion: number;
  nonce: string;
}

export const makeNonce = (): string => randomBytes(16).toString("base64url");

/** Cabeceras de TODAS las respuestas: sin caché, sin referrer, sin marcos, CSP con nonce y sin cookies. */
export function securityHeaders(nonce: string): Record<string, string> {
  return {
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Frame-Options": "DENY",
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`,
  };
}

const STYLE = "body{font-family:sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem}button{margin:.5rem .5rem 0 0;padding:.6rem 1rem}";
const shell = (lang: string, title: string, body: string) =>
  `<!doctype html><html lang="${lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>${STYLE}</style></head><body>${body}</body></html>`;

const TEXT = {
  es: {
    title: "Consentimiento del tutor legal",
    intro: (n: string) => `${n} quiere usar Violin Studio, una app para estudiar violín, y necesita tu consentimiento como madre, padre o tutor legal.`,
    policy: (v: number) => `Política de privacidad (versión ${v})`,
    declaration: "Declaro ser su madre, padre o tutor legal.",
    accept: "Aceptar",
    noscript: "Esta página necesita JavaScript para leer tu enlace de forma segura. Actívalo y vuelve a abrir el enlace del correo.",
  },
  en: {
    title: "Legal guardian consent",
    intro: (n: string) => `${n} wants to use Violin Studio, a violin practice app, and needs your consent as a parent or legal guardian.`,
    policy: (v: number) => `Privacy policy (version ${v})`,
    declaration: "I declare that I am their parent or legal guardian.",
    accept: "Accept",
    noscript: "This page needs JavaScript to read your link securely. Enable it and open the link from the email again.",
  },
};

/** El script va en una plantilla JS: sin barras invertidas (`\w` se convertiría en `w`); por eso clase explícita. El token va en el fragmento (`#t=`), que el navegador nunca envía: el script lo copia al campo oculto y limpia la URL. */
export function renderValidPage(i: ValidPageInput): string {
  if (!i.policyUrl.startsWith("https://")) throw new Error("policyUrl debe empezar por https://");
  const lang = i.locale.toLowerCase().startsWith("es") ? "es" : "en";
  const t = TEXT[lang];
  const script =
    `var m=/(?:^#|&)t=([A-Za-z0-9_-]+)/.exec(location.hash);if(m){document.getElementById("t").value=m[1]}` +
    `history.replaceState(null,"",location.pathname+location.search);`;
  return shell(
    lang,
    t.title,
    `<h1>${esc(t.title)}</h1><p>${esc(t.intro(i.displayName))}</p>` +
      `<p><a href="${esc(i.policyUrl)}">${esc(t.policy(i.policyVersion))}</a></p>` +
      `<form method="post" action=""><input type="hidden" name="r" value="${esc(i.requestId)}">` +
      `<input type="hidden" name="t" id="t" value="">` +
      `<p><label><input type="checkbox" name="declaration"> ${esc(t.declaration)}</label></p>` +
      `<button type="submit" name="action" value="accept">${esc(t.accept)}</button></form>` +
      `<noscript><p>${esc(t.noscript)}</p></noscript><script nonce="${esc(i.nonce)}">${script}</script>`,
  );
}

const BILINGUAL = (title: string, es: string, en: string) => shell("en", title, `<h1>${esc(title)}</h1><p>${esc(es)}</p><p>${esc(en)}</p>`);

/** Respuesta única para cualquier causa de rechazo: no distingue inexistente, caducada, usada, bloqueada ni token erróneo. */
export const renderInvalidPage = (): string =>
  BILINGUAL("Violin Studio", "Este enlace no es válido o ha caducado.", "This link is not valid or has expired.");

export const renderDeclarationPage = (): string =>
  BILINGUAL("Violin Studio", "Debes marcar la declaración para continuar. Vuelve atrás e inténtalo de nuevo.", "You must tick the declaration to continue. Go back and try again.");

export const renderDonePage = (): string =>
  BILINGUAL("Violin Studio", "Gracias. Hemos registrado tu consentimiento.", "Thank you. Your consent has been recorded.");
