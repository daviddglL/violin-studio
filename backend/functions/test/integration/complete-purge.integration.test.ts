import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { completeRecordingPurge } from "../../src/recordings/complete-purge";
import { db, recRef, rnd, seedUser, userDoc, wipeFirestore } from "./helpers/finalize-fixtures";

const SINCE = Timestamp.fromMillis(1_700_000_000_000);
let logs: { message: string; data: Record<string, unknown> }[];
const log = (message: string, data: Record<string, unknown>) => void logs.push({ message, data });
const pending = (uid: string, epoch: unknown = 0, over: Record<string, unknown> = {}) =>
  seedUser(uid, { recordingConsent: "revoked", recordingConsentEpoch: 3, recordingPurge: { pending: true, since: SINCE, epoch }, ...over });
const doc = (uid: string, id: string, data: Record<string, unknown>) => recRef(uid, id).set({ kind: "audio", ...data });

beforeEach(async () => {
  logs = [];
  await wipeFirestore();
});

describe("completeRecordingPurge (W4 de la revision fresca B5b, cierre manual tras limpieza)", () => {
  test("sin docs ready <= epoch: baja pending, sella completedAt y la marca de cierre manual; conserva since/epoch", async () => {
    const u = rnd("u");
    await pending(u, 1);
    await doc(u, "r1", { status: "rejected", recordingConsentEpoch: 0 }); // no ready: no bloquea
    await doc(u, "r2", { status: "ready", recordingConsentEpoch: 2 }); // epoca posterior: no bloquea
    expect(await completeRecordingPurge(db, u, { log })).toEqual({ outcome: "completed", remaining: 0 });
    const p = (await userDoc(u))?.recordingPurge;
    expect(p).toMatchObject({ pending: false, epoch: 1, completedBy: "admin", forced: false });
    expect((p.since as Timestamp).isEqual(SINCE)).toBe(true);
    expect(p.completedAt).toBeInstanceOf(Timestamp);
    expect(JSON.stringify(logs)).not.toContain(u);
  });

  test("quedan docs ready <= epoch (o sin epoca): rechaza RECORDINGS_REMAIN con el recuento y no escribe", async () => {
    const u = rnd("u");
    await pending(u, 1);
    await doc(u, "a", { status: "ready", recordingConsentEpoch: 1 });
    await doc(u, "b", { status: "ready" }); // sin epoca = anterior a todo
    await recRef(u, "b").update({ recordingConsentEpoch: FieldValue.delete() });
    await expect(completeRecordingPurge(db, u, { log })).rejects.toMatchObject({ reason: "RECORDINGS_REMAIN", remaining: 2 });
    expect((await userDoc(u))?.recordingPurge.pending).toBe(true);
  });

  test("con force cierra igual y lo deja marcado (forced) con el recuento", async () => {
    const u = rnd("u");
    await pending(u, 0);
    await doc(u, "a", { status: "ready", recordingConsentEpoch: 0 });
    expect(await completeRecordingPurge(db, u, { force: true, log })).toEqual({ outcome: "completed", remaining: 1 });
    expect((await userDoc(u))?.recordingPurge).toMatchObject({ pending: false, forced: true, completedBy: "admin" });
    expect(logs.some((l) => l.data.forced === true && l.data.remaining === 1)).toBe(true);
  });

  test("epoca de purga corrupta = infinita: cualquier ready bloquea", async () => {
    const u = rnd("u");
    await pending(u, "x");
    await doc(u, "a", { status: "ready", recordingConsentEpoch: 99 });
    await expect(completeRecordingPurge(db, u)).rejects.toMatchObject({ reason: "RECORDINGS_REMAIN" });
  });

  test("sin purga pendiente: not_pending sin escribir", async () => {
    const u = rnd("u");
    await seedUser(u, { recordingPurge: { pending: false, since: SINCE, epoch: 0 } });
    const before = (await db.collection("users").doc(u).get()).updateTime!;
    expect(await completeRecordingPurge(db, u)).toEqual({ outcome: "not_pending", remaining: 0 });
    expect((await db.collection("users").doc(u).get()).updateTime!.isEqual(before)).toBe(true);
  });

  test("usuario inexistente o en borrado: rechaza sin recrear ni escribir", async () => {
    const ghost = rnd("g");
    await expect(completeRecordingPurge(db, ghost)).rejects.toMatchObject({ reason: "USER_NOT_FOUND" });
    expect(await userDoc(ghost)).toBeUndefined();
    const del = rnd("d");
    await pending(del, 0, { deletion: { state: "in_progress" } });
    await expect(completeRecordingPurge(db, del)).rejects.toMatchObject({ reason: "DELETING" });
    expect((await userDoc(del))?.recordingPurge.pending).toBe(true);
  });
});
