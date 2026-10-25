import { logger } from "firebase-functions/v2";
import { sha256Hex } from "../../src/common/hashing";
import { bucketStore, finalizeRecordingHandler } from "../../src/recordings/finalize";
import { audioMp4 } from "../unit/helpers/mp4-builder";
import {
  bucket, db, idOf, NOW, rnd, seedAssignment, seedLink, seedTeacher, seedUser, upload, wipeFirestore,
} from "./helpers/finalize-fixtures";

const flags = { recordingsEnabled: true, videoEnabled: false, minorRecordingEnabled: false };
type Log = { message: string; data: Record<string, unknown> };

beforeEach(wipeFirestore);

describe("B4b.4 logs de finalize: solo uidHash, sin PII ni rutas", () => {
  test("cada resultado registra una linea con uidHash y ninguna cadena sensible", async () => {
    const logs: Log[] = [];
    const run = (ev: { name: string; generation: string }, over: Record<string, unknown> = {}) =>
      finalizeRecordingHandler(
        {
          db, store: bucketStore(bucket), clock: () => NOW, flags, requiredConsentVersion: 1,
          log: (message, data) => void logs.push({ message, data }), ...over,
        },
        ev,
      );

    const s = rnd("alumno");
    const t = rnd("profe");
    await seedUser(s, { email: "alumna.secreta@example.com", displayName: "Alumna Secreta" });
    await seedTeacher(t);
    await seedLink(t, s);
    await seedAssignment(s, "tarea-secreta", { teacherUid: t });

    const sensitive: string[] = [s, t, "alumna.secreta@example.com", "Alumna Secreta", "tarea-secreta"];
    const ready = await upload(s);
    const badDuration = await upload(s, { buf: audioMp4(99) });
    const badAssignment = await upload(s, { meta: { assignmentId: "tarea-secreta-inexistente" } });
    const demo = await upload(t, { dir: "demos", meta: { sharedWith: s, assignmentId: "tarea-secreta" } });
    const orphanName = `users/${s}/recordings/nombre_invalido.m4a`;
    await bucket.file(orphanName).save(audioMp4(5), { resumable: false, contentType: "audio/mp4" });
    for (const ev of [ready, ready, badDuration, badAssignment, demo, { name: orphanName, generation: "1" }, { name: `users/${s}/otra/x.m4a`, generation: "1" }]) {
      await run(ev);
    }
    // un fallo de Storage tambien deja traza sin ruta
    await expect(
      run(ready, { store: { ...bucketStore(bucket), stat: async () => { throw new Error("boom"); } } }),
    ).rejects.toThrow(/^finalize_transient:/);

    expect(logs.length).toBeGreaterThanOrEqual(7);
    const blob = JSON.stringify(logs);
    for (const secret of [...sensitive, idOf(ready.name), idOf(demo.name), ready.name, "users/", ".m4a", orphanName]) {
      expect(blob).not.toContain(secret);
    }
    for (const l of logs) {
      expect(l.message).toBe("finalizeRecording");
      expect(Object.keys(l.data).sort()).toEqual(expect.arrayContaining(["outcome"]));
      const allowed = ["outcome", "reason", "uidHash", "dir"];
      expect(Object.keys(l.data).every((k) => allowed.includes(k))).toBe(true);
      if (l.data.uidHash !== undefined) expect(l.data.uidHash).toMatch(/^[0-9a-f]{12}$/);
    }
    expect(logs.find((l) => l.data.outcome === "ready")?.data.uidHash).toBe(sha256Hex(s).slice(0, 12));
    expect(logs.find((l) => l.data.reason === "duration")).toBeDefined();
    expect(logs.find((l) => l.data.reason === "assignment")).toBeDefined();
    expect(logs.filter((l) => l.data.outcome === "duplicate")).toHaveLength(1);
    expect(logs.filter((l) => l.data.outcome === "ignored")).toHaveLength(1);
    expect(logs.filter((l) => l.data.outcome === "dropped")).toHaveLength(1);
    expect(logs.find((l) => l.data.outcome === "error")).toBeDefined();
  });

  test("sin sumidero inyectado usa el logger de Functions con el mismo contenido", async () => {
    const spy = jest.spyOn(logger, "info").mockImplementation(() => undefined);
    try {
      const uid = rnd("alumno");
      await seedUser(uid);
      const ev = await upload(uid);
      await finalizeRecordingHandler(
        { db, store: bucketStore(bucket), clock: () => NOW, flags, requiredConsentVersion: 1 },
        ev,
      );
      expect(spy).toHaveBeenCalledWith("finalizeRecording", { outcome: "ready", uidHash: sha256Hex(uid).slice(0, 12), dir: "recordings" });
    } finally {
      spy.mockRestore();
    }
  });
});
