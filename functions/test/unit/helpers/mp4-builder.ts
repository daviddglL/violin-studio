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
  return Buffer.concat([
    u32(8 + body.length),
    Buffer.from(type, "ascii"),
    body,
  ]);
};

/** Caja con cabecera `largesize` (size == 1 + 64 bits). */
export const largeBox = (type: string, ...payload: Buffer[]): Buffer => {
  const body = Buffer.concat(payload);
  return Buffer.concat([
    u32(1),
    Buffer.from(type, "ascii"),
    u64(BigInt(16 + body.length)),
    body,
  ]);
};

/** `ftyp` realista de Android (`isom`/`mp42`) con version menor y una marca compatible: 20 bytes. */
export const ftyp = (brand = "isom"): Buffer =>
  box(
    "ftyp",
    Buffer.from(brand, "ascii"),
    u32(0),
    Buffer.from("isom", "ascii"),
  );

/** `ftyp` degenerado: solo la marca mayor (12 bytes), sin version menor. */
export const ftypBrandOnly = (brand = "isom"): Buffer =>
  box("ftyp", Buffer.from(brand, "ascii"));

/** Caja con tamano declarado 0 ("hasta el final del fichero"). */
export const toEofBox = (type: string, ...payload: Buffer[]): Buffer =>
  Buffer.concat([u32(0), Buffer.from(type, "ascii"), ...payload]);

/** MP4 fragmentado: `mvhd` con duracion 0 y `mvex`; la duracion real vive en `moof`/`mehd` (no soportado). */
export const fragmentedMp4 = (): Buffer =>
  Buffer.concat([
    ftyp("iso5"),
    moov(mvhd({ duration: 0 }), box("mvex", box("trex", Buffer.alloc(24)))),
    box("moof", Buffer.alloc(16)),
    mdat(),
  ]);

export const mvhd = (opts: {
  version?: 0 | 1;
  timescale?: number;
  duration: number | bigint;
}): Buffer => {
  const version = opts.version ?? 0;
  const timescale = opts.timescale ?? 1000;
  const rest = Buffer.alloc(80); // rate, volume, matriz, next_track_id... (relleno)
  if (version === 0) {
    return box(
      "mvhd",
      Buffer.from([0, 0, 0, 0]),
      u32(0),
      u32(0),
      u32(timescale),
      u32(Number(opts.duration)),
      rest,
    );
  }
  return box(
    "mvhd",
    Buffer.from([1, 0, 0, 0]),
    u64(0n),
    u64(0n),
    u32(timescale),
    u64(BigInt(opts.duration)),
    rest,
  );
};

export const moov = (...children: Buffer[]): Buffer => box("moov", ...children);
export const mdat = (bytes = 64): Buffer => box("mdat", Buffer.alloc(bytes, 1));
export const free = (bytes = 16): Buffer => box("free", Buffer.alloc(bytes));

/** MP4 valido de audio con la duracion pedida (moov al principio). */
export const audioMp4 = (durationSec: number): Buffer =>
  Buffer.concat([
    ftyp(),
    moov(mvhd({ timescale: 1000, duration: Math.round(durationSec * 1000) })),
    mdat(),
  ]);

/** Lector por rango simulado sobre un buffer; cuenta las lecturas y devuelve menos bytes si se pasa del final. */
export const rangeReader = (data: Buffer) => {
  const calls: Array<[number, number]> = [];
  const read = async (offset: number, length: number): Promise<Buffer> => {
    calls.push([offset, length]);
    return data.subarray(offset, Math.min(offset + length, data.length));
  };
  return { read, calls, size: data.length };
};
