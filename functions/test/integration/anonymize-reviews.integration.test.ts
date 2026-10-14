import { Firestore, Timestamp } from "firebase-admin/firestore";
import { anonymizeReviews } from "../../src/recordings/anonymize-reviews";
import { db, recRef, rnd, wipeFirestore } from "./helpers/finalize-fixtures";

const review = (student: string, rec: string, reviewer: string, over: Record<string, unknown> = {}) =>
  recRef(student, rec)
    .collection("reviews")
    .doc(reviewer)
    .set({ reviewerUid: reviewer, verdict: "needs_work", text: `texto de ${rec}`, createdAt: Timestamp.fromMillis(1000), updatedAt: Timestamp.fromMillis(2000), ...over });
const reviewsOf = async (student: string, rec: string) => (await recRef(student, rec).collection("reviews").get()).docs;
const allReviews = async () => (await db.collectionGroup("reviews").get()).docs;

beforeEach(wipeFirestore);

describe("anonymizeReviews (REQ-RVW-04)", () => {
  test("re-key a id aleatorio con reviewerUid null y texto/veredicto/fechas intactos; sin rastro del uid en ruta ni campos", async () => {
    const t = rnd("t");
    const [s1, s2] = [rnd("s"), rnd("s")];
    await review(s1, "r1", t);
    await review(s1, "r2", t);
    await review(s2, "r3", t, { verdict: "approved" });
    const n = await anonymizeReviews(db, t);
    expect(n).toBe(3);
    const docs = await allReviews();
    expect(docs).toHaveLength(3);
    for (const d of docs) {
      expect(d.id).not.toContain(t);
      expect(d.ref.path).not.toContain(t);
      expect(JSON.stringify(d.data())).not.toContain(t);
      expect(d.data().reviewerUid).toBeNull();
      expect(d.data().createdAt).toEqual(Timestamp.fromMillis(1000));
      expect(d.data().updatedAt).toEqual(Timestamp.fromMillis(2000));
    }
    expect((await reviewsOf(s1, "r1"))[0].data().text).toBe("texto de r1");
    expect((await reviewsOf(s2, "r3"))[0].data().verdict).toBe("approved");
    expect(new Set(docs.map((d) => d.id)).size).toBe(3);
  });

  test("termina (las re-keyed ya no casan con la consulta) y repetirla es un no-op", async () => {
    const t = rnd("t");
    await review(rnd("s"), "r1", t);
    expect(await anonymizeReviews(db, t)).toBe(1);
    expect(await anonymizeReviews(db, t)).toBe(0);
    expect(await allReviews()).toHaveLength(1);
  });

  test("no toca las revisiones de otro profesor ni las del mismo espacio", async () => {
    const [t, other, s] = [rnd("t"), rnd("t"), rnd("s")];
    await review(s, "r1", t);
    await review(s, "r1", other);
    await anonymizeReviews(db, t);
    const docs = await reviewsOf(s, "r1");
    expect(docs).toHaveLength(2);
    expect(docs.find((d) => d.id === other)?.data().reviewerUid).toBe(other);
  });

  test("revision sin doc de grabacion padre tambien se anonimiza", async () => {
    const t = rnd("t");
    await review(rnd("s"), "fantasma", t);
    expect(await anonymizeReviews(db, t)).toBe(1);
    expect((await allReviews())[0].data().reviewerUid).toBeNull();
  });

  test("reintento tras un fallo transitorio a mitad: termina sin duplicar ni perder revisiones", async () => {
    const t = rnd("t");
    const s = rnd("s");
    for (const r of ["r1", "r2", "r3", "r4"]) await review(s, r, t);
    let calls = 0;
    const flaky = new Proxy(db, {
      get(target, prop) {
        if (prop === "runTransaction") {
          return (...a: unknown[]) => {
            if (++calls === 3) throw Object.assign(new Error("transitorio"), { code: 14 });
            return (target.runTransaction as (...x: unknown[]) => unknown).apply(target, a);
          };
        }
        const v = Reflect.get(target, prop, target);
        return typeof v === "function" ? v.bind(target) : v;
      },
    }) as Firestore;
    await expect(anonymizeReviews(flaky, t)).rejects.toMatchObject({ code: 14 });
    expect((await allReviews()).filter((d) => d.data().reviewerUid === null).length).toBeGreaterThan(0);
    await anonymizeReviews(db, t);
    const docs = await allReviews();
    expect(docs).toHaveLength(4);
    expect(docs.every((d) => d.data().reviewerUid === null)).toBe(true);
  });

  test("dos ejecuciones concurrentes no duplican", async () => {
    const t = rnd("t");
    const s = rnd("s");
    for (const r of ["r1", "r2", "r3"]) await review(s, r, t);
    await Promise.all([anonymizeReviews(db, t), anonymizeReviews(db, t)]);
    expect(await allReviews()).toHaveLength(3);
  });

  test("el contador devuelto acumula entre lotes y onProgress recibe cada lote (sin uids)", async () => {
    const t = rnd("t");
    for (let i = 0; i < 5; i++) await review(rnd("s"), `r${i}`, t);
    const seen: number[] = [];
    expect(await anonymizeReviews(db, t, { batchSize: 2, onProgress: (n: number) => seen.push(n) })).toBe(5);
    expect(seen.reduce((a, b) => a + b, 0)).toBe(5);
    expect(Math.max(...seen)).toBeLessThanOrEqual(2);
  });
});
