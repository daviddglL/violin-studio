import { Bucket } from "@google-cloud/storage";
import { DocumentData, DocumentReference, DocumentSnapshot, Firestore, Timestamp } from "firebase-admin/firestore";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { RecordingFlags } from "../config/features";
import { RECORDING_CONSENT_VERSION } from "../config/identity";
import { DEMOS_DIR, MAX_RECORDINGS_PER_OWNER, RECORDINGS_DIR } from "../config/recordings";
import { consentDocId, recordingEpochOf } from "../consent/consent-docs";
import { Mp4Error, readMp4DurationSec } from "./mp4-duration";
import { ContainerProbe, parseObjectPath, RecordingMeta, RejectReason, ValidationResult, validateRecording } from "./validate";

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
  /** Sumidero de logs sin PII (solo `uidHash`); por defecto `logger`. */
  log?: (message: string, data: Record<string, unknown>) => void;
}

/**
 * - `ready` / `rejected`: doc escrito; el objeto rechazado se borra.
 * - `duplicate`: ya habia un doc para esa ruta (reintento / evento doble / otra generation): no se toca.
 * - `ignored`: ruta ajena a los dos prefijos, objeto desaparecido o evento obsoleto: sin efectos.
 * - `conflict`: el doc existente apunta a otro `objectPath` (mismo id en el otro prefijo/extension): doc intacto, se borra el objeto del evento.
 * - `dropped`: se borra el objeto sin escribir doc (ruta sin id valido dentro de los prefijos, cuenta borrada o en borrado).
 */
export type FinalizeOutcome = "ready" | "rejected" | "duplicate" | "ignored" | "conflict" | "dropped";

const DAY_MS = 24 * 60 * 60 * 1000;
export const READY_TTL_DAYS = 90;
export const REJECTED_TTL_DAYS = 7;
/** Forma de un id de documento que se puede interpolar en una ruta (metadatos del cliente: nunca se confia en ellos). */
const SAFE_ID = /^(?!__)[A-Za-z0-9_-]{1,128}$/;
/** Rutas dentro de los dos prefijos que no son `users/{uid}/{dir}/{id}.{ext}` validas: no hay id con el que crear un doc. */
const IN_PREFIX = new RegExp(`^users/[^/]+/(?:${RECORDINGS_DIR}|${DEMOS_DIR})/.`);

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

type ExistingDecision = { action: "proceed" } | { action: "duplicate"; redelete: boolean } | { action: "conflict" };

/**
 * Que hacer con un doc ya existente para este id (decision pura, se aplica antes y dentro de la transaccion):
 * - otro `objectPath` -> `conflict`: el doc NO se toca (B3);
 * - misma generation -> `duplicate` (si estaba `rejected`, se vuelve a borrar el objeto por si el borrado fallo);
 * - otra generation: solo un doc `rejected` con generation MENOR se reevalua (el cliente reintenta el mismo id tras un
 *   rechazo); un doc `ready` o un evento viejo no lo alteran (el doc es la autoridad).
 */
function decideExisting(doc: DocumentData | undefined, event: FinalizeEvent): ExistingDecision {
  if (!doc) return { action: "proceed" };
  if (doc.objectPath !== event.name) return { action: "conflict" };
  if (doc.generation === event.generation) return { action: "duplicate", redelete: doc.status === "rejected" };
  if (doc.status === "rejected" && isNewer(event.generation, doc.generation)) return { action: "proceed" };
  return { action: "duplicate", redelete: false };
}

function isNewer(a: unknown, b: unknown): boolean {
  try {
    return BigInt(String(a)) > BigInt(String(b));
  } catch {
    return false;
  }
}

/** `recordingCount` valido (entero >= 0) o infinito: un valor corrupto cuenta como cupo agotado. */
const validCount = (v: unknown): number => (typeof v === "number" && Number.isInteger(v) && v >= 0 ? v : Infinity);

const str = (v: unknown): string | undefined => (typeof v === "string" && v !== "" ? v : undefined);

/** Inicio de la epoca = la concesion mas reciente registrada para (version, epoca); la escribe `grantRecordingConsent` o el tutor. */
function createdAfterGrant(timeCreated: Date, grants: DocumentSnapshot[]): boolean {
  let grantedAt = -Infinity;
  for (const g of grants) {
    const ts = g.exists ? g.data()?.timestamp : undefined;
    if (ts instanceof Timestamp) grantedAt = Math.max(grantedAt, ts.toMillis());
  }
  return Number.isFinite(grantedAt) && timeCreated.getTime() >= grantedAt;
}

