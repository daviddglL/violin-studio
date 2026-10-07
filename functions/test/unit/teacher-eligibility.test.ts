import { CURRENT_POLICY_VERSION } from "../../src/config/identity";
import { canGrantTeacher } from "../../src/teacher/eligibility";

const NOW = new Date("2026-10-07T12:00:00Z");
const ok = {
  role: "independent",
  birthDate: "1996-05-10",
  consentStatus: "granted",
  policyVersion: CURRENT_POLICY_VERSION,
  teacherCount: 0,
};
const check = (profile: Record<string, unknown> | undefined, emailVerified = true, policy?: number) =>
  canGrantTeacher({ profile, emailVerified, now: NOW, policyVersion: policy });

describe("canGrantTeacher", () => {
  test("adulto verificado y vigente -> elegible", () => {
    expect(check(ok)).toEqual({ eligible: true, alreadyTeacher: false });
  });
  test("sin perfil -> NO_PROFILE", () => {
    expect(check(undefined)).toEqual({ eligible: false, reason: "NO_PROFILE" });
  });
  test("17 años y 364 días rechazado; 18 hoy aceptado", () => {
    expect(check({ ...ok, birthDate: "2008-10-08" })).toEqual({ eligible: false, reason: "NOT_ADULT" });
    expect(check({ ...ok, birthDate: "2008-10-07" })).toEqual({ eligible: true, alreadyTeacher: false });
  });
  test.each([[undefined], [null], ["nope"], ["2030-01-01"], [42]])("birthDate ilegible %p -> NOT_ADULT", (b) => {
    expect(check({ ...ok, birthDate: b })).toEqual({ eligible: false, reason: "NOT_ADULT" });
  });
  test("email sin verificar", () => {
    expect(check(ok, false)).toEqual({ eligible: false, reason: "EMAIL_NOT_VERIFIED" });
  });
  test.each(["pending", "parental_pending", "revoked", undefined])("consentimiento %p no vigente", (s) => {
    expect(check({ ...ok, consentStatus: s })).toEqual({ eligible: false, reason: "CONSENT_NOT_CURRENT" });
  });
  test("versión de política vieja o nula", () => {
    expect(check({ ...ok, policyVersion: CURRENT_POLICY_VERSION - 1 })).toEqual({
      eligible: false,
      reason: "CONSENT_NOT_CURRENT",
    });
    expect(check({ ...ok, policyVersion: null })).toEqual({ eligible: false, reason: "CONSENT_NOT_CURRENT" });
  });
  test("versión vigente inyectada", () => {
    expect(check({ ...ok, policyVersion: 2 }, true, 2)).toEqual({ eligible: true, alreadyTeacher: false });
  });
  test("borrado en curso", () => {
    expect(check({ ...ok, deletion: { state: "in_progress" } })).toEqual({ eligible: false, reason: "DELETION_IN_PROGRESS" });
  });
  test("con vínculos como alumno", () => {
    expect(check({ ...ok, role: "student", teacherCount: 1 })).toEqual({ eligible: false, reason: "HAS_TEACHER_LINKS" });
  });
  test("teacherCount ausente cuenta como 0", () => {
    const { teacherCount: _omit, ...sinContador } = ok;
    void _omit;
    expect(check(sinContador)).toEqual({ eligible: true, alreadyTeacher: false });
  });
  test("ya profesor -> idempotente", () => {
    expect(check({ ...ok, role: "teacher" })).toEqual({ eligible: true, alreadyTeacher: true });
  });
});
