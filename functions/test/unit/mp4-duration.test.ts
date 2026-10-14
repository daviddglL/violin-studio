import {
  Mp4Error,
  Mp4ErrorCode,
  readMp4DurationSec,
} from "../../src/recordings/mp4-duration";
import {
  box,
  fragmentedMp4,
  free,
  ftyp,
  ftypBrandOnly,
  largeBox,
  mdat,
  moov,
  mvhd,
  rangeReader,
  toEofBox,
  u32,
} from "./helpers/mp4-builder";

const probe = (data: Buffer, maxReads?: number) => {
  const r = rangeReader(data);
  return {
    r,
    run: () =>
      readMp4DurationSec(
        r.read,
        r.size,
        maxReads === undefined ? undefined : { maxReads },
      ),
  };
};

const codeOf = async (
  p: Promise<unknown>,
): Promise<Mp4ErrorCode | "no-error"> => {
  try {
    await p;
    return "no-error";
  } catch (e) {
    if (e instanceof Mp4Error) return e.code;
    throw e;
  }
};

describe("readMp4DurationSec (REQ-REC-05, A11)", () => {
  test("moov al principio: duracion = duration / timescale (v0)", async () => {
    const data = Buffer.concat([
      ftyp(),
      moov(mvhd({ timescale: 1000, duration: 20_000 })),
      mdat(),
    ]);
    expect((await probe(data).run()).durationSec).toBe(20);
  });

  test("moov al final (tras mdat) tambien se encuentra", async () => {
    const data = Buffer.concat([
      ftyp(),
      free(),
      mdat(5000),
      moov(mvhd({ timescale: 44100, duration: 44100 * 61 })),
    ]);
    expect((await probe(data).run()).durationSec).toBe(61);
  });

  test("mvhd v1 (duracion de 64 bits)", async () => {
    const data = Buffer.concat([
      ftyp(),
      moov(mvhd({ version: 1, timescale: 1000, duration: 121_000n })),
      mdat(),
    ]);
    expect((await probe(data).run()).durationSec).toBe(121);
  });

  test("mvhd precedido de otra caja dentro de moov", async () => {
    const data = Buffer.concat([
      ftyp(),
      moov(box("iods", Buffer.alloc(12)), mvhd({ duration: 3000 })),
      mdat(),
    ]);
    expect((await probe(data).run()).durationSec).toBe(3);
  });

  test("caja top-level con largesize se salta bien", async () => {
    const data = Buffer.concat([
      ftyp(),
      largeBox("mdat", Buffer.alloc(300)),
      moov(mvhd({ duration: 1500 })),
    ]);
    expect((await probe(data).run()).durationSec).toBe(1.5);
  });

  test("devuelve la marca mayor de ftyp", async () => {
    const data = Buffer.concat([
      ftyp("isom"),
      moov(mvhd({ duration: 1000 })),
      mdat(),
    ]);
    expect((await probe(data).run()).brand).toBe("isom");
  });

  test("solo lee rangos pequenos y pocos: no descarga el mdat", async () => {
    const data = Buffer.concat([
      ftyp(),
      mdat(2_000_000),
      moov(mvhd({ duration: 1000 })),
    ]);
    const p = probe(data);
    await p.run();
    expect(p.r.calls.length).toBeLessThanOrEqual(4);
    expect(Math.max(...p.r.calls.map(([, len]) => len))).toBeLessThanOrEqual(
      48,
    );
  });

  test("mvhd v1 con duracion pequena", async () => {
    const data = Buffer.concat([
      ftyp(),
      moov(mvhd({ version: 1, timescale: 1000, duration: 2500n })),
      mdat(),
    ]);
    expect((await probe(data).run()).durationSec).toBe(2.5);
  });

  test("moov con size 0 (hasta el final del fichero) se lee", async () => {
    const data = Buffer.concat([
      ftyp(),
      mdat(),
      toEofBox("moov", mvhd({ duration: 4000 })),
    ]);
    expect((await probe(data).run()).durationSec).toBe(4);
  });

  test("varios moov: gana el primero", async () => {
    const data = Buffer.concat([
      ftyp(),
      moov(mvhd({ duration: 5000 })),
      moov(mvhd({ duration: 99_000 })),
      mdat(),
    ]);
    expect((await probe(data).run()).durationSec).toBe(5);
  });

  test("MP4 fragmentado: mvhd con duracion 0 (el parser la devuelve tal cual; el validador la rechaza)", async () => {
    expect((await probe(fragmentedMp4()).run()).durationSec).toBe(0);
  });

  test("ftyp de solo 12 bytes (marca sin version menor): not_mp4", async () => {
    const data = Buffer.concat([
      ftypBrandOnly(),
      moov(mvhd({ duration: 1000 })),
      mdat(),
    ]);
    expect(await codeOf(probe(data).run())).toBe("not_mp4");
  });

  describe("errores tipados", () => {
    test("ftyp menor de 16 bytes: not_mp4", async () => {
      const data = Buffer.concat([
        box("ftyp", Buffer.from("isom")),
        moov(mvhd({ duration: 1000 })),
        mdat(),
      ]);
      expect(await codeOf(probe(data).run())).toBe("not_mp4");
    });

    test("mdat con size 0 hasta el final y sin moov: no_moov", async () => {
      expect(
        await codeOf(
          probe(
            Buffer.concat([ftyp(), toEofBox("mdat", Buffer.alloc(100))]),
          ).run(),
        ),
      ).toBe("no_moov");
    });

    test("largesize mayor que 2^53: inconsistent", async () => {
      const huge = Buffer.concat([
        u32(1),
        Buffer.from("mdat", "ascii"),
        Buffer.from([0xff, 0, 0, 0, 0, 0, 0, 0]),
      ]);
      expect(
        await codeOf(
          probe(Buffer.concat([ftyp(), huge, Buffer.alloc(64)])).run(),
        ),
      ).toBe("inconsistent");
    });

    test("mvhd v1 con duracion mayor que 2^53: inconsistent", async () => {
      const data = Buffer.concat([
        ftyp(),
        moov(mvhd({ version: 1, duration: 2n ** 60n })),
        mdat(),
      ]);
      expect(await codeOf(probe(data).run())).toBe("inconsistent");
    });

    test("moov mas pequeno que un mvhd completo: inconsistent", async () => {
      const short = Buffer.concat([
        u32(8 + 12),
        Buffer.from("moov", "ascii"),
        u32(12),
        Buffer.from("mvhd", "ascii"),
        u32(0),
      ]);
      expect(
        await codeOf(probe(Buffer.concat([ftyp(), short, mdat()])).run()),
      ).toBe("inconsistent");
    });

    test("lectura corta del origen (objeto cambiado): truncated", async () => {
      const data = Buffer.concat([
        ftyp(),
        moov(mvhd({ duration: 1000 })),
        mdat(),
      ]);
      const r = rangeReader(data);
      const shortRead = async (o: number, l: number) =>
        (await r.read(o, l)).subarray(0, Math.max(0, l - 1));
      expect(await codeOf(readMp4DurationSec(shortRead, r.size))).toBe(
        "truncated",
      );
    });

    test("fichero que no es MP4 (sin ftyp)", async () => {
      expect(
        await codeOf(
          probe(Buffer.from("RIFF....WAVEfmt ".padEnd(64, "x"))).run(),
        ),
      ).toBe("not_mp4");
    });

    test("ftyp no es la primera caja", async () => {
      expect(
        await codeOf(
          probe(Buffer.concat([moov(mvhd({ duration: 1000 })), ftyp()])).run(),
        ),
      ).toBe("not_mp4");
    });

    test("fichero vacio o menor que una cabecera", async () => {
      expect(await codeOf(probe(Buffer.alloc(0)).run())).toBe("not_mp4");
      expect(await codeOf(probe(Buffer.from([0, 0, 0])).run())).toBe("not_mp4");
    });

    test("truncado: moov declarado mas alla del final", async () => {
      const full = Buffer.concat([
        ftyp(),
        mdat(100),
        moov(mvhd({ duration: 1000 })),
      ]);
      expect(
        await codeOf(probe(full.subarray(0, full.length - 10)).run()),
      ).toBe("truncated");
    });

    test("truncado: mdat declarado mas alla del final", async () => {
      const full = Buffer.concat([ftyp(), mdat(1000)]);
      expect(
        await codeOf(probe(full.subarray(0, full.length - 500)).run()),
      ).toBe("truncated");
    });

    test("sin moov: no_moov", async () => {
      expect(await codeOf(probe(Buffer.concat([ftyp(), mdat()])).run())).toBe(
        "no_moov",
      );
    });

    test("moov sin mvhd: no_mvhd", async () => {
      expect(
        await codeOf(
          probe(
            Buffer.concat([
              ftyp(),
              moov(box("iods", Buffer.alloc(12))),
              mdat(),
            ]),
          ).run(),
        ),
      ).toBe("no_mvhd");
    });

    test("tamano de caja menor que su cabecera: inconsistent", async () => {
      const bad = Buffer.concat([
        ftyp(),
        u32(4),
        Buffer.from("moov", "ascii"),
        mdat(),
      ]);
      expect(await codeOf(probe(bad).run())).toBe("inconsistent");
    });

    test("caja hija de moov que excede a moov: inconsistent", async () => {
      const child = mvhd({ duration: 1000 });
      // moov cuya cabecera declara 6 bytes menos de los que ocupa su unico hijo
      const lying = Buffer.concat([
        u32(8 + child.length - 6),
        Buffer.from("moov", "ascii"),
        child,
      ]);
      expect(
        await codeOf(
          probe(Buffer.concat([ftyp(), lying, Buffer.alloc(64)])).run(),
        ),
      ).toBe("inconsistent");
    });

    test("timescale 0: inconsistent", async () => {
      const data = Buffer.concat([
        ftyp(),
        moov(mvhd({ timescale: 0, duration: 1000 })),
        mdat(),
      ]);
      expect(await codeOf(probe(data).run())).toBe("inconsistent");
    });

    test("mvhd con version desconocida: inconsistent", async () => {
      const m = mvhd({ duration: 1000 });
      m[8] = 7; // byte de version
      expect(
        await codeOf(probe(Buffer.concat([ftyp(), moov(m), mdat()])).run()),
      ).toBe("inconsistent");
    });

    test("limite de lecturas: cadena larga de cajas sin moov", async () => {
      const many = Array.from({ length: 50 }, () => free(8));
      const data = Buffer.concat([
        ftyp(),
        ...many,
        moov(mvhd({ duration: 1000 })),
      ]);
      const p = probe(data, 10);
      expect(await codeOf(p.run())).toBe("too_many_reads");
      expect(p.r.calls.length).toBeLessThanOrEqual(10);
    });
  });
});

