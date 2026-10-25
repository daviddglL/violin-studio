import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { Bucket } from "@google-cloud/storage";
import { getStorage } from "firebase-admin/storage";
import { FinalizeEvent } from "../../../src/recordings/finalize";
import { audioMp4 } from "../../unit/helpers/mp4-builder";

export const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
export const db = getFirestore(app);
/**
 * Los tests que llaman al handler directamente usan OTRO bucket: el trigger emulado solo escucha el bucket por defecto, asi
 * no compite con ellos (doble procesamiento). `finalize-trigger` usa `triggerBucket`.
 */
export const bucket = getStorage(app).bucket(`${project}-finalize-direct`);
export const triggerBucket = getStorage(app).bucket();

export const NOW = new Date("2026-10-14T10:00:00.000Z");
export const DAY = 24 * 60 * 60 * 1000;
export const rnd = (p: string) => `${p}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;

export async function wipeFirestore() {
  await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${project}/databases/(default)/documents`, {
    method: "DELETE",
  });
}

/** Usuario adulto con consentimiento base y de grabacion vigentes (epoca 0, concedida hace 1 h respecto del reloj real). */
export async function seedUser(uid: string, over: Record<string, unknown> = {}, opts: { consentDoc?: boolean } = {}) {
  await db.collection("users").doc(uid).set({
    role: "independent",
    isMinor: false,
    consentStatus: "granted",
    recordingConsent: "granted",
    recordingConsentVersion: 1,
    recordingConsentEpoch: 0,
    ...over,
  });
  if (opts.consentDoc !== false) await seedGrant(uid, 1, 0, new Date(Date.now() - 60 * 60 * 1000));
}

/** Registro de la concesion de la epoca (lo que escribe `grantRecordingConsent`): fija el inicio de la epoca. */
export async function seedGrant(uid: string, version: number, epoch: number, at: Date, by: "self" | "guardian" = "self") {
  await db
    .collection("users")
    .doc(uid)
    .collection("consents")
    .doc(`recording_sharing_v${version}_${by}_e${epoch}`)
    .set({ type: "recording_sharing", version, grantedBy: by, timestamp: Timestamp.fromDate(at) });
}

export const seedTeacher = (uid: string, over: Record<string, unknown> = {}) => seedUser(uid, { role: "teacher", ...over });

export const seedAssignment = (owner: string, id: string, data: Record<string, unknown> = {}) =>
  db.collection("users").doc(owner).collection("assignments").doc(id).set({ status: "open", ...data });

export const seedLink = (t: string, s: string) =>
  db.collection("teacherLinks").doc(`${t}_${s}`).set({ teacherUid: t, studentUid: s });

export interface UploadOpts {
  dir?: "recordings" | "demos";
  id?: string;
  buf?: Buffer;
  contentType?: string;
  /** `undefined` quita la clave (p. ej. `recordingConsentEpoch`, que por defecto es "0" como la epoca de `seedUser`). */
  meta?: Record<string, string | undefined>;
  ext?: string;
  bucket?: Bucket;
}

/** Sube un objeto con Admin al emulador de Storage y devuelve el evento que dispararia `onObjectFinalized`. */
export async function upload(uid: string, o: UploadOpts = {}): Promise<FinalizeEvent> {
  const id = o.id ?? rnd("r");
  const name = `users/${uid}/${o.dir ?? "recordings"}/${id}.${o.ext ?? "m4a"}`;
  const file = (o.bucket ?? bucket).file(name);
  const custom = Object.entries({ kind: "audio", recordingConsentEpoch: "0", ...(o.meta ?? {}) }).filter(([, v]) => v !== undefined);
  await file.save(o.buf ?? audioMp4(30), {
    resumable: false,
    contentType: o.contentType ?? "audio/mp4",
    metadata: { metadata: Object.fromEntries(custom) },
  });
  const [m] = await file.getMetadata();
  return { name, generation: String(m.generation) };
}

export const exists = async (name: string, b: Bucket = bucket) => (await b.file(name).exists())[0];
export const recRef = (uid: string, id: string) => db.collection("users").doc(uid).collection("recordings").doc(id);
export const recDoc = async (uid: string, id: string) => (await recRef(uid, id).get()).data();
export const idOf = (name: string) => name.split("/").pop()!.replace(/\.(m4a|mp4)$/, "");
export const userDoc = async (uid: string) => (await db.collection("users").doc(uid).get()).data();

/** Doc `ready` como lo deja finalize para ese evento (sembrado directo: no dispara ningun trigger de borrado). */
export async function seedRecording(uid: string, ev: FinalizeEvent, over: Record<string, unknown> = {}) {
  const id = idOf(ev.name);
  await recRef(uid, id).set({
    kind: "audio",
    status: "ready",
    durationSec: 30,
    sizeBytes: 100,
    createdAt: Timestamp.fromDate(NOW),
    expiresAt: Timestamp.fromMillis(NOW.getTime() + 90 * DAY),
    objectPath: ev.name,
    generation: ev.generation,
    recordingConsentEpoch: 0,
    ...over,
  });
  return id;
}

export const seedReview = (uid: string, id: string, reviewer = "t1") =>
  recRef(uid, id).collection("reviews").doc(reviewer).set({ reviewerUid: reviewer, verdict: "approved", text: "bien" });
export const reviewCount = async (uid: string, id: string) => (await recRef(uid, id).collection("reviews").get()).size;
