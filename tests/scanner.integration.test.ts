import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { seed } from "@/db/seed";
import { BookingError } from "@/lib/booking/errors";
import {
  catalogFor,
  createDevice,
  deleteDevice,
  deviceFor,
  latestCatalog,
  latestCatalogInfo,
  listDevices,
  normCode,
  pair,
  putCatalogPart,
  renewCode,
  setActive,
  updateDevice,
} from "@/lib/scanner";

const url = process.env.TEST_DATABASE_URL;
const NOW = new Date("2026-11-21T10:00:00Z");

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe("pairing code", () => {
  it("ignores case, spaces and dashes", () => {
    expect(normCode(" abcd-23 45 ")).toBe("ABCD2345");
  });
});

describe.skipIf(!url)("store scanners (PostgreSQL)", () => {
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
    await db.execute(sql`truncate scanner_device, catalog_snapshot, catalog_part restart identity`);
  });

  afterAll(async () => close());

  it("pairs a device once with its code, then knows it by its key", async () => {
    const { device, pairCode } = await createDevice(db, { name: "TC22 Foiano", entityCode: 1 }, NOW);
    expect(device).toMatchObject({ name: "TC22 Foiano", entityCode: 1, paired: false, active: true });
    expect(pairCode).toMatch(/^[2-9A-HJ-NP-Z]{8}$/);

    const later = new Date(NOW.getTime() + 3600_000);
    const { token } = await pair(db, pairCode.toLowerCase().slice(0, 4) + "-" + pairCode.slice(4), later);
    expect((await deviceFor(db, token, later))?.name).toBe("TC22 Foiano");
    await expectCode(pair(db, pairCode, later), "NOT_FOUND"); // a code works once
    expect(await deviceFor(db, "forged")).toBeNull();
    expect((await listDevices(db))[0]).toMatchObject({ paired: true, lastSeenAt: later.toISOString() });

    // Lost scanner: a new code cuts off the old key.
    const { pairCode: code2 } = await renewCode(db, device.id, later);
    expect(await deviceFor(db, token)).toBeNull();
    const { token: t2 } = await pair(db, code2, later);
    // Deactivated: key and code stop working.
    await setActive(db, device.id, false);
    expect(await deviceFor(db, t2)).toBeNull();
  });

  it("renames / moves a device and deletes it only once deactivated", async () => {
    const { device, pairCode } = await createDevice(db, { name: "TC22 Foiano", entityCode: 1 }, NOW);
    const { token } = await pair(db, pairCode, NOW);
    const d = await updateDevice(db, device.id, { name: "TC22 Marlia 1", entityCode: 2 });
    expect(d).toMatchObject({ name: "TC22 Marlia 1", entityCode: 2, paired: true, active: true });
    expect(await deviceFor(db, token, NOW)).not.toBeNull();
    await expectCode(updateDevice(db, device.id, { name: "  " }), "VALIDATION");
    await expectCode(updateDevice(db, 999, { name: "x" }), "NOT_FOUND");

    await expectCode(deleteDevice(db, device.id), "CONFLICT");
    await setActive(db, device.id, false);
    expect(await deleteDevice(db, device.id)).toEqual({ deleted: device.id });
    expect(await listDevices(db)).toEqual([]);
    await expectCode(deleteDevice(db, device.id), "NOT_FOUND");
  });

  it("expired codes do not pair", async () => {
    const { pairCode } = await createDevice(db, { name: "TC22 Marlia", entityCode: 2 }, NOW);
    await expectCode(pair(db, pairCode, new Date(NOW.getTime() + 8 * 86_400_000)), "NOT_FOUND");
  });

  it("joins catalog parts into one snapshot and keeps the last three", async () => {
    const meta = { week: "47", sources: ["MELONI", "PETRILLO"], directs: ["BAMA"] };
    const r1 = await putCatalogPart(db, { upload: "up-00000001", part: 2, parts: 2, items: [["2", "B", 0, [], null, "", "", 0]] }, "Marco", NOW);
    expect(r1).toEqual({ complete: false, received: 1 });
    expect(await latestCatalogInfo(db)).toBeNull();
    const r2 = await putCatalogPart(db, { upload: "up-00000001", part: 1, parts: 2, meta, items: [["1", "A", 1, [0, 1], 1.5, "", "", -1]] }, "Marco", NOW);
    expect(r2).toMatchObject({ complete: true, items: 2 });
    const c = await latestCatalog(db);
    expect(c?.payload).toMatchObject({ week: "47", sources: ["MELONI", "PETRILLO"], items: [["1", "A", 1, [0, 1], 1.5, "", "", -1], ["2", "B", 0, [], null, "", "", 0]] });
    expect(c?.createdBy).toBe("Marco");
    const parts = await db.execute(sql`select count(*)::int as n from catalog_part`);
    expect(parts.rows[0].n).toBe(0);

    for (let k = 0; k < 4; k++) {
      await putCatalogPart(db, { upload: `up-1000000${k}`, part: 1, parts: 1, items: [["9", "X", 1]] }, "Marco", NOW);
    }
    const n = await db.execute(sql`select count(*)::int as n from catalog_snapshot`);
    expect(n.rows[0].n).toBe(3);
  });

  it("keeps each store's data apart; a scanner only gets its own store", async () => {
    const up = "up-20000001";
    await putCatalogPart(db, { upload: up, part: 1, parts: 3, meta: { week: "48" }, items: [["1", "A", 1, [0], "", "", -1]] }, "M", NOW);
    await putCatalogPart(db, { upload: up, part: 2, parts: 3, store: 1, items: [["1", 9, 22]] }, "M", NOW);
    await putCatalogPart(db, { upload: up, part: 3, parts: 3, store: 2, items: [["1", 3, 5]] }, "M", NOW);
    const c = await latestCatalog(db);
    const foiano = catalogFor(c?.payload, 1);
    expect(foiano).toMatchObject({ week: "48", items: [["1", "A", 1, [0], "", "", -1]], store: [["1", 9, 22]] });
    expect(foiano).not.toHaveProperty("stores");
    expect(catalogFor(c?.payload, 5).store).toEqual([]);
    expect(catalogFor(c?.payload, null).store).toEqual([]);
  });
});
