import { onCall } from "firebase-functions/v2/https";
import { shouldEnforceAppCheck } from "./appcheck";
import { VERSION } from "./version";

export const REGION = "europe-west1";

export const health = onCall(
  { region: REGION, enforceAppCheck: shouldEnforceAppCheck(process.env) },
  () => ({ status: "ok", version: VERSION }),
);
