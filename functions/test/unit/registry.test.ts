import { COLLECTIONS, CollectionName } from "../../src/common/collections";
import { ERASABLE_COLLECTIONS, ErasurePolicy, STORAGE_PREFIXES } from "../../src/erasure/registry";

test("el registro cubre todas las colecciones con la política prevista", () => {
  expect(Object.keys(ERASABLE_COLLECTIONS).sort()).toEqual(Object.values(COLLECTIONS).sort());
  expect(ERASABLE_COLLECTIONS.users).toEqual({ kind: "userDoc" });
  expect(ERASABLE_COLLECTIONS.consents).toEqual({ kind: "subcollectionOf", parent: "users" });
  expect(ERASABLE_COLLECTIONS.practiceSessions).toEqual({ kind: "subcollectionOf", parent: "users" });
  expect(ERASABLE_COLLECTIONS.guardianRequests).toEqual({ kind: "queryByFields", fields: ["uid"] });
  expect(ERASABLE_COLLECTIONS.mail).toEqual({ kind: "queryByFields", fields: ["uid"] });
  // Politica de dos campos (REQ-DEL-T03): un uid puede ser profesor (teacherUid) o alumno/canjeador (usedBy).
  expect(ERASABLE_COLLECTIONS.teacherCodes).toEqual({ kind: "queryByFields", fields: ["teacherUid", "usedBy"] });
  expect(ERASABLE_COLLECTIONS.teacherLinks).toEqual({ kind: "link", fields: ["teacherUid", "studentUid"] });
  expect(ERASABLE_COLLECTIONS.assignments).toEqual({
    kind: "subcollectionOf",
    parent: "users",
    foreign: { field: "teacherUid", action: "delete" },
  });
  const exempt = ERASABLE_COLLECTIONS.guardianEmailLimits;
  expect(exempt.kind).toBe("exempt");
  expect(exempt.kind === "exempt" && exempt.reason.length).toBeGreaterThan(10);
});

test("los prefijos de Storage dependen del uid", () => {
  expect(STORAGE_PREFIXES.map((p) => p("u1"))).toEqual(["users/u1/"]);
});

test("una colección sin política no compila", () => {
  // @ts-expect-error falta guardianEmailLimits: Record<CollectionName, ErasurePolicy> es exhaustivo
  const incompleto: Record<CollectionName, ErasurePolicy> = {
    users: { kind: "userDoc" },
    consents: { kind: "subcollectionOf", parent: "users" },
    practiceSessions: { kind: "subcollectionOf", parent: "users" },
    guardianRequests: { kind: "queryByFields", fields: ["uid"] },
    mail: { kind: "queryByFields", fields: ["uid"] },
    teacherCodes: { kind: "exempt", reason: "x" },
    teacherLinks: { kind: "exempt", reason: "x" },
    assignments: { kind: "subcollectionOf", parent: "users" },
  };
  expect(incompleto).toBeDefined();
});

/** Cada variante de `ErasurePolicy` debe tratarse: añadir una sin manejarla aqui no compila. */
function handled(p: ErasurePolicy): string {
  switch (p.kind) {
    case "userDoc":
    case "subcollectionOf":
    case "queryByFields":
    case "link":
    case "exempt":
      return p.kind;
    default: {
      const nunca: never = p;
      return nunca;
    }
  }
}

test("toda variante de politica esta cubierta y queryByField ya no existe", () => {
  const kinds = new Set(Object.values(ERASABLE_COLLECTIONS).map(handled));
  expect([...kinds].sort()).toEqual(["exempt", "link", "queryByFields", "subcollectionOf", "userDoc"]);
  // @ts-expect-error la variante de un solo campo se sustituyo por queryByFields
  const viejo: ErasurePolicy = { kind: "queryByField", field: "uid" };
  expect(viejo).toBeDefined();
});
