import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

// Spike 1a.1: el emulador de Auth dispara onDelete (funciones v1) al borrar un usuario con Admin SDK.
const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project });

async function esperar<T>(leer: () => Promise<T | undefined>, timeoutMs = 10_000): Promise<T | undefined> {
  const limite = Date.now() + timeoutMs;
  while (Date.now() < limite) {
    const valor = await leer();
    if (valor !== undefined) return valor;
    await new Promise((r) => setTimeout(r, 250));
  }
  return undefined;
}

test("borrar un usuario en el emulador de Auth ejecuta onUserDeleted", async () => {
  const auth = getAuth(app);
  const db = getFirestore(app);
  const user = await auth.createUser({ email: `spike-${Date.now()}@example.com`, password: "Passw0rd!x" });
  await auth.deleteUser(user.uid);
  const marcador = await esperar(async () => {
    const snap = await db.collection("spikeMarkers").doc(user.uid).get();
    return snap.exists ? snap.data() : undefined;
  });
  expect(marcador).toEqual({ deleted: true });
}, 20_000);
