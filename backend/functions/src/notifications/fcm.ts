import type { Messaging } from "firebase-admin/messaging";
import { MessagingPort, sendResultOf } from "./notify";

/** Adaptador de FCM: aviso de Android con claves de texto (la app los traduce) y `data.kind` para abrir la pantalla. */
export function fcmMessaging(messaging: Messaging): MessagingPort {
  return {
    async send(tokens, message) {
      const res = await messaging.sendEachForMulticast({
        tokens,
        data: { kind: message.kind },
        android: {
          priority: "normal",
          notification: {
            titleLocKey: message.titleLocKey,
            bodyLocKey: message.bodyLocKey,
            channelId: message.channelId,
            // Un aviso por tipo: varios mensajes seguidos se sustituyen en vez de acumularse.
            tag: message.kind,
          },
        },
      });
      return res.responses.map((r) => (r.success ? "ok" : sendResultOf((r.error as { code?: string } | undefined) ?? {})));
    },
  };
}
