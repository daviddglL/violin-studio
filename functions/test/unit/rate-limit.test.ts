import { checkRateLimit } from "../../src/guardian/rate-limit";

const H = 3_600_000;
const NOW = 1_000 * H;

describe("checkRateLimit (ventana deslizante de 24 h, máx. 3)", () => {
  test("permite hasta 3 y devuelve los timestamps incluyendo el nuevo", () => {
    const r = checkRateLimit([NOW - 2 * H, NOW - H], NOW, 3, 24 * H);
    expect(r).toEqual({ allowed: true, sends: [NOW - 2 * H, NOW - H, NOW] });
  });
  test("el 4.º no se permite; retryAfterSeconds = hasta que caduque el más antiguo", () => {
    const r = checkRateLimit([NOW - 10 * H, NOW - 2 * H, NOW - H], NOW, 3, 24 * H);
    expect(r.allowed).toBe(false);
    expect(r.retryAfterSeconds).toBe(14 * 3600);
    expect(r.sends).toEqual([NOW - 10 * H, NOW - 2 * H, NOW - H]);
  });
  test("envíos fuera de la ventana (incluido el borde exacto) no cuentan", () => {
    const r = checkRateLimit([NOW - 24 * H, NOW - 30 * H, NOW - H], NOW, 3, 24 * H);
    expect(r).toEqual({ allowed: true, sends: [NOW - H, NOW] });
  });
});
