// Opt-in y solo LOCAL (requiere `firebase login`: el emulador de extensiones la descarga del registro; el CI no la ejecuta).
// Escribe en mail/ y espera a que la extension anote `delivery` (SMTP de prueba inalcanzable => ERROR).
const admin = require("firebase-admin");

admin.initializeApp({ projectId: "demo-violin-studio" });
const db = admin.firestore();

(async () => {
  const ref = await db.collection("mail").add({
    to: "tutor@example.com",
    message: { subject: "smoke", text: "smoke" },
  });
  for (let i = 0; i < 60; i++) {
    const delivery = (await ref.get()).get("delivery");
    if (delivery && ["SUCCESS", "ERROR"].includes(delivery.state)) {
      console.log("delivery.state =", delivery.state);
      process.exit(0);
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  console.error("la extension no proceso el documento mail/ en 60 s");
  process.exit(1);
})();
