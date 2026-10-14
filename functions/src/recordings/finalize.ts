import { Bucket } from "@google-cloud/storage";
import { DocumentReference, DocumentSnapshot, Firestore, Timestamp } from "firebase-admin/firestore";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { RecordingFlags } from "../config/features";
import { RECORDING_CONSENT_VERSION } from "../config/identity";
import { DEMOS_DIR } from "../config/recordings";
import { Mp4Error, readMp4DurationSec } from "./mp4-duration";
import { ContainerProbe, parseObjectPath, RecordingMeta, RejectReason, validateRecording } from "./validate";

/** Lo unico que `onObjectFinalized` aporta: el resto se lee del objeto (metadatos frescos). */
export interface FinalizeEvent {
  name: string;
  generation: string;
}

export interface ObjectInfo {
  generation: string;
  size: number;
  contentType?: string;
  /** Metadatos personalizados del cliente (`kind`, `assignmentId`, `sharedWith`, ...). */
  metadata: Record<string, string>;
  timeCreated: Date;
}

/** Acceso a Storage inyectable: el parser y las pruebas de fallo no dependen del SDK. */
export interface ObjectStore {
  /** `null` = el objeto ya no existe (404). Cualquier otro error se propaga para que el trigger reintente. */
  stat(name: string): Promise<ObjectInfo | null>;
  readRange(name: string, offset: number, length: number): Promise<Buffer>;
  /** Borra por la ruta dada; 404 = OK. */
  remove(name: string): Promise<void>;
}

export interface FinalizeDeps {
  db: Firestore;
  store: ObjectStore;
  clock: Clock;
  flags: RecordingFlags;
  /** Solo se inyecta en tests; en produccion es `RECORDING_CONSENT_VERSION`. */
  requiredConsentVersion?: number;
  log?: (message: string, data: Record<string, unknown>) => void;
}

export type FinalizeOutcome = "ready" | "rejected";

const DAY_MS = 24 * 60 * 60 * 1000;
export const READY_TTL_DAYS = 90;
export const REJECTED_TTL_DAYS = 7;
/** Forma de un id de documento que se puede interpolar en una ruta (metadatos del cliente: nunca se confia en ellos). */
const SAFE_ID = /^(?!__)[A-Za-z0-9_-]{1,128}$/;

function isNotFound(e: unknown): boolean {
  return (e as { code?: unknown } | null)?.code === 404;
}

export function bucketStore(bucket: Bucket): ObjectStore {
  return {
    async stat(name) {
      try {
        const [m] = await bucket.file(name).getMetadata();
        return {
          generation: String(m.generation),
          size: Number(m.size),
          contentType: m.contentType,
          metadata: { ...(m.metadata ?? {}) } as Record<string, string>,
          timeCreated: new Date(String(m.timeCreated)),
        };
      } catch (e) {
        if (isNotFound(e)) return null;
        throw e;
      }
    },
    async readRange(name, offset, length) {
      const chunks: Buffer[] = [];
      const stream = bucket.file(name).createReadStream({ start: offset, end: offset + length - 1, validation: false });
      for await (const chunk of stream) chunks.push(chunk as Buffer);
      return Buffer.concat(chunks);
    },
    async remove(name) {
      await bucket.file(name).delete({ ignoreNotFound: true });
    },
  };
}

async function probeContainer(store: ObjectStore, name: string, size: number): Promise<ContainerProbe> {
  try {
    const { durationSec, brand } = await readMp4DurationSec((o, l) => store.readRange(name, o, l), size);
    return { ok: true, durationSec, brand };
  } catch (e) {
    // Solo un contenedor defectuoso es motivo de rechazo; un fallo de lectura de Storage debe reintentarse (B4a).
    if (e instanceof Mp4Error) return { ok: false, code: e.code };
    throw e;
  }
}

const str = (v: unknown): string | undefined => (typeof v === "string" && v !== "" ? v : undefined);

