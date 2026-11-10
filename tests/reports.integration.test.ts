import { sql } from "drizzle-orm";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { appUser, site } from "@/db/schema";
import { seed } from "@/db/seed";
import { changeOwnPassword } from "@/lib/auth/account";
import { hashPassword } from "@/lib/auth/password";
import { authenticate, createSession, getSessionUser } from "@/lib/auth/session";
import type { SessionUser } from "@/lib/auth/site-access";
import { BookingError } from "@/lib/booking/errors";
import { createBooking, transitionBooking, type CreateBookingInput } from "@/lib/booking/service";
import { bookingsCsv, getReport, loadReportRows, quantile } from "@/lib/reports/service";
import { zonedToUtc } from "@/lib/time";

const url = process.env.TEST_DATABASE_URL;
const TZ = "Europe/Rome";
const NOW = zonedToUtc("2026-11-09", "10:00", TZ);
const DAY = "2026-11-10";
const at = (t: string, d = DAY) => zonedToUtc(d, t, TZ);
const hoursAfterNow = (h: number) => new Date(NOW.getTime() + h * 3600_000);

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe("quantile", () => {
  it("interpolates", () => {
    expect(quantile([10, 20, 30, 40], 0.5)).toBe(25);
    expect(quantile([5], 0.9)).toBe(5);
    expect(quantile([], 0.5)).toBeNull();
  });
});

