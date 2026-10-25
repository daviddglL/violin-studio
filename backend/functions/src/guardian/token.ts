import { randomToken, safeEqualHex, sha256Hex } from "../common/hashing";

/** Token de un solo uso (32 bytes, base64url). Solo viaja en el enlace del correo; en BD se guarda su hash. */
export const generateToken = randomToken;

export const hashToken = (token: string): string => sha256Hex(token);

/** Comparación en tiempo constante contra el hash guardado; longitud o formato distintos -> false. */
export const verifyToken = (token: string, storedHash: string): boolean => safeEqualHex(hashToken(token), storedHash);
