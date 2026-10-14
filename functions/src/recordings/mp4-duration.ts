/**
 * Duracion real de un MP4/M4A leyendo solo las cabeceras de caja con lecturas por rango (REQ-REC-05, A11/R4): comprueba a
 * la vez que el contenedor es un MP4 real (`ftyp` primero), anulando un `contentType` falso, sin descargar el fichero.
 * Recorre las cajas de primer nivel por sus tamanos (saltando `mdat`) hasta `moov` y dentro de `moov` hasta `mvhd`;
 * funciona con `moov` al principio o al final. Puro: el acceso al objeto es un `RangeReader` inyectado.
 */
export type RangeReader = (offset: number, length: number) => Promise<Buffer>;

export type Mp4ErrorCode =
  | "not_mp4" // no empieza por `ftyp` o es demasiado corto para tener cabecera
  | "truncated" // una caja declara mas bytes de los que tiene el fichero
  | "inconsistent" // tamanos de caja imposibles, version/timescale invalidos
  | "no_moov"
  | "no_mvhd"
  | "too_many_reads";

export class Mp4Error extends Error {
  constructor(readonly code: Mp4ErrorCode) {
    super(code);
    this.name = "Mp4Error";
  }
}

export interface Mp4Duration {
  durationSec: number;
  /** Marca mayor de `ftyp` (4 caracteres). */
  brand: string;
}

export const DEFAULT_MAX_READS = 16;
const HEADER_PROBE = 16; // size(4) + type(4) + largesize(8)
const MVHD_PROBE = 48; // cabecera + version/flags + campos hasta la duracion de la version 1
const FTYP = "ftyp";

interface BoxHeader {
  type: string;
  headerLen: number;
  size: number;
}

export async function readMp4DurationSec(
  read: RangeReader,
  totalSize: number,
  opts: { maxReads?: number } = {},
): Promise<Mp4Duration> {
  const maxReads = opts.maxReads ?? DEFAULT_MAX_READS;
  let reads = 0;
  const readAt = async (offset: number, length: number): Promise<Buffer> => {
    if (reads >= maxReads) throw new Mp4Error("too_many_reads");
    reads += 1;
    const buf = await read(offset, length);
    // Menos bytes de los pedidos dentro del fichero: el objeto cambio o esta cortado.
    if (buf.length < length) throw new Mp4Error("truncated");
    return buf;
  };

  if (totalSize < 8) throw new Mp4Error("not_mp4");

  const parseHeader = (buf: Buffer, offset: number, limit: number): BoxHeader => {
    const size32 = buf.readUInt32BE(0);
    const type = buf.toString("latin1", 4, 8);
    let headerLen = 8;
    let size = size32;
    if (size32 === 1) {
      if (buf.length < 16) throw new Mp4Error("truncated");
      const big = buf.readBigUInt64BE(8);
      if (big > BigInt(Number.MAX_SAFE_INTEGER)) throw new Mp4Error("inconsistent");
      headerLen = 16;
      size = Number(big);
    } else if (size32 === 0) {
      size = limit - offset; // hasta el final del contenedor
    }
    return { type, headerLen, size };
  };

  let brand = "";
  let offset = 0;
  let first = true;
  while (offset + 8 <= totalSize) {
    const want = Math.min(HEADER_PROBE, totalSize - offset);
    const buf = await readAt(offset, want);
    if (first && buf.toString("latin1", 4, 8) !== FTYP) throw new Mp4Error("not_mp4");
    const box = parseHeader(buf, offset, totalSize);
    if (box.size < box.headerLen) throw new Mp4Error("inconsistent");
    const end = offset + box.size;
    if (end > totalSize) throw new Mp4Error("truncated");
    if (first) {
      brand = buf.length >= 12 ? buf.toString("latin1", 8, 12) : "";
      first = false;
    }
    if (box.type === "moov") {
      return { durationSec: await durationInMoov(readAt, offset + box.headerLen, end, parseHeader), brand };
    }
    offset = end;
  }
  if (first) throw new Mp4Error("not_mp4");
  throw new Mp4Error("no_moov");
}

async function durationInMoov(
  readAt: (offset: number, length: number) => Promise<Buffer>,
  start: number,
  end: number,
  parseHeader: (buf: Buffer, offset: number, limit: number) => BoxHeader,
): Promise<number> {
  let c = start;
  while (c + 8 <= end) {
    const buf = await readAt(c, Math.min(MVHD_PROBE, end - c));
    const box = parseHeader(buf, c, end);
    if (box.size < box.headerLen || c + box.size > end) throw new Mp4Error("inconsistent");
    if (box.type === "mvhd") return durationFromMvhd(buf, box);
    c += box.size;
  }
  throw new Mp4Error("no_mvhd");
}

function durationFromMvhd(buf: Buffer, box: BoxHeader): number {
  const p = box.headerLen; // version(1) flags(3)
  if (box.size < p + 4) throw new Mp4Error("inconsistent");
  const version = buf[p];
  let timescale: number;
  let duration: number;
  if (version === 0) {
    if (box.size < p + 20 || buf.length < p + 20) throw new Mp4Error("inconsistent");
    timescale = buf.readUInt32BE(p + 12);
    duration = buf.readUInt32BE(p + 16);
  } else if (version === 1) {
    if (box.size < p + 32 || buf.length < p + 32) throw new Mp4Error("inconsistent");
    timescale = buf.readUInt32BE(p + 20);
    const big = buf.readBigUInt64BE(p + 24);
    if (big > BigInt(Number.MAX_SAFE_INTEGER)) throw new Mp4Error("inconsistent");
    duration = Number(big);
  } else {
    throw new Mp4Error("inconsistent");
  }
  if (timescale === 0) throw new Mp4Error("inconsistent");
  return duration / timescale;
}
