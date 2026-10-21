import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { syncLinkNamesHandler } from "../../src/teacher/sync-names";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const db = getFirestore(app);

const uid = () => `u${Date.now()}${Math.random().toString(36).slice(2, 10)}`;
async function vincular(t: string, s: string) {
  await db.collection("teacherLinks").doc(`${t}_${s}`).set({
    teacherUid: t,
    studentUid: s,
    teacherDisplayName: "Profe viejo",
    studentDisplayName: "Alum viejo",
    studentInstrument: "viola",
    ackVersion: 1,
    createdAt: Timestamp.now(),
  });
}
const usuario = (u: string, d: Record<string, unknown>) => db.collection("users").doc(u).set(d);
const link = async (t: string, s: string) => (await db.collection("teacherLinks").doc(`${t}_${s}`).get()).data();

describe("syncLinkNames (A3b.3)", () => {
  test("el alumno cambia displayName e instrument: se actualiza su vinculo", async () => {
    const t = uid();
    const s = uid();
    await vincular(t, s);
    await usuario(s, { displayName: "Alum nuevo", instrument: "violin" });
    await syncLinkNamesHandler(
      { db },
      s,
      { displayName: "Alum viejo", instrument: "viola" },
      { displayName: "Alum nuevo", instrument: "violin" },
    );
    expect(await link(t, s)).toMatchObject({
      studentDisplayName: "Alum nuevo",
      studentInstrument: "violin",
      teacherDisplayName: "Profe viejo",
    });
  });

  test("el profesor cambia displayName: se actualiza en todos sus vinculos", async () => {
    const t = uid();
    const [s1, s2] = [uid(), uid()];
    await vincular(t, s1);
    await vincular(t, s2);
    await usuario(t, { displayName: "Profe nuevo" });
    await syncLinkNamesHandler({ db }, t, { displayName: "Profe viejo" }, { displayName: "Profe nuevo" });
    for (const s of [s1, s2]) {
      expect(await link(t, s)).toMatchObject({ teacherDisplayName: "Profe nuevo", studentDisplayName: "Alum viejo" });
    }
  });

  test("instrument eliminado -> null", async () => {
    const t = uid();
    const s = uid();
    await vincular(t, s);
    await usuario(s, { displayName: "Alum viejo" });
    await syncLinkNamesHandler({ db }, s, { displayName: "Alum viejo", instrument: "viola" }, { displayName: "Alum viejo" });
    expect((await link(t, s))?.studentInstrument).toBeNull();
  });

  test("otro campo (locale) no consulta ni escribe nada", async () => {
    const t = uid();
    const s = uid();
    await vincular(t, s);
    const espia = jest.spyOn(db, "collection");
    try {
      await syncLinkNamesHandler({ db }, s, { displayName: "X", locale: "es" }, { displayName: "X", locale: "en" });
      expect(espia).not.toHaveBeenCalled();
    } finally {
      espia.mockRestore();
    }
    expect(await link(t, s)).toMatchObject({ studentDisplayName: "Alum viejo" });
  });

  test("idempotente: repetir el evento deja el mismo estado; doc borrado no hace nada", async () => {
    const t = uid();
    const s = uid();
    await vincular(t, s);
    const [antes, despues] = [{ displayName: "Alum viejo" }, { displayName: "Alum nuevo" }];
    await usuario(s, despues);
    await syncLinkNamesHandler({ db }, s, antes, despues);
    const una = await link(t, s);
    await syncLinkNamesHandler({ db }, s, antes, despues);
    expect(await link(t, s)).toEqual(una);
    await expect(syncLinkNamesHandler({ db }, s, despues, undefined)).resolves.toBeUndefined();
    expect(await link(t, s)).toEqual(una);
  });

  test("evento atrasado: escribe el valor ACTUAL del perfil, no el del snapshot del evento", async () => {
    const t = uid();
    const s = uid();
    await vincular(t, s);
    await usuario(s, { displayName: "Alum actual", instrument: "cello" });
    await syncLinkNamesHandler({ db }, s, { displayName: "Alum viejo" }, { displayName: "Alum intermedio" });
    expect(await link(t, s)).toMatchObject({ studentDisplayName: "Alum actual", studentInstrument: "cello" });
  });

  test("perfil borrado entre el evento y la lectura: no escribe", async () => {
    const t = uid();
    const s = uid();
    await vincular(t, s);
    await syncLinkNamesHandler({ db }, s, { displayName: "Alum viejo" }, { displayName: "X" });
    expect(await link(t, s)).toMatchObject({ studentDisplayName: "Alum viejo" });
  });

  test("sin vinculos no falla", async () => {
    const u = uid();
    await usuario(u, { displayName: "b" });
    await expect(
      syncLinkNamesHandler({ db }, u, { displayName: "a" }, { displayName: "b" }),
    ).resolves.toBeUndefined();
  });
});
