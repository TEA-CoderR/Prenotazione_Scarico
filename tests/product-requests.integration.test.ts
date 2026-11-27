import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { seed } from "@/db/seed";
import { BookingError } from "@/lib/booking/errors";
import { answerRequest, askRequest, erpRequests, requestPhoto, storeAction, storeRequests } from "@/lib/product-requests";
import { currentList, putLines } from "@/lib/store-lists";

const url = process.env.TEST_DATABASE_URL;
const NOW = new Date("2026-11-27T11:00:00Z");
const foiano = { name: "TC22 FOIANO 1", entityCode: 1 };
const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(200, 7)]).toString("base64");
const ask = { ean: "08001480000017", name: "", kind: "unknown", reason: "customers", boxes: 2, note: "I clienti lo chiedono spesso" };

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe.skipIf(!url)("product requests (PostgreSQL)", () => {
  let db: Db;
  let close: () => Promise<void>;

  beforeAll(async () => {
    const c = createDb(url!);
    db = c.db;
    close = () => c.pool.end();
    await db.execute(sql`drop schema if exists public cascade`);
    await db.execute(sql`drop schema if exists drizzle cascade`);
    await db.execute(sql`create schema public`);
    await runMigrations(db);
    await seed(db, { holidayYears: [2026] });
  });

  beforeEach(async () => {
    await db.execute(sql`truncate product_request, store_list, store_list_line, app_setting restart identity cascade`);
  });

  afterAll(async () => close());

  it("one open request per store and item; 'other' needs a note; photos must be JPEG", async () => {
    await expectCode(askRequest(db, foiano, { ...ask, reason: "other", note: "" }, NOW), "VALIDATION");
    await expectCode(askRequest(db, foiano, { ...ask, photo: Buffer.alloc(300, 1).toString("base64") }, NOW), "VALIDATION");
    await expectCode(askRequest(db, { name: "x", entityCode: null }, ask, NOW), "FORBIDDEN");
    const r = await askRequest(db, foiano, { ...ask, photo: jpeg }, NOW);
    expect(r).toMatchObject({ ean: "8001480000017", status: "open", boxes: 2, hasPhoto: true, device: "TC22 FOIANO 1" });
    const r2 = await askRequest(db, foiano, { ...ask, boxes: 3 }, NOW);
    expect(r2.id).toBe(r.id);
    expect(r2.hasPhoto).toBe(true); // asking again without a photo keeps the old one
    expect((await storeRequests(db, 1, NOW)).map((x) => x.boxes)).toEqual([3]);
    expect(await storeRequests(db, 2, NOW)).toEqual([]);
    expect((await requestPhoto(db, r.id))?.[0]).toBe(0xff);
    expect(await erpRequests(db, "open", NOW)).toHaveLength(1);
  });

  it("'use another item' with boxes goes into this week's list; undo puts the list back", async () => {
    await putLines(db, foiano, { lines: [{ ean: "8001090000001", name: "SVELTO", boxes: 1, kind: "griglia" }] }, NOW);
    const r = await askRequest(db, foiano, ask, NOW);
    await expectCode(answerRequest(db, r.id, { action: "answer", kind: "use", answer: "Usa SVELTO" }, NOW), "VALIDATION");
    await expectCode(answerRequest(db, r.id, { action: "answer", kind: "no", answer: "x", boxes: 2 }, NOW), "VALIDATION");
    const a = await answerRequest(
      db,
      r.id,
      { action: "answer", kind: "use", answer: "Usa SVELTO", ean: "8001090000001", name: "SVELTO", boxes: 2, by: "Marco" },
      NOW,
    );
    expect(a).toMatchObject({ status: "answered", answerKind: "use", addedToList: true, handledBy: "Marco" });
    let lines = (await currentList(db, 1, NOW)).list!.lines;
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ ean: "8001090000001", boxes: 2, origEan: "8001480000017" });
    await expectCode(answerRequest(db, r.id, { action: "answer", kind: "no", answer: "x" }, NOW), "CONFLICT");
    const back = await answerRequest(db, r.id, { action: "reopen" }, NOW);
    expect(back).toMatchObject({ status: "open", answerKind: null, addedToList: false });
    lines = (await currentList(db, 1, NOW)).list!.lines;
    expect(lines[0].boxes).toBe(1);
  });

  it("'once' adds a new line (removed on undo); 'info' waits for the store's reply; seen and cancel", async () => {
    const r = await askRequest(db, foiano, ask, NOW);
    await answerRequest(db, r.id, { action: "answer", kind: "once", answer: "Ordinato questa settimana", boxes: 2 }, NOW);
    expect((await currentList(db, 1, NOW)).list!.lines[0]).toMatchObject({ ean: "8001480000017", kind: "other", boxes: 2 });
    await answerRequest(db, r.id, { action: "reopen" }, NOW);
    expect((await currentList(db, 1, NOW)).list!.lines).toHaveLength(0);

    await expectCode(storeAction(db, foiano, r.id, { action: "reply", note: "x" }, NOW), "CONFLICT");
    const i = await answerRequest(db, r.id, { action: "answer", kind: "info", answer: "Manda una foto" }, NOW);
    expect(i.status).toBe("info");
    await expectCode(storeAction(db, foiano, r.id, { action: "reply" }, NOW), "VALIDATION");
    await expectCode(storeAction(db, { name: "y", entityCode: 2 }, r.id, { action: "seen" }, NOW), "NOT_FOUND");
    const rep = await storeAction(db, foiano, r.id, { action: "reply", note: "È il nuovo formato", photo: jpeg }, NOW);
    expect(rep).toMatchObject({ status: "open", storeReply: "È il nuovo formato", hasPhoto: true });

    const n = await answerRequest(db, r.id, { action: "answer", kind: "no", answer: "Non lo trattiamo" }, NOW);
    expect(n.seenAt).toBeNull();
    expect((await storeAction(db, foiano, r.id, { action: "seen" }, NOW)).seenAt).not.toBeNull();
    await expectCode(storeAction(db, foiano, r.id, { action: "cancel" }, NOW), "CONFLICT");

    const c = await askRequest(db, foiano, { ...ask, ean: "123456" }, NOW);
    expect((await storeAction(db, foiano, c.id, { action: "cancel" }, NOW)).status).toBe("cancelled");
    expect((await storeRequests(db, 1, NOW)).map((x) => x.ean)).toEqual(["8001480000017"]);
  });

  it("keeps the whole conversation (store ⇄ office); undo takes the answer's message back", async () => {
    const r = await askRequest(db, foiano, { ...ask, reason: "other", note: "Finito da 2 settimane" }, NOW);
    expect(r.messages).toEqual([{ by: "store", text: "Finito da 2 settimane", at: NOW.toISOString(), boxes: 2 }]);
    await answerRequest(db, r.id, { action: "answer", kind: "info", answer: "È il formato da 6?" }, NOW);
    await storeAction(db, foiano, r.id, { action: "reply", note: "Sì, da 6", photo: jpeg }, NOW);
    const done = await answerRequest(db, r.id, { action: "answer", kind: "no", answer: "Non lo trattiamo" }, NOW);
    expect(done.messages.map((m) => [m.by, m.text])).toEqual([
      ["store", "Finito da 2 settimane"], ["office", "È il formato da 6?"], ["store", "Sì, da 6"], ["office", "Non lo trattiamo"]]);
    expect(done.messages[2].photo).toBe(true);
    const back = await answerRequest(db, r.id, { action: "reopen" }, NOW);
    expect(back.messages.map((m) => m.text)).toEqual(["Finito da 2 settimane", "È il formato da 6?", "Sì, da 6"]);
    // asking again while open adds the new message
    const again = await askRequest(db, foiano, { ...ask, reason: "other", note: "Ne servono 3 colli", boxes: 3 }, NOW);
    expect(again.messages).toHaveLength(4);
    expect(again.messages[3]).toMatchObject({ by: "store", text: "Ne servono 3 colli", boxes: 3 });
  });
});
