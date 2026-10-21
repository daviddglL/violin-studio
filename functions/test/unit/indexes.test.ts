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

  test("indice compuesto teacherCodes(teacherUid, expiresAt) para listar y contar codigos activos (A2)", () => {
    expect(cfg.indexes).toContainEqual({
      collectionGroup: "teacherCodes",
      queryScope: "COLLECTION",
      fields: [
        { fieldPath: "teacherUid", order: "ASCENDING" },
        { fieldPath: "expiresAt", order: "ASCENDING" },
      ],
    });
  });

  test("indice compuesto assignments(teacherUid, createdAt DESC) para la lista del profesor (A6)", () => {
    expect(cfg.indexes).toContainEqual({
      collectionGroup: "assignments",
      queryScope: "COLLECTION",
      fields: [
        { fieldPath: "teacherUid", order: "ASCENDING" },
        { fieldPath: "createdAt", order: "DESCENDING" },
      ],
    });
  });

  test("assignments: la lista del alumno (createdAt) y la limpieza (teacherUid == t && status == open) usan indices de campo unico", () => {
    // Sin compuesto: orderBy simple y dos igualdades se resuelven con los indices automaticos (fusion de indices).
    // El override de teacherUid solo declara ASC (COLLECTION y COLLECTION_GROUP): la limpieza y la cascada solo usan ==.
    const overridden = cfg.fieldOverrides.filter((f: { collectionGroup: string }) => f.collectionGroup === "assignments").map((f: { fieldPath: string }) => f.fieldPath);
    expect(overridden).toEqual(["teacherUid"]);
  });

  test("assignments.teacherUid: indice COLLECTION_GROUP para la cascada de borrado del profesor (A5)", () => {
    const o = cfg.fieldOverrides.find((f: { collectionGroup: string; fieldPath: string }) => f.collectionGroup === "assignments" && f.fieldPath === "teacherUid");
    expect(o.indexes).toContainEqual({ order: "ASCENDING", queryScope: "COLLECTION_GROUP" });
  });

  test("indices compuestos de recordings: (status, createdAt DESC) del profesor y (sharedWith, createdAt DESC) de las demos (B3b)", () => {
    // El alumno consulta `sharedWith == uid && status == ready` ordenado: se resuelve por fusion de ambos compuestos.
    for (const campo of ["status", "sharedWith"]) {
      expect(cfg.indexes).toContainEqual({
        collectionGroup: "recordings",
        queryScope: "COLLECTION",
        fields: [
          { fieldPath: campo, order: "ASCENDING" },
          { fieldPath: "createdAt", order: "DESCENDING" },
        ],
      });
    }
  });

  test.each([
    ["recordings", "expiresAt"],
    ["recordings", "sharedWith"],
    ["recordings", "status"],
    ["reviews", "reviewerUid"],
  ])("%s.%s: indice COLLECTION_GROUP ascendente (purga diaria y cascadas de borrado, B3b)", (coleccion, campo) => {
    const o = cfg.fieldOverrides.find((f: { collectionGroup: string; fieldPath: string }) => f.collectionGroup === coleccion && f.fieldPath === campo);
    expect(o).toBeDefined();
    expect(o.indexes).toContainEqual({ order: "ASCENDING", queryScope: "COLLECTION_GROUP" });
    expect(o.indexes).toContainEqual({ order: "ASCENDING", queryScope: "COLLECTION" });
  });

  test("recordings y reviews no declaran TTL (la caducidad la barre purgeRecordings, A21)", () => {
    for (const f of cfg.fieldOverrides) if (["recordings", "reviews"].includes(f.collectionGroup)) expect(f.ttl).toBeUndefined();
  });

  test.each(["mail", "teacherCodes", "guardianRequests", "guardianEmailLimits"])("%s.expireAt: TTL y indice ascendente habilitado (la purga 7b.4 filtra por rango)", (coleccion) => {
    const o = cfg.fieldOverrides.find((f: { collectionGroup: string; fieldPath: string }) => f.collectionGroup === coleccion && f.fieldPath === "expireAt");
    expect(o.ttl).toBe(true);
    expect(o.indexes).toContainEqual({ order: "ASCENDING", queryScope: "COLLECTION" });
  });

  test("recordingCountLedger.expireAt: TTL (marcas de idempotencia del decremento del cupo, B5a W2)", () => {
    const o = cfg.fieldOverrides.find((f: { collectionGroup: string; fieldPath: string }) => f.collectionGroup === "recordingCountLedger" && f.fieldPath === "expireAt");
    expect(o).toBeDefined();
    expect(o.ttl).toBe(true);
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
