import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { seed } from "@/db/seed";
import { BookingError } from "@/lib/booking/errors";
import { erpReports, handleReport, reportStock, storeReports } from "@/lib/stock-reports";
import { currentList } from "@/lib/store-lists";

const url = process.env.TEST_DATABASE_URL;
const NOW = new Date("2026-11-21T11:00:00Z");
const foiano = { name: "TC22 FOIANO 1", entityCode: 1 };
const ok = { ean: "08001090213465", name: "SVELTO", systemQty: 12, counted: 0, countedBy: "Marco", shelf: true, backroom: true };

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe.skipIf(!url)("stock reports (PostgreSQL)", () => {
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
    await db.execute(sql`truncate stock_report, store_list, store_list_line, app_setting restart identity cascade`);
  });

  afterAll(async () => close());

  it("needs both checks, a name and a different count; a new count replaces the open one", async () => {
    await expectCode(reportStock(db, foiano, { ...ok, backroom: false }, NOW), "VALIDATION");
    await expectCode(reportStock(db, foiano, { ...ok, countedBy: "" }, NOW), "VALIDATION");
    await expectCode(reportStock(db, foiano, { ...ok, counted: 12 }, NOW), "VALIDATION");
    await expectCode(reportStock(db, { name: "x", entityCode: null }, ok, NOW), "FORBIDDEN");
    const r = await reportStock(db, foiano, ok, NOW);
    expect(r).toMatchObject({ ean: "8001090213465", systemQty: 12, counted: 0, countedBy: "Marco", status: "open", device: "TC22 FOIANO 1" });
    const r2 = await reportStock(db, foiano, { ...ok, counted: 2 }, NOW);
    expect(r2.id).toBe(r.id);
    expect((await storeReports(db, 1, NOW)).map((x) => x.counted)).toEqual([2]);
    expect(await storeReports(db, 2, NOW)).toEqual([]);
    // the scanner's list call carries them
    expect((await currentList(db, 1, NOW)).stockReports).toHaveLength(1);
  });

  it("the purchasing office marks fixed / rejected (with reason) and can undo", async () => {
    const r = await reportStock(db, foiano, ok, NOW);
    expect(await erpReports(db, "open", NOW)).toHaveLength(1);
    await expectCode(handleReport(db, r.id, { action: "rejected" }, NOW), "VALIDATION");
    const f = await handleReport(db, r.id, { action: "fixed", by: "Marco" }, NOW);
    expect(f).toMatchObject({ status: "fixed", handledBy: "Marco" });
    expect(await erpReports(db, "open", NOW)).toHaveLength(0);
    expect((await handleReport(db, r.id, { action: "reopen" }, NOW)).status).toBe("open");
    const x = await handleReport(db, r.id, { action: "rejected", answer: "Ricontare: 6 pz in magazzino" }, NOW);
    expect(x.answer).toBe("Ricontare: 6 pz in magazzino");
    // after fixing, a new count opens a new report
    await handleReport(db, r.id, { action: "fixed" }, NOW);
    const n = await reportStock(db, foiano, { ...ok, counted: 1 }, NOW);
    expect(n.id).not.toBe(r.id);
    expect(await erpReports(db, "all", NOW)).toHaveLength(2);
    await expectCode(handleReport(db, 999, { action: "fixed" }, NOW), "NOT_FOUND");
  });
});
