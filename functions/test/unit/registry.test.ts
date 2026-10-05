import { COLLECTIONS, CollectionName } from "../../src/common/collections";
import { ERASABLE_COLLECTIONS, ErasurePolicy, STORAGE_PREFIXES } from "../../src/erasure/registry";

test("el registro cubre todas las colecciones con la política prevista", () => {
  expect(Object.keys(ERASABLE_COLLECTIONS).sort()).toEqual(Object.values(COLLECTIONS).sort());
  expect(ERASABLE_COLLECTIONS.users).toEqual({ kind: "userDoc" });
  expect(ERASABLE_COLLECTIONS.consents).toEqual({ kind: "subcollectionOf", parent: "users" });
  expect(ERASABLE_COLLECTIONS.practiceSessions).toEqual({ kind: "subcollectionOf", parent: "users" });
  expect(ERASABLE_COLLECTIONS.guardianRequests).toEqual({ kind: "queryByField", field: "uid" });
  expect(ERASABLE_COLLECTIONS.mail).toEqual({ kind: "queryByField", field: "uid" });
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
    guardianRequests: { kind: "queryByField", field: "uid" },
    mail: { kind: "queryByField", field: "uid" },
  };
  expect(incompleto).toBeDefined();
});
