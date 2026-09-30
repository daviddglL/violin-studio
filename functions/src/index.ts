import { onCall } from "firebase-functions/v2/https";
import { callableOptions } from "./config/runtime";
import { VERSION } from "./version";

export { REGION } from "./config/runtime";
export { onUserDeleted } from "./erasure/on-user-deleted";

export const health = onCall(callableOptions(), () => ({ status: "ok", version: VERSION }));
