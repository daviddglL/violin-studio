import { audioMp4 } from "../unit/helpers/mp4-builder";
import { db, exists as existsIn, idOf, rnd, seedAssignment, seedUser, triggerBucket, upload as uploadTo, wipeFirestore } from "./helpers/finalize-fixtures";

/** Camino completo: el emulador de Storage dispara `finalizeRecording` (funciones cargadas desde `lib/`, `npm run build`). */
async function waitFor<T>(fn: () => Promise<T | undefined>, ms = 20000): Promise<T> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v !== undefined) return v;
    if (Date.now() > end) throw new Error("timeout esperando el doc");
    await new Promise((r) => setTimeout(r, 250));
  }
}
const docOf = (uid: string, id: string) => async () => (await db.collection("users").doc(uid).collection("recordings").doc(id).get()).data();

const upload = (uid: string, o: Parameters<typeof uploadTo>[1] = {}) => uploadTo(uid, { ...o, bucket: triggerBucket });
const exists = (name: string) => existsIn(name, triggerBucket);

beforeEach(wipeFirestore);

test("subida con Admin -> el trigger crea el doc ready y conserva el objeto", async () => {
  const uid = rnd("s");
  await seedUser(uid);
  await seedAssignment(uid, "a1");
  const ev = await upload(uid, { meta: { assignmentId: "a1" } });
  const doc = await waitFor(docOf(uid, idOf(ev.name)));
  expect(doc).toMatchObject({ status: "ready", kind: "audio", durationSec: 30, assignmentId: "a1", objectPath: ev.name });
  expect(await exists(ev.name)).toBe(true);
}, 40000);

test("subida invalida (61 s) -> el trigger deja el doc rejected y borra el objeto", async () => {
  const uid = rnd("s");
  await seedUser(uid);
  const ev = await upload(uid, { buf: audioMp4(61) });
  const doc = await waitFor(docOf(uid, idOf(ev.name)));
  expect(doc).toMatchObject({ status: "rejected", rejectReason: "duration" });
  await waitFor(async () => ((await exists(ev.name)) ? undefined : true));
}, 40000);