export async function finalizeRecordingHandler(deps: FinalizeDeps, event: FinalizeEvent): Promise<FinalizeOutcome> {
  const path = parseObjectPath(event.name);
  if (!path) {
    if (!IN_PREFIX.test(event.name)) return "ignored";
    await deps.store.remove(event.name);
    return "dropped";
  }
  // Metadatos frescos: confirman que el objeto sigue ahi con esta generation (un reintento tardio tras el borrado del
  // dueno no debe resucitar el doc) y evitan fiarse de lo que trae el evento.
  const info = await deps.store.stat(event.name);
  if (!info || info.generation !== event.generation) return "ignored";

  const users = deps.db.collection(COLLECTIONS.users);
  const userRef = users.doc(path.uid);
  const recRef = userRef.collection(COLLECTIONS.recordings).doc(path.id);
  const now = deps.clock();

  // Comprobacion barata antes de leer el contenedor; la autoritativa se repite dentro de la transaccion.
  const settleExisting = async (d: ExistingDecision): Promise<FinalizeOutcome | undefined> => {
    if (d.action === "proceed") return undefined;
    if (d.action === "conflict" || d.redelete) await deps.store.remove(event.name);
    return d.action === "conflict" ? "conflict" : "duplicate";
  };
  const early = await settleExisting(decideExisting((await recRef.get()).data(), event));
  if (early) return early;

  const meta: RecordingMeta = {
    kind: str(info.metadata.kind),
    assignmentId: str(info.metadata.assignmentId),
    sharedWith: str(info.metadata.sharedWith),
  };
  const container = await probeContainer(deps.store, event.name, info.size);

  const outcome = await deps.db.runTransaction(async (tx) => {
    const getIf = (ref: DocumentReference | null): Promise<DocumentSnapshot | null> => (ref ? tx.get(ref) : Promise.resolve(null));
    const sharedOk = meta.sharedWith !== undefined && SAFE_ID.test(meta.sharedWith);
    const assignOk = meta.assignmentId !== undefined && SAFE_ID.test(meta.assignmentId);
    const isDemo = path.dir === DEMOS_DIR;
    const assignmentOwner = isDemo ? (sharedOk ? meta.sharedWith! : null) : path.uid;
    const userSnap = await tx.get(userRef);
    const u = userSnap.data();
    const version = u?.recordingConsentVersion;
    const grantRefs =
      Number.isInteger(version) && (version as number) >= 0
        ? (["self", "guardian"] as const).map((by) =>
            userRef.collection(COLLECTIONS.consents).doc(consentDocId("recording_sharing", version as number, by, recordingEpochOf(u ?? {}))),
          )
        : [];
    const [existingSnap, assignmentSnap, linkSnap, ...grantSnaps] = await Promise.all([
      tx.get(recRef),
      getIf(assignOk && assignmentOwner ? users.doc(assignmentOwner).collection(COLLECTIONS.assignments).doc(meta.assignmentId!) : null),
      getIf(isDemo && sharedOk ? deps.db.collection(COLLECTIONS.teacherLinks).doc(`${path.uid}_${meta.sharedWith}`) : null),
      ...grantRefs.map((ref) => tx.get(ref)),
    ]);

    const existing = decideExisting(existingSnap.data(), event);
    if (existing.action !== "proceed") return existing;
    // Sin doc de usuario o con la cuenta en borrado no se escribe nada bajo `users/{uid}`: la cascada ya paso o esta en curso.
    if (!userSnap.exists || !u || (u.deletion !== undefined && u.deletion !== null)) return { action: "dropped" as const };

    const assignment = assignmentSnap?.exists ? assignmentSnap.data() : undefined;
    const link = linkSnap?.exists ? linkSnap.data() : undefined;
    let result: ValidationResult | { ok: false; reason: "quota" } = validateRecording(
      meta,
      { path: event.name, contentType: info.contentType, size: info.size, container },
      {
        ownerUid: userSnap.id,
        role: str(u.role),
        isMinor: u.isMinor !== false,
        deleting: false,
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
    // Epoca de captura: el objeto debe haberse creado DESPUES de la concesion de la epoca vigente. Una subida en vuelo
    // de una epoca anterior (revocada y reconcedida) no pasa. Sin registro fechado de la concesion, falla cerrado.
    if (result.ok && !createdAfterGrant(info.timeCreated, grantSnaps)) result = { ok: false, reason: "consent" };

    // Cupo (A21): la autoridad es esta transaccion; la lectura del usuario la serializa con las demas finalizaciones del
    // mismo dueno. Ausente = 0; un contador ilegible falla cerrado (lo repara `recount-recordings`, B5a).
    const used = u.recordingCount === undefined ? 0 : validCount(u.recordingCount);
    if (result.ok && !(used < MAX_RECORDINGS_PER_OWNER)) result = { ok: false, reason: "quota" };

    const createdAt = Timestamp.fromDate(now);
    if (!result.ok) {
      tx.set(recRef, rejectedDoc(result.reason, createdAt, event, now));
      return { action: "rejected" as const };
    }
    tx.update(userRef, { recordingCount: used + 1 });
    tx.set(recRef, {
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
    return { action: "ready" as const };
  });

  if (outcome.action === "ready") return "ready";
  if (outcome.action === "rejected" || outcome.action === "dropped") {
    await deps.store.remove(event.name);
    return outcome.action;
  }
  return (await settleExisting(outcome)) ?? "duplicate";
}

function rejectedDoc(reason: RejectReason | "quota", createdAt: Timestamp, event: FinalizeEvent, now: Date) {
  return {
    status: "rejected",
    rejectReason: reason,
    createdAt,
    expiresAt: Timestamp.fromMillis(now.getTime() + REJECTED_TTL_DAYS * DAY_MS),
    objectPath: event.name,
    generation: event.generation,
  };
}
