import { reevaluateAllConsents } from "../../src/consent/reevaluate-all";

const fakeDb = (docs: Array<{ id: string; data: Record<string, unknown> }>) => {
  const snapDocs = docs.map((d) => ({ id: d.id, data: () => d.data }));
  const q: Record<string, unknown> = {};
  q.where = () => q;
  q.orderBy = () => q;
  q.startAfter = () => q;
  q.limit = () => q;
  q.get = async () => ({ empty: snapDocs.length === 0, size: snapDocs.length, docs: snapDocs });
  return { collection: () => q } as never;
};
const base = { auth: {} as never, currentVersion: 2, log: () => undefined };
const old = { consentStatus: "granted", policyVersion: 1 };

test("un fallo transitorio (p. ej. syncClaims tras el commit) se reintenta en la misma pasada", async () => {
  const reevaluate = jest.fn().mockRejectedValueOnce(new Error("auth down")).mockResolvedValue(undefined);
  const r = await reevaluateAllConsents({ ...base, db: fakeDb([{ id: "u1", data: old }]), reevaluate });
  expect(reevaluate).toHaveBeenCalledTimes(2);
  expect(r).toEqual({ scanned: 1, changed: 1, failed: 0 });
});

test("si el reintento tambien falla se cuenta un fallo y el lote continua", async () => {
  const reevaluate = jest.fn().mockRejectedValueOnce(new Error("x")).mockRejectedValueOnce(new Error("x")).mockResolvedValue(undefined);
  const r = await reevaluateAllConsents({
    ...base,
    db: fakeDb([{ id: "u1", data: old }, { id: "u2", data: old }]),
    reevaluate,
  });
  expect(r).toEqual({ scanned: 2, changed: 1, failed: 1 });
});

test("ignora documentos con deletion en curso (no cuentan como changed)", async () => {
  const reevaluate = jest.fn().mockResolvedValue(undefined);
  const r = await reevaluateAllConsents({ ...base, db: fakeDb([{ id: "u1", data: { ...old, deletion: { at: 1 } } }]), reevaluate });
  expect(reevaluate).not.toHaveBeenCalled();
  expect(r).toEqual({ scanned: 1, changed: 0, failed: 0 });
});
