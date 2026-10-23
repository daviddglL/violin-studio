import {
  assignmentRecipient,
  chatRecipient,
  isActiveUser,
  pushMessage,
  reviewRecipient,
  sendResultOf,
} from "../../src/notifications/notify";
import { deviceId, MAX_TOKEN_LENGTH, validToken } from "../../src/notifications/devices";

describe("pushMessage", () => {
  test("solo claves de texto y el tipo: nada de contenido", () => {
    expect(pushMessage("chat")).toEqual({
      kind: "chat",
      titleLocKey: "push_chat_title",
      bodyLocKey: "push_chat_body",
      channelId: "activity",
    });
    expect(pushMessage("review").bodyLocKey).toBe("push_review_body");
    expect(pushMessage("assignment").titleLocKey).toBe("push_assignment_title");
  });
});

describe("sendResultOf", () => {
  test("solo los tokens que FCM da por inexistentes se marcan invalidos", () => {
    expect(sendResultOf(undefined)).toBe("ok");
    expect(sendResultOf({ code: "messaging/registration-token-not-registered" })).toBe("invalid");
    expect(sendResultOf({ code: "messaging/invalid-registration-token" })).toBe("invalid");
    expect(sendResultOf({ code: "messaging/internal-error" })).toBe("error");
    expect(sendResultOf({})).toBe("error");
  });
});

describe("destinatarios", () => {
  test("chat: la otra parte del vinculo", () => {
    expect(chatRecipient({ teacherUid: "t", studentUid: "s", senderUid: "t" })).toBe("s");
    expect(chatRecipient({ teacherUid: "t", studentUid: "s", senderUid: "s" })).toBe("t");
  });

  test("chat: nadie si el autor no es parte o el doc es raro", () => {
    expect(chatRecipient({ teacherUid: "t", studentUid: "s", senderUid: "x" })).toBeUndefined();
    expect(chatRecipient({ teacherUid: "t", studentUid: "t", senderUid: "t" })).toBeUndefined();
    expect(chatRecipient({ teacherUid: 1, studentUid: "s", senderUid: "s" })).toBeUndefined();
    expect(chatRecipient(undefined)).toBeUndefined();
  });

  test("revision: al dueno de la grabacion, salvo las anonimizadas del servidor", () => {
    expect(reviewRecipient("s", { reviewerUid: "t" })).toBe("s");
    expect(reviewRecipient("s", { reviewerUid: null })).toBeUndefined();
    expect(reviewRecipient("s", {})).toBeUndefined();
    expect(reviewRecipient("s", { reviewerUid: "s" })).toBeUndefined();
  });

  test("tarea: al alumno, nunca al profesor que la crea", () => {
    expect(assignmentRecipient("s", { teacherUid: "t" })).toBe("s");
    expect(assignmentRecipient("s", { teacherUid: "s" })).toBeUndefined();
    expect(assignmentRecipient("s", {})).toBeUndefined();
  });
});

describe("isActiveUser", () => {
  test("consentimiento vigente y sin borrado en curso", () => {
    expect(isActiveUser({ consentStatus: "granted" })).toBe(true);
    expect(isActiveUser({ consentStatus: "pending" })).toBe(false);
    expect(isActiveUser({ consentStatus: "granted", deletion: { state: "running" } })).toBe(false);
    expect(isActiveUser(undefined)).toBe(false);
  });
});

describe("validToken", () => {
  test("acepta un token normal y lo recorta", () => {
    expect(validToken("  abc:DEF_123  ")).toBe("abc:DEF_123");
  });

  test("rechaza vacios, espacios internos, demasiado largos y no textos", () => {
    for (const bad of ["", "   ", "a b", "x".repeat(MAX_TOKEN_LENGTH + 1), 42, null, undefined, {}]) {
      expect(() => validToken(bad)).toThrow();
    }
  });

  test("el id del doc es el hash, nunca el token", () => {
    expect(deviceId("abc")).toMatch(/^[0-9a-f]{64}$/);
    expect(deviceId("abc")).not.toContain("abc");
  });
});
