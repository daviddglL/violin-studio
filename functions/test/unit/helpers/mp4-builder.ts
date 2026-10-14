/** Constructor de MP4 minimos para probar el parser de `mvhd` sin ficheros binarios opacos (solo cajas, sin media real). */
export const u32 = (n: number): Buffer => {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
};
const u64 = (n: bigint): Buffer => {
  const b = Buffer.alloc(8);
  b.writeBigUInt64BE(n);
  return b;
};

export const box = (type: string, ...payload: Buffer[]): Buffer => {
  const body = Buffer.concat(payload);
  return Buffer.concat([u32(8 + body.length), Buffer.from(type, "ascii"), body]);
};

/** Caja con cabecera `largesize` (size == 1 + 64 bits). */
export const largeBox = (type: string, ...payload: Buffer[]): Buffer => {
  const body = Buffer.concat(payload);
  return Buffer.concat([u32(1), Buffer.from(type, "ascii"), u64(BigInt(16 + body.length)), body]);
};

export const ftyp = (brand = "M4A "): Buffer =>
  box("ftyp", Buffer.from(brand, "ascii"), u32(0), Buffer.from("isom", "ascii"));

export const mvhd = (opts: { version?: 0 | 1; timescale?: number; duration: number | bigint }): Buffer => {
  const version = opts.version ?? 0;
  const timescale = opts.timescale ?? 1000;
  const rest = Buffer.alloc(80); // rate, volume, matriz, next_track_id... (relleno)
  if (version === 0) {
    return box("mvhd", Buffer.from([0, 0, 0, 0]), u32(0), u32(0), u32(timescale), u32(Number(opts.duration)), rest);
  }
  return box("mvhd", Buffer.from([1, 0, 0, 0]), u64(0n), u64(0n), u32(timescale), u64(BigInt(opts.duration)), rest);
};

export const moov = (...children: Buffer[]): Buffer => box("moov", ...children);
export const mdat = (bytes = 64): Buffer => box("mdat", Buffer.alloc(bytes, 1));
export const free = (bytes = 16): Buffer => box("free", Buffer.alloc(bytes));

/** MP4 valido de audio con la duracion pedida (moov al principio). */
export const audioMp4 = (durationSec: number): Buffer =>
  Buffer.concat([ftyp(), moov(mvhd({ timescale: 1000, duration: Math.round(durationSec * 1000) })), mdat()]);

/** Lector por rango simulado sobre un buffer; cuenta las lecturas y devuelve menos bytes si se pasa del final. */
export const rangeReader = (data: Buffer) => {
  const calls: Array<[number, number]> = [];
  const read = async (offset: number, length: number): Promise<Buffer> => {
    calls.push([offset, length]);
    return data.subarray(offset, Math.min(offset + length, data.length));
  };
  return { read, calls, size: data.length };
};
