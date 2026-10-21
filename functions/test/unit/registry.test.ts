import { COLLECTIONS, CollectionName } from "../../src/common/collections";
import { ERASABLE_COLLECTIONS, erasureRoot, ErasurePolicy, STORAGE_PREFIXES } from "../../src/erasure/registry";

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
  // B5b.1: demos dirigidas al alumno borrado (con su objeto) y revisiones del profesor borrado (anonimizar).
  expect(ERASABLE_COLLECTIONS.recordings).toEqual({
    kind: "subcollectionOf",
    parent: "users",
    foreign: { field: "sharedWith", action: "deleteRecording" },
  });
  expect(ERASABLE_COLLECTIONS.reviews).toEqual({
    kind: "subcollectionOf",
    parent: "recordings",
    foreign: { field: "reviewerUid", action: "anonymize" },
  });
  // B5a (W2 de la revision fresca): marcas de idempotencia del decremento del cupo; caen con el usuario y tienen TTL.
  expect(ERASABLE_COLLECTIONS.recordingCountLedger).toEqual({ kind: "subcollectionOf", parent: "users" });
  // Fase 4c: los mensajes del chat caen al borrar al profesor o al alumno.
  expect(ERASABLE_COLLECTIONS.chatMessages).toEqual({ kind: "queryByFields", fields: ["teacherUid", "studentUid"] });
  // B5a (W1): cursores de los trabajos diarios; sin uid completo (prefijo truncado), exenta con motivo.
  const maint = ERASABLE_COLLECTIONS.maintenance;
  expect(maint.kind).toBe("exempt");
  expect(maint.kind === "exempt" && maint.reason.length).toBeGreaterThan(10);
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
    recordings: { kind: "subcollectionOf", parent: "users" },
    reviews: { kind: "subcollectionOf", parent: "recordings" },
    recordingCountLedger: { kind: "subcollectionOf", parent: "users" },
    maintenance: { kind: "exempt", reason: "x" },
    chatMessages: { kind: "queryByFields", fields: ["teacherUid", "studentUid"] },
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

describe("erasureRoot (cadena de padres hasta users)", () => {
  test("toda subcoleccion registrada llega a users, la unica raiz que borra recursiveDelete", () => {
    for (const [name, p] of Object.entries(ERASABLE_COLLECTIONS)) {
      if (p.kind === "subcollectionOf") expect({ name, root: erasureRoot(ERASABLE_COLLECTIONS, name) }).toEqual({ name, root: "users" });
    }
  });

  test("casos negativos: padre sin politica de raiz, ciclo y raiz que no es userDoc", () => {
    const sub = (parent: string): ErasurePolicy => ({ kind: "subcollectionOf", parent: parent as never });
    const raiz: ErasurePolicy = { kind: "userDoc" };
    expect(erasureRoot({ users: raiz, a: sub("b"), b: sub("users") }, "a")).toBe("users");
    expect(erasureRoot({ a: sub("b"), b: sub("a") }, "a")).toBeUndefined();
    expect(erasureRoot({ a: sub("z"), z: { kind: "queryByFields", fields: ["uid"] } }, "a")).toBeUndefined();
    expect(erasureRoot({ a: sub("ausente") }, "a")).toBeUndefined();
  });
});
