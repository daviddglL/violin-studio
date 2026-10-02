import type { Request } from "firebase-functions/v2/https";
import type { Response } from "express";
import { GuardianHttpRequest, GuardianHttpResponse } from "./confirm";
import { makeNonce, renderInvalidPage, securityHeaders } from "./page";

type Plain = Record<string, unknown>;

/** Cuerpo seguro: objeto ya parseado (urlencoded/JSON), cadena o Buffer urlencoded; cualquier otra cosa -> `{}`. */
function parseBody(body: unknown): Plain {
  if (Buffer.isBuffer(body)) body = body.toString("utf8");
  if (typeof body === "string") return Object.fromEntries(new URLSearchParams(body));
  return body && typeof body === "object" && !Array.isArray(body) ? (body as Plain) : {};
}

/**
 * Adaptador express -> handler puro: copia estado, TODAS las cabeceras y cuerpo, sin cookies. Si el handler
 * lanzara algo (no debería), responde el 404 genérico con las cabeceras de seguridad: nunca un 500 ni detalles.
 */
export const guardianHttpAdapter =
  (handler: (req: GuardianHttpRequest) => Promise<GuardianHttpResponse>) =>
  async (req: Request, res: Response): Promise<void> => {
    let out: GuardianHttpResponse;
    try {
      const query = req.query && typeof req.query === "object" ? (req.query as Plain) : {};
      out = await handler({ method: String(req.method ?? "").toUpperCase(), query, body: parseBody(req.body) });
    } catch {
      out = { status: 404, headers: securityHeaders(makeNonce()), body: renderInvalidPage() };
    }
    res.status(out.status).set(out.headers).send(out.body);
  };