describe.skipIf(!url)("reports (PostgreSQL)", () => {
  let db: Db;
  let close: () => Promise<void>;
  let admin: SessionUser;
  let foianoManager: SessionUser;
  let foiano: number;
  let marlia: number;

  beforeAll(async () => {
    const c = createDb(url!);
    db = c.db;
    close = () => c.pool.end();
    await db.execute(sql`drop schema if exists public cascade`);
    await db.execute(sql`drop schema if exists drizzle cascade`);
    await db.execute(sql`create schema public`);
    await runMigrations(db);
    await seed(db, { holidayYears: [2026] });
    const sites = Object.fromEntries((await db.select().from(site)).map((s) => [s.code, s.id]));
    foiano = sites.FOIANO;
    marlia = sites.MARLIA;
    admin = { id: 1, role: "admin", siteIds: [] };
    foianoManager = { id: 2, role: "warehouse_manager", siteIds: [foiano] };

    const book = (over: Partial<CreateBookingInput>) =>
      createBooking(
        db,
        { entityCode: 1, date: DAY, time: "10:00", vehicleType: "van", pallets: 3, parcels: 0, carrierName: "x", contactEmail: "x@example.com", ...over },
        { now: NOW },
      );
    const go = (id: number, to: Parameters<typeof transitionBooking>[1]["to"], when: Date, type: "user" | "carrier" | "system" = "user") =>
      transitionBooking(db, { bookingId: id, to, actor: { type } }, when);

    // A: on time, unloads in 30 min, waits 5 min; confirmed after 1 h.
    const a = await book({ time: "10:00", carrierName: "Rossi srl", notes: "=SUM(1)" });
    await go(a.id, "confirmed", hoursAfterNow(1));
    await go(a.id, "arrived", at("10:05"));
    await go(a.id, "unloading", at("10:10"));
    await go(a.id, "completed", at("10:40"));
    // B: same carrier written differently; 40 min late, truck takes 70 min instead of 60; confirmed after 6 h.
    const b = await book({ time: "11:00", vehicleType: "truck", pallets: 12, carrierName: "rossi  SRL" });
    await go(b.id, "confirmed", hoursAfterNow(6));
    await go(b.id, "arrived", at("11:40"));
    await go(b.id, "unloading", at("11:50"));
    await go(b.id, "completed", at("13:00"));
    // C: confirmed after 2 h, never shows up.
    const c2 = await book({ time: "14:00", carrierName: "GLS" });
    await go(c2.id, "confirmed", hoursAfterNow(2));
    await go(c2.id, "no_show", at("14:31"), "system");
    // D: cancelled by the carrier. E: declined by the warehouse.
    const d = await book({ time: "15:00", carrierName: "GLS" });
    await go(d.id, "cancelled", NOW, "carrier");
    const e = await book({ time: "16:00", carrierName: "BRT" });
    await go(e.id, "rejected", NOW);
    // Another warehouse, outside the manager's reach.
    await book({ entityCode: 2, time: "10:00", carrierName: "Marlia Co" });
  });

  afterAll(async () => close());

  it("computes volumes, punctuality, no-shows, confirmation speed and waiting", async () => {
    const r = await getReport(db, admin, { from: DAY, to: DAY, siteId: foiano });
    expect(r.totals.requests).toBe(5);
    expect(r.totals.byStatus).toMatchObject({ completed: 2, no_show: 1, cancelled: 1, rejected: 1 });
    expect(r.totals.cancelledByCarrier).toBe(1);
    expect(r.totals.pallets).toBe(3 + 12 + 3); // A, B, C took a slot
    expect(r.punctuality).toEqual({ arrived: 2, onTime: 1, late: 1, onTimeRate: 0.5, avgLateMin: 40, noShows: 1, noShowRate: 1 / 3 });
    expect(r.confirmation).toEqual({ decided: 3, avgHours: 3, withinSlaRate: 2 / 3 });
    expect(r.waiting).toEqual({ measured: 2, avgMin: 8, over15Rate: 0 }); // 5 and 10 min
  });

  it("compares actual and planned unloading time per vehicle", async () => {
    const r = await getReport(db, admin, { from: DAY, to: DAY, siteId: foiano });
    expect(r.unloading.find((u) => u.vehicleType === "van")).toMatchObject({ measured: 1, plannedMin: 30, avgMin: 30 });
    expect(r.unloading.find((u) => u.vehicleType === "truck")).toMatchObject({ measured: 1, plannedMin: 60, avgMin: 70, medianMin: 70 });
    expect(r.unloading.find((u) => u.vehicleType === "semi")).toMatchObject({ measured: 0, avgMin: null });
  });

  it("groups carriers regardless of case and spacing", async () => {
    const r = await getReport(db, admin, { from: DAY, to: DAY, siteId: foiano });
    const rossi = r.carriers.find((c) => c.name.toLowerCase().startsWith("rossi"))!;
    expect(rossi).toMatchObject({ requests: 2, completed: 2, noShows: 0, onTimeRate: 0.5 });
    expect(r.carriers.find((c) => c.name === "GLS")).toMatchObject({ requests: 2, noShows: 1, noShowRate: 1, cancelledByCarrier: 1 });
    expect(r.entities).toEqual([{ code: 1, name: "FOIANO", bookings: 3, pallets: 18 }]);
  });

  it("builds the daily series and the weekday × hour heatmap", async () => {
    const r = await getReport(db, admin, { from: "2026-11-09", to: "2026-11-11", siteId: foiano });
    expect(r.daily).toEqual([
      { date: "2026-11-09", bookings: 0, pallets: 0 },
      { date: "2026-11-10", bookings: 3, pallets: 18 },
      { date: "2026-11-11", bookings: 0, pallets: 0 },
    ]);
    expect(r.heatmap.sort((x, y) => x.hour - y.hour)).toEqual([
      { weekday: 2, hour: 10, bookings: 1 },
      { weekday: 2, hour: 11, bookings: 1 },
      { weekday: 2, hour: 14, bookings: 1 },
    ]);
  });

  it("limits managers to their warehouses and refuses clerks and bad ranges", async () => {
    expect((await getReport(db, foianoManager, { from: DAY, to: DAY, siteId: null })).totals.requests).toBe(5);
    expect((await getReport(db, admin, { from: DAY, to: DAY, siteId: null })).totals.requests).toBe(6);
    await expectCode(getReport(db, foianoManager, { from: DAY, to: DAY, siteId: marlia }), "FORBIDDEN");
    await expectCode(getReport(db, { id: 3, role: "warehouse_clerk", siteIds: [foiano] }, { from: DAY, to: DAY, siteId: null }), "FORBIDDEN");
    await expectCode(getReport(db, admin, { from: "2026-11-10", to: "2026-11-01", siteId: null }), "VALIDATION");
    await expectCode(getReport(db, admin, { from: "2025-01-01", to: "2026-11-10", siteId: null }), "VALIDATION");
  });

  it("exports an Excel-friendly CSV and neutralises formulas", async () => {
    const csv = bookingsCsv(await loadReportRows(db, admin, { from: DAY, to: DAY, siteId: foiano }));
    expect(csv.startsWith("﻿Codice;Data;Ora;Fine;Magazzino")).toBe(true);
    const lines = csv.trim().split("\r\n");
    expect(lines).toHaveLength(6);
    expect(lines[1]).toContain(";2026-11-10;10:00;10:30;Magazzino Foiano;1 FOIANO;Completata;Rossi srl;");
    expect(lines[1]).toContain(";'=SUM(1);");
    expect(lines[1]).toMatch(/;5;30\r?$/); // 5 min after the slot, 30 min unloading
    expect(lines[2]).toMatch(/;40;70$/);
  });

  it("lets users change their own password and signs out their other devices", async () => {
    const [u] = await db
      .insert(appUser)
      .values({ email: "me@casa-te.com", name: "Me", role: "warehouse_clerk", passwordHash: await hashPassword("old-password-1") })
      .returning();
    const here = await createSession(db, u.id, NOW, true);
    const elsewhere = await createSession(db, u.id, NOW, true);
    const me = (await getSessionUser(db, here.token, NOW))!;

    await expectCode(changeOwnPassword(db, me, here.token, "wrong", "new-password-22"), "FORBIDDEN");
    await expectCode(changeOwnPassword(db, me, here.token, "old-password-1", "short"), "VALIDATION");
    await changeOwnPassword(db, me, here.token, "old-password-1", "new-password-22");

    expect(await getSessionUser(db, here.token, NOW)).not.toBeNull();
    expect(await getSessionUser(db, elsewhere.token, NOW)).toBeNull();
    expect((await authenticate(db, "me@casa-te.com", "old-password-1", undefined)).status).toBe("bad_credentials");
    expect((await authenticate(db, "me@casa-te.com", "new-password-22", undefined)).status).toBe("ok");
  });
});