describe("errores de JS del parser frente a cajas manipuladas (revision fresca de B5a)", () => {
  // Con un lector honesto los tamanos ya se acotan antes de cada lectura; esto es defensa en profundidad: si un tamano
  // manipulado llevara al parser a leer fuera del buffer (RangeError) o a operar sobre algo que no es lo esperado
  // (TypeError), el resultado debe ser un rechazo (`Mp4Error`), no un fallo transitorio que se reintenta durante horas.
  const valid = () => Buffer.concat([ftyp(), moov(mvhd({ timescale: 1000, duration: 20_000 })), mdat()]);

  test.each([
    ["RangeError", () => new RangeError("The value of \"offset\" is out of range")],
    ["TypeError", () => new TypeError("Cannot read properties of undefined")],
  ])("un %s al interpretar una caja -> Mp4Error(inconsistent)", async (_n, err) => {
    const data = valid();
    const { read, size } = rangeReader(data);
    // La 3.a lectura es la de `mvhd`: su buffer revienta al leer los campos, como lo haria una caja con tamanos imposibles.
    let n = 0;
    const crafted = async (o: number, l: number) => {
      const buf = await read(o, l);
      if (++n < 3) return buf;
      return Object.assign(Buffer.from(buf), {
        readUInt32BE: () => {
          throw err();
        },
      });
    };
    expect(await codeOf(readMp4DurationSec(crafted, size))).toBe("inconsistent");
  });

  test.each([
    ["RangeError", () => new RangeError("lector")],
    ["TypeError", () => new TypeError("lector")],
  ])("un %s del LECTOR (Storage) NO se convierte: se propaga para que finalize reintente", async (_n, err) => {
    const e = err();
    await expect(readMp4DurationSec(() => Promise.reject(e), valid().length)).rejects.toBe(e);
  });
});
