import { getAuth } from "firebase-admin/auth";
import { getApps } from "firebase-admin/app";
import { COLLECTIONS } from "../../src/common/collections";
import { eraseUserData } from "../../src/erasure/erase-user-data";
import { bucketStore } from "../../src/recordings/finalize";
import { removeLink } from "../../src/teacher/remove-link";
import { bucket, db, rnd, seedLink, seedUser, wipeFirestore } from "./helpers/finalize-fixtures";

const auth = getAuth(getApps()[0]);
const store = bucketStore(bucket);
const deps = () => ({ db, auth, store, log: () => undefined });
const messages = db.collection(COLLECTIONS.chatMessages);

beforeEach(() => wipeFirestore());

/** Mensaje del chat del par `t`-`s` escrito por `sender`. */
const message = (t: string, s: string, sender = s) =>
  messages.add({ teacherUid: t, studentUid: s, senderUid: sender, text: "hola", createdAt: new Date(), expireAt: new Date() });
const count = async (t: string, s: string) =>
  (await messages.where("teacherUid", "==", t).where("studentUid", "==", s).get()).size;

describe("chat del vinculo (fase 4c)", () => {
  test("removeLink borra los mensajes del par, de los dos autores, y no toca otros chats", async () => {
    const [t, s, other] = [rnd("t"), rnd("s"), rnd("s")];
    await seedUser(t, { role: "teacher" });
    await seedUser(s, { role: "student", teacherCount: 1 });
    await seedLink(t, s);
    await message(t, s, s);
    await message(t, s, t);
    await message(t, other, other);

    await removeLink(deps(), t, s);

    expect(await count(t, s)).toBe(0);
    expect(await count(t, other)).toBe(1);
    // Repetir no encuentra nada que borrar.
    await expect(removeLink(deps(), t, s)).resolves.toBeUndefined();
  });

  test("borrar la cuenta de una parte borra todos sus chats, como profesor y como alumno", async () => {
    const { uid } = await auth.createUser({ email: `chat-${Date.now()}-${Math.random()}@example.com`, password: "Passw0rd!x" });
    await seedUser(uid, { role: "student" });
    const [t, otherT, otherS] = [rnd("t"), rnd("t"), rnd("s")];
    await message(t, uid, uid);
    await message(t, uid, t);
    await message(uid, otherS, otherS); // aunque sea raro, el uid tambien puede aparecer como profesor
    await message(otherT, otherS, otherS);

    const res = await eraseUserData({ db, auth, bucket, store }, uid, { deleteAuth: true });

    expect(res.deleted).toMatchObject({ chatMessages: 3 });
    expect(await count(t, uid)).toBe(0);
    expect(await count(uid, otherS)).toBe(0);
    expect(await count(otherT, otherS)).toBe(1);
  });
});
