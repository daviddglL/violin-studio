import { CallableRequest, HttpsError } from "firebase-functions/v2/https";
import { DecodedIdToken } from "firebase-admin/auth";
import { REAUTH_MAX_AGE_SECONDS } from "../config/identity";
import { ErrorReason, fail } from "./errors";

export interface VerifiedUser {
  uid: string;
  token: DecodedIdToken;
}

/** Exige sesión y email verificado (también para Google, que llega verificado). */
export function requireVerifiedUser(req: Pick<CallableRequest, "auth">): VerifiedUser {
  if (!req.auth) throw new HttpsError("unauthenticated", "Se requiere sesión");
  const token = req.auth.token;
  if (token.email_verified !== true) {
    throw fail("failed-precondition", ErrorReason.EMAIL_NOT_VERIFIED);
  }
  return { uid: req.auth.uid, token };
}

/** Reautenticación reciente: rechaza si han pasado 300 s o más (o si falta `auth_time`). */
export function requireRecentAuth(
  token: { auth_time?: unknown },
  now: Date,
  maxAgeSeconds: number = REAUTH_MAX_AGE_SECONDS,
): void {
  const authTime = token.auth_time;
  if (typeof authTime !== "number" || !Number.isFinite(authTime)) {
    throw fail("failed-precondition", ErrorReason.REAUTH_REQUIRED);
  }
  if (now.getTime() / 1000 - authTime >= maxAgeSeconds) {
    throw fail("failed-precondition", ErrorReason.REAUTH_REQUIRED);
  }
}
