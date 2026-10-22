import { Timestamp } from "firebase-admin/firestore";
import { decideTeacherRequest, TEACHER_REQUEST_COOLDOWN_DAYS } from "../../src/teacher/teacher-request";

const NOW = new Date("2026-10-22T12:00:00Z");
const DAY_MS = 86_400_000;
const eligible = { eligible: true as const, alreadyTeacher: false };
const daysAgo = (d: number) => Timestamp.fromMillis(NOW.getTime() - d * DAY_MS);

describe("decideTeacherRequest", () => {
  test("no elegible -> deny con el motivo, aunque haya una solicitud", () => {
    expect(decideTeacherRequest({ eligible: false, reason: "NOT_ADULT" }, { status: "pending" }, NOW)).toEqual({
      kind: "deny",
      reason: "NOT_ADULT",
    });
  });

  test("ya profesor -> approved sin escribir", () => {
    expect(decideTeacherRequest({ eligible: true, alreadyTeacher: true }, undefined, NOW)).toEqual({
      kind: "keep",
      status: "approved",
    });
  });

  test("sin solicitud -> se crea", () => {
    expect(decideTeacherRequest(eligible, undefined, NOW)).toEqual({ kind: "create" });
  });

  test("pendiente -> se mantiene (idempotente)", () => {
    expect(decideTeacherRequest(eligible, { status: "pending" }, NOW)).toEqual({ kind: "keep", status: "pending" });
  });

  test("rechazada hace menos del plazo -> rejected sin reescribir", () => {
    const req = { status: "rejected", decidedAt: daysAgo(TEACHER_REQUEST_COOLDOWN_DAYS - 1) };
    expect(decideTeacherRequest(eligible, req, NOW)).toEqual({ kind: "keep", status: "rejected" });
  });

  test("rechazada hace mas del plazo -> se puede volver a pedir", () => {
    const req = { status: "rejected", decidedAt: daysAgo(TEACHER_REQUEST_COOLDOWN_DAYS + 1) };
    expect(decideTeacherRequest(eligible, req, NOW)).toEqual({ kind: "create" });
  });

  test("rechazada sin fecha legible -> cuenta como reciente", () => {
    expect(decideTeacherRequest(eligible, { status: "rejected", decidedAt: "ayer" }, NOW)).toEqual({
      kind: "keep",
      status: "rejected",
    });
  });

  test("aprobada pero ya no es profesor (baja posterior) -> se puede volver a pedir", () => {
    expect(decideTeacherRequest(eligible, { status: "approved" }, NOW)).toEqual({ kind: "create" });
  });
});
