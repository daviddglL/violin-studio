import { reconcileAllOwners, reconcileOwner } from "../../src/recordings/recount";
import { db, NOW, recRef, rnd, seedUser, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

/** Doc sembrado directo (sin objeto): el contador solo mira docs `ready`. */
const seed = (uid: string, status: "ready" | "rejected" = "ready", id = rnd("r")) =>
  recRef(uid, id).set({ status, objectPath: `users/${uid}/recordings/${id}.m4a`, generation: "1", expiresAt: NOW });
const count = async (uid: string) => (await userDoc(uid))?.recordingCount;
const updateTime = async (uid: string) => (await db.collection("users").doc(uid).get()).updateTime!.toMillis();

beforeEach(wipeFirestore);

describe("reconcileOwner", () => {
  test("deriva al alza: repara con el recuento real de docs ready (los rejected no cuentan)", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 7 });
    await seed(uid);
    await seed(uid);
    await seed(uid, "rejected");
    expect(await reconcileOwner(db, uid)).toBe("repaired");
    expect(await count(uid)).toBe(2);
  });

  test("deriva a la baja y contador ausente", async () => {
    const a = rnd("s");
    await seedUser(a, { recordingCount: 0 });
    await seed(a);
    const b = rnd("s");
    await seedUser(b);
    await seed(b);
    await seed(b);
    expect(await reconcileOwner(db, a)).toBe("repaired");
    expect(await reconcileOwner(db, b)).toBe("repaired");
    expect([await count(a), await count(b)]).toEqual([1, 2]);
  });

  test("contador corrupto (negativo, cadena, no entero) se repara; sin docs queda en 0", async () => {
    for (const bad of [-3, "4", 2.5, null]) {
      const uid = rnd("s");
      await seedUser(uid, { recordingCount: bad });
      expect(await reconcileOwner(db, uid)).toBe("repaired");
      expect(await count(uid)).toBe(0);
    }
  });

  test("coherente (o ausente sin docs): no escribe", async () => {
    const a = rnd("s");
    await seedUser(a, { recordingCount: 1 });
    await seed(a);
    const b = rnd("s");
    await seedUser(b);
    const [ta, tb] = [await updateTime(a), await updateTime(b)];
    expect(await reconcileOwner(db, a)).toBe("ok");
    expect(await reconcileOwner(db, b)).toBe("ok");
    expect([await updateTime(a), await updateTime(b)]).toEqual([ta, tb]);
    expect(await userDoc(b)).not.toHaveProperty("recordingCount");
  });

  test("sin doc de usuario o con la cuenta en borrado: no escribe ni recrea nada", async () => {
    const ghost = rnd("g");
    await seed(ghost);
    expect(await reconcileOwner(db, ghost)).toBe("skipped");
    expect((await db.collection("users").doc(ghost).get()).exists).toBe(false);
    const del = rnd("d");
    await seedUser(del, { recordingCount: 9, deletion: { state: "in_progress" } });
    await seed(del);
    expect(await reconcileOwner(db, del)).toBe("skipped");
    expect(await count(del)).toBe(9);
  });

  test("el profesor tiene su propio contador (demos) independiente del alumno", async () => {
    const t = rnd("t");
    const s = rnd("s");
    await seedUser(t, { role: "teacher", recordingCount: 0 });
    await seedUser(s, { recordingCount: 0 });
    await seed(t);
    await seed(t);
    await seed(s);
    await reconcileOwner(db, t);
    await reconcileOwner(db, s);
    expect([await count(t), await count(s)]).toEqual([2, 1]);
  });

  test("dos reconciliaciones simultaneas del mismo dueno convergen en el recuento real", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 5 });
    await seed(uid);
    await Promise.all([reconcileOwner(db, uid), reconcileOwner(db, uid)]);
    expect(await count(uid)).toBe(1);
  });
});

describe("reconcileAllOwners", () => {
  test("recorre usuarios con contador (cualquier tipo) y duenos de docs ready sin contador; no toca a los coherentes", async () => {
    const drift = rnd("a");
    const missing = rnd("b");
    const zero = rnd("c");
    const fine = rnd("d");
    const none = rnd("e");
    await seedUser(drift, { recordingCount: 9 });
    await seed(drift);
    await seedUser(missing);
    await seed(missing);
    await seedUser(zero, { recordingCount: 3 });
    await seedUser(fine, { recordingCount: 1 });
    await seed(fine);
    await seedUser(none);
    const res = await reconcileAllOwners(db, { pageSize: 2 });
    expect(res.failed).toBe(0);
    expect([await count(drift), await count(missing), await count(zero), await count(fine)]).toEqual([1, 1, 0, 1]);
    expect(res.repaired).toBe(3);
    expect(await userDoc(none)).not.toHaveProperty("recordingCount");
  });

  test("paginado: mas duenos que el tamano de pagina y varios docs ready por dueno", async () => {
    const uids = [rnd("p1"), rnd("p2"), rnd("p3"), rnd("p4"), rnd("p5")];
    for (const [i, u] of uids.entries()) {
      await seedUser(u, { recordingCount: 99 });
      for (let k = 0; k <= i; k++) await seed(u);
    }
    const res = await reconcileAllOwners(db, { pageSize: 2 });
    expect(res.repaired).toBe(5);
    expect(await Promise.all(uids.map(count))).toEqual([1, 2, 3, 4, 5]);
  });

  test("idempotente: la segunda pasada no repara nada", async () => {
    const uid = rnd("s");
    await seedUser(uid, { recordingCount: 4 });
    await seed(uid);
    await reconcileAllOwners(db);
    const res = await reconcileAllOwners(db);
    expect(res).toMatchObject({ repaired: 0, failed: 0 });
  });

  test("shouldStop corta la pasada (presupuesto de tiempo)", async () => {
    for (let i = 0; i < 3; i++) {
      const u = rnd("s");
      await seedUser(u, { recordingCount: 9 });
    }
    let calls = 0;
    const res = await reconcileAllOwners(db, { shouldStop: () => ++calls > 1 });
    expect(res.stopped).toBe(true);
    expect(res.repaired).toBeLessThan(3);
  });

  test("los duenos en `skip` no se tocan (la purga acaba de borrarles docs y su trigger aun puede bajar el contador)", async () => {
    const a = rnd("s");
    const b = rnd("s");
    await seedUser(a, { recordingCount: 9 });
    await seedUser(b, { recordingCount: 9 });
    await reconcileAllOwners(db, { skip: new Set([a]) });
    expect([await count(a), await count(b)]).toEqual([9, 0]);
  });

  test("un fallo en un dueno no detiene el resto y se cuenta (sin PII en el callback)", async () => {
    const a = rnd("s");
    const b = rnd("s");
    await seedUser(a, { recordingCount: 9 });
    await seedUser(b, { recordingCount: 9 });
    const failures: string[] = [];
    const res = await reconcileAllOwners(db, {
      reconcile: async (d, uid) => {
        if (uid === a) throw Object.assign(new Error(`secreto ${uid}`), { code: 14 });
        return reconcileOwner(d, uid);
      },
      onFailure: (_uid, code) => void failures.push(String(code)),
    });
    expect(res.failed).toBe(1);
    expect(failures).toEqual(["14"]);
    expect(await count(b)).toBe(0);
    expect(await count(a)).toBe(9);
  });
});
