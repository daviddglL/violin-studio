import { initializeTestEnvironment, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, setDoc } from "firebase/firestore";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = join(__dirname, "../..");

export async function createEnv(): Promise<RulesTestEnvironment> {
  // Host y puerto salen de FIRESTORE_EMULATOR_HOST / FIREBASE_STORAGE_EMULATOR_HOST (los pone emulators:exec).
  return initializeTestEnvironment({
    projectId: "demo-violin-studio",
    firestore: { rules: readFileSync(join(root, "firestore.rules"), "utf8") },
    storage: { rules: readFileSync(join(root, "storage.rules"), "utf8") },
  });
}

export type Claims = { email_verified?: boolean; consentOk?: boolean };
export const OK: Claims = { email_verified: true, consentOk: true };

/** Perfil tal y como lo deja registerProfile + el flujo de consentimiento. */
export function userDoc(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    displayName: "Alice",
    instrument: "violin",
    locale: "es-ES",
    role: "independent",
    birthDate: "1990-05-10",
    isMinor: false,
    consentStatus: "granted",
    policyVersion: 1,
    createdAt: new Date("2026-01-01T00:00:00Z"),
    updatedAt: new Date("2026-01-01T00:00:00Z"),
    ...overrides,
  };
}

export async function seed(env: RulesTestEnvironment, path: string, data: Record<string, unknown>): Promise<void> {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), path), data);
  });
}
