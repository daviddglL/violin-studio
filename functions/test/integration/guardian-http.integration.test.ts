import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { REGION } from "../../src/config/runtime";
import { requestGuardianConsentHandler } from "../../src/guardian/request";

// E2E por HTTP real: emulador de functions (URL directa) y de hosting (rewrite /tutor). Requiere `npm run build`.
const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const app = getApps()[0] ?? initializeApp({ projectId: project, storageBucket: `${project}.appspot.com` });
const auth = getAuth(app);
const db = getFirestore(app);
const FN = `http://127.0.0.1:5001/${project}/${REGION}/guardianConsent`;
const HOSTING = "http://127.0.0.1:5000/tutor";
const urlencoded = (o: Record<string, string>) => ({
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams(o).toString(),
});

async function setup() {
  const email = `e2e-${Date.now()}-${Math.random()}@example.com`;
  const { uid } = await auth.createUser({ email, password: "Passw0rd!x" });
  await db.collection("users").doc(uid).set({ displayName: "Ana", locale: "es", isMinor: true, consentStatus: "pending", policyVersion: null });
  await requestGuardianConsentHandler(
    { db, pepper: "t".repeat(32), linkBaseUrl: "https://x.app", guardianFlowEnabled: true, log: () => undefined },
    uid, email, { guardianEmail: `g-${Date.now()}-${Math.random()}@example.com` },
  );
  const [mail] = (await db.collection("mail").where("uid", "==", uid).get()).docs;
  const [, r, t] = mail.data().message.text.match(/\/tutor\?r=(\S+)#t=([\w-]+)/)!;
  return { uid, r: r as string, t: t as string };
}

test.each([["functions", FN], ["hosting", HOSTING]])("%s: GET sin token en el HTML y POST accept real -> granted + claims", async (_n, base) => {
  const s = await setup();
  const get = await fetch(`${base}?r=${s.r}`);
  const html = await get.text();
  expect(get.status).toBe(200);
  expect(html).toContain('name="declaration"');
  expect(html).not.toContain(s.t);
  expect(get.headers.get("cache-control")).toContain("no-store");
  expect(get.headers.get("x-frame-options")).toBe("DENY");
  expect(get.headers.get("content-security-policy")).toContain("form-action 'self'");
  expect((await db.collection("users").doc(s.uid).get()).data()?.consentStatus).toBe("parental_pending");

  const post = await fetch(base, urlencoded({ r: s.r, t: s.t, action: "accept", declaration: "on" }));
  expect(post.status).toBe(200);
  expect((await db.collection("users").doc(s.uid).get()).data()?.consentStatus).toBe("granted");
  expect((await auth.getUser(s.uid)).customClaims?.consentOk).toBe(true);
  const again = await fetch(base, urlencoded({ r: s.r, t: s.t, action: "accept", declaration: "on" }));
  expect(again.status).toBe(404);
});

test("cuerpo JSON y métodos no permitidos: aceptan JSON, 405 sin efectos, nunca 500", async () => {
  const s = await setup();
  const put = await fetch(FN, { method: "PUT", body: "x" });
  expect([put.status, put.headers.get("allow")]).toEqual([405, "GET, HEAD, POST"]);
  const basura = await fetch(FN, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{no-json" });
  expect(basura.status).toBeLessThan(500);
  const json = await fetch(FN, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ r: s.r, t: s.t, action: "accept", declaration: "on" }),
  });
  expect(json.status).toBe(200);
});

test("hosting: rechazo real en dos pasos -> Auth, perfil, solicitud y mail del menor desaparecen", async () => {
  const s = await setup();
  const otro = await setup();
  const paso1 = await fetch(HOSTING, urlencoded({ r: s.r, t: s.t, action: "reject" }));
  expect(paso1.status).toBe(200);
  expect(await paso1.text()).toContain('name="confirm" value="yes"');
  expect((await auth.getUser(s.uid)).uid).toBe(s.uid); // nada destructivo todavía
  const paso2 = await fetch(HOSTING, urlencoded({ r: s.r, t: s.t, action: "reject", confirm: "yes" }));
  expect(paso2.status).toBe(200);
  await expect(auth.getUser(s.uid)).rejects.toMatchObject({ code: "auth/user-not-found" });
  expect((await db.collection("users").doc(s.uid).get()).exists).toBe(false);
  expect((await db.collection("guardianRequests").where("uid", "==", s.uid).get()).size).toBe(0);
  expect((await db.collection("mail").where("uid", "==", s.uid).get()).size).toBe(0);
  expect((await auth.getUser(otro.uid)).uid).toBe(otro.uid);
});

test("hosting: aceptar y luego revocar con el enlace del segundo correo -> revoked, consentOk=false, token de un solo uso", async () => {
  const s = await setup();
  const acc = await fetch(HOSTING, urlencoded({ r: s.r, t: s.t, action: "accept", declaration: "on" }));
  expect(acc.status).toBe(200);
  const mail = (await db.collection("mail").where("uid", "==", s.uid).where("kind", "==", "guardian_revoke").get()).docs[0].data();
  const link = mail.message.text.match(/(https?:\/\/\S+\/tutor\?r=\S+#t=[\w-]+&a=revoke)/)![1];
  expect(link).toContain(`/tutor?r=${s.r}#t=`);
  const rev = link.match(/#t=([\w-]+)&a=revoke/)![1];

  const page = await fetch(`${HOSTING}?r=${s.r}`); // el fragmento nunca llega al servidor
  const html = await page.text();
  expect(page.status).toBe(200);
  expect(html).toContain('name="action" value="revoke"');
  expect(html).not.toContain(rev);
  expect((await db.collection("users").doc(s.uid).get()).data()?.consentStatus).toBe("granted");
  expect((await auth.getUser(s.uid)).customClaims?.consentOk).toBe(true);

  expect((await fetch(HOSTING, urlencoded({ r: s.r, t: s.t, action: "revoke" }))).status).toBe(404); // el token de aceptación no revoca
  const res = await fetch(HOSTING, urlencoded({ r: s.r, t: rev, action: "revoke" }));
  expect(res.status).toBe(200);
  expect((await db.collection("users").doc(s.uid).get()).data()?.consentStatus).toBe("revoked");
  expect((await auth.getUser(s.uid)).customClaims?.consentOk).toBe(false);
  expect((await fetch(HOSTING, urlencoded({ r: s.r, t: rev, action: "revoke" }))).status).toBe(404);
  expect((await fetch(`${HOSTING}?r=${s.r}`)).status).toBe(404);
});
