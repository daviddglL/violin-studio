import { CURRENT_POLICY_VERSION, LINK_NOTICE_VERSION } from "../../src/config/identity";
import { canRedeem, RedeemInput } from "../../src/teacher/eligibility";

const now = new Date("2026-10-07T12:00:00Z");
const student = { role: "independent", consentStatus: "granted", policyVersion: CURRENT_POLICY_VERSION };
const teacher = {
  role: "teacher",
  birthDate: "1985-03-01",
  consentStatus: "granted",
  policyVersion: CURRENT_POLICY_VERSION,
  studentCount: 3,
};
const base: RedeemInput = { student, teacher, ackVersion: LINK_NOTICE_VERSION, now };
const check = (patch: Partial<RedeemInput>) => canRedeem({ ...base, ...patch });

describe("canRedeem", () => {
  test("alumno independiente y profesor activo con cupo -> ok", () => {
    expect(check({})).toEqual({ ok: true });
  });
  test("profesor con 29 alumnos entra; con 30 esta lleno", () => {
    expect(check({ teacher: { ...teacher, studentCount: 29 } })).toEqual({ ok: true });
    expect(check({ teacher: { ...teacher, studentCount: 30 } })).toEqual({ ok: false, reason: "TEACHER_FULL" });
  });
  test("studentCount ausente cuenta como 0; corrupto falla cerrado", () => {
    expect(check({ teacher: { ...teacher, studentCount: undefined } })).toEqual({ ok: true });
    expect(check({ teacher: { ...teacher, studentCount: "x" } })).toEqual({ ok: false, reason: "TEACHER_FULL" });
  });
  test.each([
    ["sin perfil", undefined],
    ["con borrado en curso", { ...student, deletion: { startedAt: 1 } }],
  ])("alumno %s -> STUDENT_INACTIVE", (_n, s) => {
    expect(check({ student: s })).toEqual({ ok: false, reason: "STUDENT_INACTIVE" });
  });
  test.each([
    ["consentimiento no concedido", { ...student, consentStatus: "parental_pending" }],
    ["version de politica vieja", { ...student, policyVersion: CURRENT_POLICY_VERSION - 1 }],
  ])("alumno con %s -> NO_CONSENT", (_n, s) => {
    expect(check({ student: s })).toEqual({ ok: false, reason: "NO_CONSENT" });
  });
  test("el profesor no puede ser alumno", () => {
    expect(check({ student: { ...student, role: "teacher" } })).toEqual({ ok: false, reason: "STUDENT_IS_TEACHER" });
  });
  test.each([
    ["rol student", { ...student, role: "student" }],
    ["teacherCount 1", { ...student, teacherCount: 1 }],
    ["teacherCount corrupto", { ...student, teacherCount: "1" }],
  ])("alumno ya con profesor (%s) -> ALREADY_LINKED", (_n, s) => {
    expect(check({ student: s })).toEqual({ ok: false, reason: "ALREADY_LINKED" });
  });
  test.each([
    ["inexistente", undefined],
    ["rol independent (revocado)", { ...teacher, role: "independent" }],
    ["con borrado en curso", { ...teacher, deletion: { startedAt: 1 } }],
    ["consentimiento caducado", { ...teacher, policyVersion: CURRENT_POLICY_VERSION - 1 }],
    ["consentimiento no concedido", { ...teacher, consentStatus: "revoked" }],
    ["menor de edad", { ...teacher, birthDate: "2015-01-01" }],
  ])("profesor %s -> TEACHER_UNAVAILABLE", (_n, t) => {
    expect(check({ teacher: t })).toEqual({ ok: false, reason: "TEACHER_UNAVAILABLE" });
  });
  test.each([[0], [LINK_NOTICE_VERSION + 1], [undefined], ["1"], [1.5]])("ackVersion %j -> NOTICE_OUTDATED", (a) => {
    expect(check({ ackVersion: a })).toEqual({ ok: false, reason: "NOTICE_OUTDATED" });
  });
  test("el aviso se evalua antes que el resto", () => {
    expect(check({ ackVersion: 0, student: undefined })).toEqual({ ok: false, reason: "NOTICE_OUTDATED" });
  });
  test("la version de politica se puede inyectar", () => {
    expect(check({ policyVersion: 9, student: { ...student, policyVersion: 9 }, teacher: { ...teacher, policyVersion: 9 } })).toEqual({ ok: true });
  });
});
