import { Bucket } from "@google-cloud/storage";
import { DocumentData, DocumentReference, DocumentSnapshot, Firestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { Clock } from "../common/clock";
import { COLLECTIONS } from "../common/collections";
import { sha256Hex } from "../common/hashing";
import { RecordingFlags } from "../config/features";
import { RECORDING_CONSENT_VERSION } from "../config/identity";
import { BLUR_POLICY_VERSION, DEMOS_DIR, MAX_RECORDINGS_PER_OWNER, RECORDINGS_DIR } from "../config/recordings";
import { consentDocId, recordingEpochOf } from "../consent/consent-docs";
import { deleteRecording } from "./delete-recording";
import { Mp4Error, readMp4DurationSec } from "./mp4-duration";
import { ContainerProbe, parseObjectPath, RecordingMeta, RejectReason, ValidationResult, validateRecording } from "./validate";

/** Lo unico que `onObjectFinalized` aporta: el resto se lee del objeto (metadatos frescos). */
export interface FinalizeEvent {
  name: string;
  generation: string;
  /** `time` del CloudEvent (ISO 8601): fija la antiguedad del evento para cortar reintentos envenenados (W4). */
  time?: string;
}

export interface ObjectInfo {
  generation: string;
  size: number;
  contentType?: string;
  /** Metadatos personalizados del cliente (`kind`, `assignmentId`, `sharedWith`, `recordingConsentEpoch`, ...). */
  metadata: Record<string, string>;
  timeCreated: Date;
}

/** Acceso a Storage inyectable: el parser y las pruebas de fallo no dependen del SDK. */
export interface ObjectStore {
  /** `null` = el objeto ya no existe (404). Cualquier otro error se propaga para que el trigger reintente. */
  stat(name: string): Promise<ObjectInfo | null>;
  readRange(name: string, offset: number, length: number): Promise<Buffer>;
  /**
   * Borra SOLO esa generation (precondicion `ifGenerationMatch`): si el cliente ya resubio el mismo nombre, la generation
   * nueva no se toca (W1). 404 (ya no existe) y 412 (ahora hay otra generation) = OK.
   */
  remove(name: string, generation: string): Promise<void>;
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
 * Todo borrado es del objeto del EVENTO y solo de su generation (W1).
 * - `ready` / `rejected`: doc escrito; el objeto rechazado se borra. `rejected(internal)` = evento envenenado (W4).
 * - `duplicate`: ya habia un doc para esta misma generation (reintento / evento doble) o uno `rejected` mas nuevo: no se toca.
 * - `ignored`: ruta ajena a los dos prefijos, objeto desaparecido o evento obsoleto: sin efectos (o abandono envenenado).
 * - `conflict`: el doc existente es de otro `objectPath` (mismo id en el otro prefijo/extension) o es `ready` de otra
 *   generation (sobrescritura, W2): doc intacto y se borra el objeto del evento (nunca se sirven bytes sin validar).
 * - `dropped`: se borra el objeto sin escribir doc (ruta sin id valido dentro de los prefijos, cuenta borrada o en borrado).
 */
export type FinalizeOutcome = "ready" | "rejected" | "duplicate" | "ignored" | "conflict" | "dropped";

/** `quota` lo decide la transaccion (A21) e `internal` el corte de eventos envenenados (W4); el resto, el validador. */
export type FinalizeRejectReason = RejectReason | "quota" | "internal";

const DAY_MS = 24 * 60 * 60 * 1000;
export const READY_TTL_DAYS = 90;
export const REJECTED_TTL_DAYS = 7;
/**
 * W4: un fallo que no es `Mp4Error` (Storage/Firestore caidos, bug) se reintenta; pasado este tiempo desde `event.time`
 * se trata como permanente (doc `rejected(internal)` + borrado del objeto) en vez de reintentar hasta 7 dias.
 */
export const POISON_AFTER_MS = 6 * 60 * 60 * 1000;
/**
 * W3 (revision fresca de B5a): nunca se escribe `ready` para un objeto con esta antiguedad (`timeCreated`) o mas; se
 * escribe `rejected(internal)` y se borra esa generation. Queda 4 h por debajo de `ORPHAN_MIN_AGE_MS` (24 h): el barrido
 * de huerfanos nunca puede borrar un objeto al que un finalize tardio (reintento) le cree el doc `ready` en medio.
 */
export const READY_MAX_OBJECT_AGE_MS = 20 * 60 * 60 * 1000;
/** Forma de un id de documento que se puede interpolar en una ruta (metadatos del cliente: nunca se confia en ellos). */
const SAFE_ID = /^(?!__)[A-Za-z0-9_-]{1,128}$/;
/** Rutas dentro de los dos prefijos que no son `users/{uid}/{dir}/{id}.{ext}` validas: no hay id con el que crear un doc. */
const IN_PREFIX = new RegExp(`^users/[^/]+/(?:${RECORDINGS_DIR}|${DEMOS_DIR})/.`);

const errorCode = (e: unknown): unknown => (e as { code?: unknown } | null)?.code;

function isNotFound(e: unknown): boolean {
  return errorCode(e) === 404;
}

/** S2: lo que ve el runtime no lleva el mensaje del SDK (rutas, uids): solo un codigo con forma segura, y sin `cause`. */
function sanitized(e: unknown): Error {
  const c = errorCode(e);
  const safe = (typeof c === "number" && Number.isInteger(c)) || (typeof c === "string" && /^[A-Za-z0-9_-]{1,32}$/.test(c));
  return new Error(`finalize_transient:${safe ? String(c) : "unknown"}`);
}

/**
 * W3 (revision fresca B5b): un 404 en `remove` solo cuenta como "ya borrado" si el BUCKET existe; con un bucket mal
 * configurado (inexistente) todos los objetos darian 404 y una purga o desvinculacion se daria por completada sin borrar
 * nada. Lanza `{code: "bucket-not-found"}` (sin nombre de bucket ni ruta). La existencia se cachea en positivo por store.
 */
export function bucketStore(bucket: Bucket): ObjectStore {
  let bucketChecked = false;
  const assertBucket = async () => {
    if (bucketChecked) return;
    // Sondeo por listado (mismo permiso que el barrido): en GCS un bucket inexistente da 404. `bucket.exists()` no sirve
    // con el emulador de Storage (no implementa el GET de bucket); su listado de un bucket desconocido da 200 vacio.
    try {
      await bucket.getFiles({ maxResults: 1, autoPaginate: false });
    } catch (e) {
      if (isNotFound(e)) throw Object.assign(new Error("bucket_not_found"), { code: "bucket-not-found" });
      throw e;
    }
    bucketChecked = true;
  };
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
    async remove(name, generation) {
      const file = bucket.file(name);
      try {
        // En GCS la atomicidad la da `ifGenerationMatch`; el emulador ignora esa precondicion en delete, y la lectura
        // previa hace la regla observable en los tests de integracion (y ahorra el 412 en el caso comun).
        const [m] = await file.getMetadata();
        if (String(m.generation) !== generation) return;
        await file.delete({ ifGenerationMatch: generation });
      } catch (e) {
        if (errorCode(e) === 412) return;
        if (isNotFound(e)) return assertBucket();
        throw e;
      }
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
type TxOutcome = ExistingDecision | { action: "ready" | "rejected" | "dropped" };

/**
 * Que hacer con un doc ya existente para este id (decision pura, se aplica antes y dentro de la transaccion):
 * - otro `objectPath` -> `conflict`: el doc NO se toca (B3);
 * - misma generation -> `duplicate` (si estaba `rejected`, se vuelve a borrar el objeto por si el borrado fallo);
 * - otra generation: un doc `rejected` con generation MENOR se reevalua (el cliente reintenta el mismo id tras un
 *   rechazo); un doc `ready` -> `conflict` (W2): el doc manda y el objeto del evento, sin validar, se borra para no
 *   servirlo bajo el doc viejo; un doc `rejected` mas nuevo que el evento -> `duplicate` sin efectos.
 */
function decideExisting(doc: DocumentData | undefined, event: FinalizeEvent): ExistingDecision {
  if (!doc) return { action: "proceed" };
  if (doc.objectPath !== event.name) return { action: "conflict" };
  if (doc.generation === event.generation) return { action: "duplicate", redelete: doc.status === "rejected" };
  if (doc.status === "rejected" && isNewer(event.generation, doc.generation)) return { action: "proceed" };
  if (doc.status === "ready") return { action: "conflict" };
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

const isDeleting = (u: DocumentData | undefined): boolean => !u || (u.deletion !== undefined && u.deletion !== null);

/** Inicio de la epoca = la concesion mas reciente registrada para (version, epoca); la escribe `grantRecordingConsent` o el tutor. */
function createdAfterGrant(timeCreated: Date, grants: DocumentSnapshot[]): boolean {
  let grantedAt = -Infinity;
  for (const g of grants) {
    const ts = g.exists ? g.data()?.timestamp : undefined;
    if (ts instanceof Timestamp) grantedAt = Math.max(grantedAt, ts.toMillis());
  }
  return Number.isFinite(grantedAt) && timeCreated.getTime() >= grantedAt;
}

/** El evento lleva fallando `POISON_AFTER_MS` o mas (sin `time` valido nunca se da por perdido). */
function isPoisoned(deps: FinalizeDeps, event: FinalizeEvent): boolean {
  const at = event.time ? Date.parse(event.time) : NaN;
  return Number.isFinite(at) && deps.clock().getTime() - at >= POISON_AFTER_MS;
}

/**
 * Entrada del trigger. Registra UNA linea por evento con `outcome`, `reason`, `dir` y `uidHash` (REQ-REC-12): nunca la
 * ruta, el id de la grabacion, el uid, tareas, nombres ni el mensaje de un error. Un fallo se relanza saneado
 * (`finalize_transient:<code>`, S2) para que la plataforma reintente, salvo que el evento este envenenado (W4).
 */
export async function finalizeRecordingHandler(deps: FinalizeDeps, event: FinalizeEvent): Promise<FinalizeOutcome> {
  const log = deps.log ?? ((m: string, d: Record<string, unknown>) => logger.info(m, d));
  const path = parseObjectPath(event.name);
  const who = path ? { uidHash: sha256Hex(path.uid).slice(0, 12), dir: path.dir } : {};
  const note: { reason?: string } = {};
  let outcome: FinalizeOutcome;
  try {
    try {
      outcome = await finalizeCore(deps, event, path, note);
    } catch (e) {
      if (!isPoisoned(deps, event)) throw e;
      outcome = await settlePoisoned(deps, event, path, note);
    }
  } catch (e) {
    log("finalizeRecording", { outcome: "error", ...who });
    throw sanitized(e);
  }
  log("finalizeRecording", { outcome, ...(note.reason ? { reason: note.reason } : {}), ...who });
  return outcome;
}

/** Efectos en Storage tras decidir (siempre el objeto del evento y solo su generation). */
async function applyOutcome(store: ObjectStore, event: FinalizeEvent, outcome: TxOutcome): Promise<FinalizeOutcome> {
  switch (outcome.action) {
    case "ready":
      return "ready";
    case "rejected":
    case "dropped":
    case "conflict":
      await store.remove(event.name, event.generation);
      return outcome.action;
    case "duplicate":
      if (outcome.redelete) await store.remove(event.name, event.generation);
      return "duplicate";
    case "proceed":
      // `decideExisting` lo devuelve, pero las transacciones lo resuelven siempre en ready/rejected/dropped.
      throw new Error("finalize: proceed sin resolver");
  }
}

/**
 * W4: el evento sigue fallando tras `POISON_AFTER_MS`. Sin id (ruta invalida dentro de los prefijos) no hay doc que
 * escribir: se abandona (`ignored`; lo recoge el barrido de huerfanos de B5a). Con id: doc `rejected(internal)` con la
 * misma guarda transaccional que el camino normal (doc existente, cuenta borrada o en borrado) y borrado del objeto
 * de esa generation. Si esto tambien falla, se relanza (saneado) y la plataforma reintenta.
 */
async function settlePoisoned(
  deps: FinalizeDeps,
  event: FinalizeEvent,
  path: ReturnType<typeof parseObjectPath>,
  note: { reason?: string },
): Promise<FinalizeOutcome> {
  if (!path) {
    note.reason = "internal";
    return "ignored";
  }
  const userRef = deps.db.collection(COLLECTIONS.users).doc(path.uid);
  const recRef = userRef.collection(COLLECTIONS.recordings).doc(path.id);
  const now = deps.clock();
  const outcome = await deps.db.runTransaction(async (tx): Promise<TxOutcome> => {
    delete note.reason;
    const [existingSnap, userSnap] = await Promise.all([tx.get(recRef), tx.get(userRef)]);
    const existing = decideExisting(existingSnap.data(), event);
    if (existing.action !== "proceed") return existing;
    if (!userSnap.exists || isDeleting(userSnap.data())) return { action: "dropped" };
    note.reason = "internal";
    tx.set(recRef, rejectedDoc("internal", Timestamp.fromDate(now), event, now));
    return { action: "rejected" };
  });
  return applyOutcome(deps.store, event, outcome);
}

async function finalizeCore(
  deps: FinalizeDeps,
  event: FinalizeEvent,
  path: ReturnType<typeof parseObjectPath>,
  note: { reason?: string },
): Promise<FinalizeOutcome> {
  if (!path) {
    if (!IN_PREFIX.test(event.name)) return "ignored";
    await deps.store.remove(event.name, event.generation);
    return "dropped";
  }
  // Metadatos frescos: confirman que el objeto sigue ahi con esta generation (un reintento tardio tras el borrado del
  // dueno no debe resucitar el doc) y evitan fiarse de lo que trae el evento.
  const users = deps.db.collection(COLLECTIONS.users);
  const userRef = users.doc(path.uid);
  const recRef = userRef.collection(COLLECTIONS.recordings).doc(path.id);
  const info = await deps.store.stat(event.name);
  if (!info || info.generation !== event.generation) {
    // Un doc `ready` de ESTA generation sin objeto es un finalize que cayo tras el commit mientras el objeto se borraba
    // (B5a.5): se retira para no dejar un doc colgando ni una plaza ocupada. Un doc de otra generation no se toca.
    if (!info) await retireDangling(deps, recRef, event);
    return "ignored";
  }
  const now = deps.clock();

  // Comprobacion barata antes de leer el contenedor; la autoritativa se repite dentro de la transaccion.
  const early = decideExisting((await recRef.get()).data(), event);
  if (early.action !== "proceed") return applyOutcome(deps.store, event, early);

  const meta: RecordingMeta = {
    kind: str(info.metadata.kind),
    assignmentId: str(info.metadata.assignmentId),
    sharedWith: str(info.metadata.sharedWith),
    blurApplied: str(info.metadata.blurApplied),
    blurPolicyVersion: str(info.metadata.blurPolicyVersion),
  };
  const container = await probeContainer(deps.store, event.name, info.size);

  const outcome = await deps.db.runTransaction(async (tx): Promise<TxOutcome> => {
    delete note.reason; // S3: cada intento de la transaccion decide de nuevo
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
    if (!userSnap.exists || !u || isDeleting(u)) return { action: "dropped" };

    const assignment = assignmentSnap?.exists ? assignmentSnap.data() : undefined;
    const link = linkSnap?.exists ? linkSnap.data() : undefined;
    let result: ValidationResult | { ok: false; reason: "quota" | "internal" } = validateRecording(
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
        videoEnabled: deps.flags.videoEnabled,
        minorRecordingEnabled: deps.flags.minorRecordingEnabled,
        requiredConsentVersion: deps.requiredConsentVersion ?? RECORDING_CONSENT_VERSION,
        // Demo: la tarea es del espacio del destinatario y debe ser de ESTE profesor (W8).
        assignmentExists: assignment !== undefined && (!isDemo || assignment.teacherUid === path.uid),
        teacherLinkExists: link !== undefined && link.teacherUid === path.uid && link.studentUid === meta.sharedWith,
      },
    );
    // Epoca de captura (W3), dos defensas:
    // 1) sello del cliente: el metadato `recordingConsentEpoch` (las reglas de Storage exigen que sea la epoca vigente AL
    //    SUBIR) debe ser la epoca vigente ahora. Una grabacion capturada en una epoca anterior y subida tras reconceder
    //    (cola offline, otro dispositivo) lleva el sello viejo y no pasa;
    // 2) `timeCreated` del objeto >= concesion de la epoca vigente: atrapa las subidas EMPEZADAS antes de la concesion.
    // Residual: el servidor no conoce el instante de CAPTURA; si un cliente sella mal (bug) una grabacion vieja con la
    // epoca nueva, se acepta y se estampa con la nueva, y la purga acotada por epoca (B5b) no la quitaria.
    // Sin registro fechado de la concesion, falla cerrado.
    if (result.ok && info.metadata.recordingConsentEpoch !== String(result.consentEpoch)) result = { ok: false, reason: "consent" };
    if (result.ok && !createdAfterGrant(info.timeCreated, grantSnaps)) result = { ok: false, reason: "consent" };

    // Cupo (A21): la autoridad es esta transaccion; la lectura del usuario la serializa con las demas finalizaciones del
    // mismo dueno. Ausente = 0; un contador ilegible falla cerrado (lo repara `recount-recordings`, B5a).
    const used = u.recordingCount === undefined ? 0 : validCount(u.recordingCount);
    if (result.ok && !(used < MAX_RECORDINGS_PER_OWNER)) result = { ok: false, reason: "quota" };
    // W3: exito tardio (reintentos durante horas) sobre un objeto ya viejo -> nunca `ready` (ver READY_MAX_OBJECT_AGE_MS).
    // Se mide con el reloj de ESTE intento de la transaccion.
    if (result.ok && deps.clock().getTime() - info.timeCreated.getTime() >= READY_MAX_OBJECT_AGE_MS) result = { ok: false, reason: "internal" };

    const createdAt = Timestamp.fromDate(now);
    if (!result.ok) {
      note.reason = result.reason;
      tx.set(recRef, rejectedDoc(result.reason, createdAt, event, now));
      return { action: "rejected" };
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
      // Video: version de las reglas de difuminado con las que el movil lo proceso (auditoria).
      ...(result.kind === "video" ? { blurPolicyVersion: BLUR_POLICY_VERSION } : {}),
      ...(result.sharedWith !== undefined ? { sharedWith: result.sharedWith } : {}),
      ...(result.assignmentId !== undefined ? { assignmentId: result.assignmentId } : {}),
    });
    return { action: "ready" };
  });

  const result = await applyOutcome(deps.store, event, outcome);
  if (result !== "ready") return result;
  // B5a.5: el objeto pudo desaparecer entre el `stat` de arriba y este commit. Caso tipico: un intento ANTERIOR de este
  // mismo evento ya habia creado el doc, el dueno lo borro y `onRecordingDeleted` borro el objeto (y releyo el doc antes de
  // que este intento lo recreara). El doc recien creado apunta entonces a un objeto que ya no existe: se retira, y el
  // trigger de ese borrado devuelve la plaza que esta transaccion acaba de sumar.
  const after = await deps.store.stat(event.name);
  if (after && after.generation === event.generation) return "ready";
  await retireDangling(deps, recRef, event);
  note.reason = "object_gone";
  return "ignored";
}

/** Borra el doc `ready` que apunta a (objectPath, generation) del evento si el objeto ya no existe; su trigger baja el contador. */
async function retireDangling(deps: FinalizeDeps, recRef: DocumentReference, event: FinalizeEvent): Promise<void> {
  await deleteRecording({ db: deps.db, store: deps.store }, recRef, {
    guard: (d) => d.status === "ready" && d.objectPath === event.name && String(d.generation) === event.generation,
  });
}

function rejectedDoc(reason: FinalizeRejectReason, createdAt: Timestamp, event: FinalizeEvent, now: Date) {
  return {
    status: "rejected",
    rejectReason: reason,
    createdAt,
    expiresAt: Timestamp.fromMillis(now.getTime() + REJECTED_TTL_DAYS * DAY_MS),
    objectPath: event.name,
    generation: event.generation,
  };
}
