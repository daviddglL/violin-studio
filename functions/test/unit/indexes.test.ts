import { readFileSync } from "node:fs";
import { join } from "node:path";

const raiz = join(__dirname, "../../..");
const leer = (f: string) => JSON.parse(readFileSync(join(raiz, f), "utf8"));

describe("firestore.indexes.json", () => {
  const cfg = leer("firestore.indexes.json");

  test("índice compuesto users(consentStatus, guardian.requestedAt) para la purga", () => {
    expect(cfg.indexes).toContainEqual({
      collectionGroup: "users",
      queryScope: "COLLECTION",
      fields: [
        { fieldPath: "consentStatus", order: "ASCENDING" },
        { fieldPath: "guardian.requestedAt", order: "ASCENDING" },
      ],
    });
  });

  test.each(["mail", "guardianRequests", "guardianEmailLimits"])("TTL sobre %s.expireAt", (coleccion) => {
    expect(cfg.fieldOverrides).toContainEqual({
      collectionGroup: coleccion,
      fieldPath: "expireAt",
      ttl: true,
      indexes: [],
    });
  });
});

describe("firebase.json", () => {
  test("apunta a firestore.indexes.json", () => {
    expect(leer("firebase.json").firestore.indexes).toBe("firestore.indexes.json");
  });
});
