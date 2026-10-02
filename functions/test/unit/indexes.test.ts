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

  test("indice compuesto users(deletion.state, deletion.startedAt) para reanudar borrados atascados (7b.3)", () => {
    expect(cfg.indexes).toContainEqual({
      collectionGroup: "users",
      queryScope: "COLLECTION",
      fields: [
        { fieldPath: "deletion.state", order: "ASCENDING" },
        { fieldPath: "deletion.startedAt", order: "ASCENDING" },
      ],
    });
  });

  test.each(["mail", "guardianRequests", "guardianEmailLimits"])("%s.expireAt: TTL y indice ascendente habilitado (la purga 7b.4 filtra por rango)", (coleccion) => {
    const o = cfg.fieldOverrides.find((f: { collectionGroup: string; fieldPath: string }) => f.collectionGroup === coleccion && f.fieldPath === "expireAt");
    expect(o.ttl).toBe(true);
    expect(o.indexes).toContainEqual({ order: "ASCENDING", queryScope: "COLLECTION" });
  });

  test("ningun fieldOverride con indexes vacios deshabilita un campo que purge.ts consulta con rango", () => {
    const src = readFileSync(join(__dirname, "../../src/maintenance/purge.ts"), "utf8");
    const ranged = [...src.matchAll(/where\("([\w.]+)",\s*"(?:<|<=|>|>=)"/g)].map((m) => m[1]);
    expect(ranged).toContain("expireAt");
    for (const f of cfg.fieldOverrides) if (f.indexes.length === 0) expect(ranged).not.toContain(f.fieldPath);
  });
});

describe("firebase.json", () => {
  test("apunta a firestore.indexes.json", () => {
    expect(leer("firebase.json").firestore.indexes).toBe("firestore.indexes.json");
  });
});
