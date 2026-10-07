import { CODE_ALPHABET, CODE_LENGTH, generateCode, hashCode, normalizeCode } from "../../src/teacher/codes";

const PEPPER = "p".repeat(32);

describe("generateCode", () => {
  test("alfabeto de 31 caracteres sin ambiguos", () => {
    expect(CODE_ALPHABET).toBe("ABCDEFGHJKMNPQRSTUVWXYZ23456789");
    expect(CODE_ALPHABET).toHaveLength(31);
    expect(CODE_ALPHABET).not.toMatch(/[0OIL1]/);
  });
  test("10 000 codigos: 8 caracteres del alfabeto, ninguno ambiguo", () => {
    for (let i = 0; i < 10_000; i++) {
      const c = generateCode();
      expect(c).toHaveLength(CODE_LENGTH);
      expect(c).toMatch(/^[A-HJKMNP-Z2-9]{8}$/);
    }
  });
  test("distribucion basica: todos los caracteres aparecen y ninguno domina", () => {
    const counts = new Map<string, number>();
    const n = 20_000;
    for (let i = 0; i < n; i++) for (const ch of generateCode()) counts.set(ch, (counts.get(ch) ?? 0) + 1);
    expect(counts.size).toBe(31);
    const esperado = (n * CODE_LENGTH) / 31;
    for (const v of counts.values()) expect(Math.abs(v - esperado) / esperado).toBeLessThan(0.1);
  });
  test("usa la fuente aleatoria inyectada (indice uniforme en [0,31))", () => {
    const pedidos: number[] = [];
    const fuente = (max: number) => {
      pedidos.push(max);
      return 0;
    };
    expect(generateCode(fuente)).toBe("AAAAAAAA");
    expect(pedidos).toEqual(Array(8).fill(31));
  });
  test("dos codigos consecutivos difieren", () => {
    expect(generateCode()).not.toBe(generateCode());
  });
});

describe("hashCode", () => {
  test("determinista y hex de 64", () => {
    expect(hashCode(PEPPER, "ABCDEFGH")).toBe(hashCode(PEPPER, "ABCDEFGH"));
    expect(hashCode(PEPPER, "ABCDEFGH")).toMatch(/^[0-9a-f]{64}$/);
  });
  test("distinto pepper -> distinto hash; distinto codigo -> distinto hash", () => {
    expect(hashCode(PEPPER, "ABCDEFGH")).not.toBe(hashCode("q".repeat(32), "ABCDEFGH"));
    expect(hashCode(PEPPER, "ABCDEFGH")).not.toBe(hashCode(PEPPER, "ABCDEFGJ"));
  });
  test("normaliza mayusculas y espacios antes de hashear", () => {
    expect(hashCode(PEPPER, " abcdefgh ")).toBe(hashCode(PEPPER, "ABCDEFGH"));
    expect(normalizeCode(" ab-cd efgh ")).toBe("ABCDEFGH");
  });
  test("pepper corto se rechaza", () => {
    expect(() => hashCode("corto", "ABCDEFGH")).toThrow(/pepper/i);
  });
});
