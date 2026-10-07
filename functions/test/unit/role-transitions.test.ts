import { isAllowedRoleTransition, ROLES, Role } from "../../src/teacher/role-transitions";
import { ALLOWED_ROLES } from "../../src/identity/claims";

// REQ-PRF-T02: unicas transiciones permitidas; toda otra combinacion distinta de la identidad se rechaza.
const PERMITIDAS: Array<[Role, Role]> = [
  ["independent", "student"],
  ["student", "independent"],
  ["independent", "teacher"],
  ["teacher", "independent"],
];

describe("transiciones de rol", () => {
  test.each(PERMITIDAS)("%s -> %s permitida", (from, to) => {
    expect(isAllowedRoleTransition(from, to)).toBe(true);
  });
  test("teacher nunca pasa a student ni viceversa", () => {
    expect(isAllowedRoleTransition("teacher", "student")).toBe(false);
    expect(isAllowedRoleTransition("student", "teacher")).toBe(false);
  });
  test("tabla completa: solo las 4 permitidas", () => {
    const permitidas = ROLES.flatMap((a) => ROLES.map((b) => [a, b] as [Role, Role])).filter(([a, b]) =>
      isAllowedRoleTransition(a, b),
    );
    expect(permitidas).toEqual(expect.arrayContaining(PERMITIDAS));
    expect(permitidas).toHaveLength(PERMITIDAS.length);
  });
  test("misma->misma no es transicion", () => {
    for (const r of ROLES) expect(isAllowedRoleTransition(r, r)).toBe(false);
  });
});

test("S6: claims.ts comparte la unica fuente de roles", () => {
  expect(ALLOWED_ROLES).toBe(ROLES);
});
