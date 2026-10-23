import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { MAX_DEVICES, registerDeviceHandler, unregisterDeviceHandler, deviceId } from "../../src/notifications/devices";
import { MessagingPort, notifyUser, PushMessage, SendResult } from "../../src/notifications/notify";

const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });
const db = getFirestore(app);

const rnd = (p: string) => `${p}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
const logs: Array<{ message: string; data: Record<string, unknown> }> = [];
const deps = { db, log: (message: string, data: Record<string, unknown>) => logs.push({ message, data }) };
const devices = (uid: string) => db.collection("users").doc(uid).collection("devices");

async function usuario(extra: Record<string, unknown> = {}) {
  const uid = rnd("n");
  await db.collection("users").doc(uid).set({ consentStatus: "granted", role: "independent", ...extra });
  return uid;
}

/** FCM falso: guarda lo enviado y responde lo que diga `results` (por token). */
function fakeMessaging(results: (token: string) => SendResult = () => "ok") {
  const sent: Array<{ tokens: string[]; message: PushMessage }> = [];
  const port: MessagingPort = {
    async send(tokens, message) {
      sent.push({ tokens, message });
      return tokens.map(results);
    },
  };
  return { port, sent };
}

describe("registerDevice / unregisterDevice", () => {
  test("guarda el token bajo su hash y es idempotente", async () => {
    const uid = await usuario();
    await registerDeviceHandler(deps, uid, "tok-1");
    await registerDeviceHandler(deps, uid, " tok-1 ");
    const snap = await devices(uid).get();
    expect(snap.docs.map((d) => d.id)).toEqual([deviceId("tok-1")]);
    expect(snap.docs[0].data()).toMatchObject({ token: "tok-1", platform: "android" });
  });

  test("un token que ya tenia otra cuenta se le quita (dispositivo compartido)", async () => {
    const a = await usuario();
    const b = await usuario();
    const token = rnd("shared-");
    await registerDeviceHandler(deps, a, token);
    await registerDeviceHandler(deps, b, token);
    expect((await devices(a).get()).size).toBe(0);
    expect((await devices(b).get()).size).toBe(1);
  });

  test(`como mucho ${MAX_DEVICES} dispositivos: se retiran los mas antiguos`, async () => {
    const uid = await usuario();
    for (let i = 0; i < MAX_DEVICES; i++) {
      await devices(uid).doc(`old${i}`).set({ token: `old-${uid}-${i}`, updatedAt: Timestamp.fromMillis(1000 + i) });
    }
    await registerDeviceHandler(deps, uid, `nuevo-${uid}`);
    const ids = (await devices(uid).get()).docs.map((d) => d.id);
    expect(ids).toHaveLength(MAX_DEVICES);
    expect(ids).toContain(deviceId(`nuevo-${uid}`));
    expect(ids).not.toContain("old0");
  });

  test("sin doc de usuario o con el borrado en curso no registra", async () => {
    await expect(registerDeviceHandler(deps, rnd("nadie"), "t")).rejects.toThrow();
    const borrando = await usuario({ deletion: { state: "running" } });
    await expect(registerDeviceHandler(deps, borrando, "t2")).rejects.toThrow();
    expect((await devices(borrando).get()).size).toBe(0);
  });

  test("unregister borra el suyo y no falla si no estaba", async () => {
    const uid = await usuario();
    await registerDeviceHandler(deps, uid, "tok-x");
    await unregisterDeviceHandler(deps, uid, "tok-x");
    await unregisterDeviceHandler(deps, uid, "tok-x");
    expect((await devices(uid).get()).size).toBe(0);
  });
});

describe("notifyUser", () => {
  const on = async () => true;

  test("envia a todos sus dispositivos solo claves de texto, y borra los tokens invalidos", async () => {
    const uid = await usuario();
    await registerDeviceHandler(deps, uid, `vivo-${uid}`);
    await registerDeviceHandler(deps, uid, `muerto-${uid}`);
    const { port, sent } = fakeMessaging((t) => (t.startsWith("muerto") ? "invalid" : "ok"));
    expect(await notifyUser({ db, messaging: port, enabled: on, log: deps.log }, uid, "chat")).toBe("sent");
    expect(sent).toHaveLength(1);
    expect(sent[0].tokens.sort()).toEqual([`muerto-${uid}`, `vivo-${uid}`]);
    expect(sent[0].message).toEqual({ kind: "chat", titleLocKey: "push_chat_title", bodyLocKey: "push_chat_body", channelId: "activity" });
    expect((await devices(uid).get()).docs.map((d) => d.get("token"))).toEqual([`vivo-${uid}`]);
  });

  test("un fallo transitorio de un token no lo borra", async () => {
    const uid = await usuario();
    await registerDeviceHandler(deps, uid, `t-${uid}`);
    const { port } = fakeMessaging(() => "error");
    await notifyUser({ db, messaging: port, enabled: on }, uid, "review");
    expect((await devices(uid).get()).size).toBe(1);
  });

  test("con el interruptor apagado, la cuenta inactiva o sin dispositivos no envia nada", async () => {
    const { port, sent } = fakeMessaging();
    const uid = await usuario();
    await registerDeviceHandler(deps, uid, `a-${uid}`);
    expect(await notifyUser({ db, messaging: port, enabled: async () => false }, uid, "chat")).toBe("disabled");

    const pendiente = await usuario();
    await registerDeviceHandler(deps, pendiente, `p-${pendiente}`);
    await db.collection("users").doc(pendiente).update({ consentStatus: "pending" });
    expect(await notifyUser({ db, messaging: port, enabled: on }, pendiente, "assignment")).toBe("inactive");

    expect(await notifyUser({ db, messaging: port, enabled: on }, await usuario(), "chat")).toBe("no_devices");
    expect(sent).toHaveLength(0);
  });

  test("los logs no llevan uid ni token", async () => {
    const uid = await usuario();
    const token = `secreto-${uid}`;
    await registerDeviceHandler(deps, uid, token);
    await notifyUser({ db, messaging: fakeMessaging().port, enabled: on, log: deps.log }, uid, "chat");
    const dump = JSON.stringify(logs);
    expect(dump).not.toContain(uid);
    expect(dump).not.toContain(token);
  });
});
