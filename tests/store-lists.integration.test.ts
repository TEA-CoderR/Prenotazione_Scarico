import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { seed } from "@/db/seed";
import { BookingError } from "@/lib/booking/errors";
import {
  currentList,
  erpLists,
  markReceived,
  nextDeadline,
  putLines,
  setFeedback,
  setRule,
  submitList,
} from "@/lib/store-lists";

const url = process.env.TEST_DATABASE_URL;
// Saturday 21/11/2026 12:00 Rome
const SAT = new Date("2026-11-21T11:00:00Z");
const foiano = { name: "TC22 FOIANO 1", entityCode: 1 };
const foiano2 = { name: "TC22 FOIANO 2", entityCode: 1 };

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe("weekly deadline", () => {
  it("is the next given weekday and time in Rome", () => {
    const r = { day: 7, time: "10:00" };
    expect(nextDeadline(SAT, r)).toEqual({ week: "2026-11-22", deadline: new Date("2026-11-22T09:00:00Z") });
    // Sunday 10:00 exactly → next week's list
    expect(nextDeadline(new Date("2026-11-22T09:00:00Z"), r).week).toBe("2026-11-29");
    expect(nextDeadline(new Date("2026-11-22T08:59:00Z"), r).week).toBe("2026-11-22");
    // summer time: Sunday 10:00 = 08:00 UTC
    expect(nextDeadline(new Date("2026-06-20T10:00:00Z"), r).deadline).toEqual(new Date("2026-06-21T08:00:00Z"));
    expect(nextDeadline(SAT, { day: 3, time: "12:00" }).week).toBe("2026-11-25");
  });
});

describe.skipIf(!url)("store lists (PostgreSQL)", () => {
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
    await db.execute(sql`truncate store_list, store_list_line, app_setting restart identity cascade`);
  });

  afterAll(async () => close());

  it("is one list per store shared by its scanners, editable until the deadline", async () => {
    expect((await currentList(db, 1, SAT)).list).toBeNull();
    await putLines(db, foiano, { lines: [{ ean: "08015100572212", name: "DIXAN 40", boxes: 2 }] }, SAT);
    const v = await putLines(db, foiano2, { lines: [{ ean: "8000036022017", name: "AMUCHINA", boxes: 1, kind: "urgent", reason: "Scaffale vuoto" }] }, SAT);
    expect(v.week).toBe("2026-11-22");
    expect(v.list?.lines.map((l) => [l.ean, l.boxes, l.kind, l.updatedBy])).toEqual([
      ["8015100572212", 2, "griglia", "TC22 FOIANO 1"],
      ["8000036022017", 1, "urgent", "TC22 FOIANO 2"],
    ]);
    // the other store sees nothing
    expect((await currentList(db, 2, SAT)).list).toBeNull();
    // set to 3, then 0 removes it
    await putLines(db, foiano, { lines: [{ ean: "8015100572212", boxes: 3 }] }, SAT);
    expect((await currentList(db, 1, SAT)).list?.lines.find((l) => l.ean === "8015100572212")?.boxes).toBe(3);
    await putLines(db, foiano, { lines: [{ ean: "8015100572212", boxes: 0 }] }, SAT);
    expect((await currentList(db, 1, SAT)).list?.lines).toHaveLength(1);
    // urgent needs a reason; a scanner without a store cannot make lists
    await expectCode(putLines(db, foiano, { lines: [{ ean: "1", boxes: 1, kind: "urgent" }] }, SAT), "VALIDATION");
    await expectCode(putLines(db, { name: "x", entityCode: null }, { lines: [{ ean: "1", boxes: 1 }] }, SAT), "FORBIDDEN");

    const s = await submitList(db, foiano, SAT);
    expect(s.list).toMatchObject({ status: "submitted", submittedBy: "TC22 FOIANO 1" });
    // after the deadline a scan goes into next week's list
    const mon = new Date("2026-11-23T08:00:00Z");
    const n = await putLines(db, foiano, { lines: [{ ean: "123", boxes: 1 }] }, mon);
    expect(n.week).toBe("2026-11-29");
    expect(n.previous?.week).toBe("2026-11-22");
  });

  it("empty lists cannot be sent; reminders come back the next weeks", async () => {
    await expectCode(submitList(db, foiano, SAT), "VALIDATION");
    await putLines(db, foiano, { lines: [{ ean: "8001090213465", name: "SVELTO", boxes: 0, kind: "remind" }] }, SAT);
    const next = await currentList(db, 1, new Date("2026-11-24T08:00:00Z"));
    expect(next.reminders).toEqual([{ ean: "8001090213465", name: "SVELTO", week: "2026-11-22" }]);
  });

  it("the purchasing program reads, marks received, sends back what was ordered; deadline can change", async () => {
    await putLines(db, foiano, { lines: [{ ean: "1", name: "A", boxes: 2 }] }, SAT);
    await submitList(db, foiano, SAT);
    const mon = new Date("2026-11-23T08:00:00Z");
    const r = await erpLists(db, null, mon);
    expect(r.current).toBe("2026-11-29");
    expect(r.lists.map((l) => [l.entityCode, l.week, l.status, l.lines.length])).toEqual([[1, "2026-11-22", "submitted", 1]]);
    const id = r.lists[0].id;
    expect((await markReceived(db, id, mon)).receivedAt).toBe(mon.toISOString());
    await setFeedback(db, id, { lines: { "1": { boxes: 1, note: "ridotto" } }, deliveries: [{ supplier: "MELONI", when: "27/11 09:00" }] }, mon);
    const v = await currentList(db, 1, mon);
    expect(v.previous).toMatchObject({ status: "ordered", feedback: { lines: { "1": { boxes: 1 } } } });
    await expectCode(setFeedback(db, 999, {}, mon), "NOT_FOUND");

    await setRule(db, { day: 3, time: "12:00" }, mon);
    expect((await currentList(db, 1, mon)).week).toBe("2026-11-25");
    await expectCode(setRule(db, { day: 8, time: "10:00" }), "VALIDATION");
  });
});