export async function finalizeRecordingHandler(deps: FinalizeDeps, event: FinalizeEvent): Promise<FinalizeOutcome> {
  const path = parseObjectPath(event.name);
  if (!path) throw new Error("unreachable: ruta no parseable");
  const info = (await deps.store.stat(event.name))!;
  const meta: RecordingMeta = {
    kind: str(info.metadata.kind),
    assignmentId: str(info.metadata.assignmentId),
    sharedWith: str(info.metadata.sharedWith),
  };
  const container = await probeContainer(deps.store, event.name, info.size);

  const users = deps.db.collection(COLLECTIONS.users);
  const userRef = users.doc(path.uid);
  const recRef = userRef.collection(COLLECTIONS.recordings).doc(path.id);
  const now = deps.clock();

  const outcome = await deps.db.runTransaction(async (tx) => {
    const getIf = (ref: DocumentReference | null): Promise<DocumentSnapshot | null> => (ref ? tx.get(ref) : Promise.resolve(null));
    const sharedOk = meta.sharedWith !== undefined && SAFE_ID.test(meta.sharedWith);
    const assignOk = meta.assignmentId !== undefined && SAFE_ID.test(meta.assignmentId);
    const isDemo = path.dir === DEMOS_DIR;
    const assignmentOwner = isDemo ? (sharedOk ? meta.sharedWith! : null) : path.uid;
    const [userSnap, assignmentSnap, linkSnap] = await Promise.all([
      tx.get(userRef),
      getIf(assignOk && assignmentOwner ? users.doc(assignmentOwner).collection(COLLECTIONS.assignments).doc(meta.assignmentId!) : null),
      getIf(isDemo && sharedOk ? deps.db.collection(COLLECTIONS.teacherLinks).doc(`${path.uid}_${meta.sharedWith}`) : null),
    ]);
    const u = userSnap.data() ?? {};
    const assignment = assignmentSnap?.exists ? assignmentSnap.data() : undefined;
    const link = linkSnap?.exists ? linkSnap.data() : undefined;
    const result = validateRecording(
      meta,
      { path: event.name, contentType: info.contentType, size: info.size, container },
      {
        ownerUid: userSnap.id,
        role: str(u.role),
        isMinor: u.isMinor !== false,
        deleting: u.deletion !== undefined && u.deletion !== null,
        consentStatus: str(u.consentStatus),
        recordingConsent: str(u.recordingConsent),
        recordingConsentVersion: typeof u.recordingConsentVersion === "number" ? u.recordingConsentVersion : undefined,
        recordingConsentEpoch: typeof u.recordingConsentEpoch === "number" ? u.recordingConsentEpoch : undefined,
      },
      {
        recordingsEnabled: deps.flags.recordingsEnabled,
        minorRecordingEnabled: deps.flags.minorRecordingEnabled,
        requiredConsentVersion: deps.requiredConsentVersion ?? RECORDING_CONSENT_VERSION,
        // Demo: la tarea es del espacio del destinatario y debe ser de ESTE profesor (W8).
        assignmentExists: assignment !== undefined && (!isDemo || assignment.teacherUid === path.uid),
        teacherLinkExists: link !== undefined && link.teacherUid === path.uid && link.studentUid === meta.sharedWith,
      },
    );
    const createdAt = Timestamp.fromDate(now);
    if (!result.ok) {
      tx.create(recRef, rejectedDoc(result.reason, createdAt, event, now));
      return "rejected" as const;
    }
    tx.create(recRef, {
      kind: result.kind,
      status: "ready",
      durationSec: result.durationSec,
      sizeBytes: result.sizeBytes,
      createdAt,
      expiresAt: Timestamp.fromMillis(now.getTime() + READY_TTL_DAYS * DAY_MS),
      objectPath: event.name,
      generation: event.generation,
      recordingConsentEpoch: result.consentEpoch,
      ...(result.sharedWith !== undefined ? { sharedWith: result.sharedWith } : {}),
      ...(result.assignmentId !== undefined ? { assignmentId: result.assignmentId } : {}),
    });
    return "ready" as const;
  });

  if (outcome === "rejected") await deps.store.remove(event.name);
  return outcome;
}

function rejectedDoc(reason: RejectReason, createdAt: Timestamp, event: FinalizeEvent, now: Date) {
  return {
    status: "rejected",
    rejectReason: reason,
    createdAt,
    expiresAt: Timestamp.fromMillis(now.getTime() + REJECTED_TTL_DAYS * DAY_MS),
    objectPath: event.name,
    generation: event.generation,
  };
}
