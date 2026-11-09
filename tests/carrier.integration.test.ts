import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { seed } from "@/db/seed";
import { cancelByToken, getBookableDays, getBookingByToken, listEntities, sendBookingMail } from "@/lib/booking/carrier";
import { BookingError } from "@/lib/booking/errors";
import { createBooking } from "@/lib/booking/service";
import type { Mailer, OutgoingMail } from "@/lib/mail/mailer";
import { zonedToUtc } from "@/lib/time";

const url = process.env.TEST_DATABASE_URL;
const TZ = "Europe/Rome";
const NOW = zonedToUtc("2026-11-09", "10:00", TZ); // Monday

function recordingMailer(fail = false): Mailer & { sent: OutgoingMail[] } {
  const sent: OutgoingMail[] = [];
  return {
    sent,
    async send(m) {
      if (fail) throw new Error("SMTP down");
      sent.push(m);
    },
  };
}

describe.skipIf(!url)("carrier functions (PostgreSQL)", () => {
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
    await seed(db, { holidayYears: [2026, 2027] });
  });

  beforeEach(async () => {
    await db.execute(sql`truncate booking restart identity cascade`);
    await db.execute(sql`truncate notification_log restart identity`);
  });

  afterAll(async () => close());

  it("lists entities and which ones share a warehouse", async () => {
    const list = await listEntities(db);
    expect(list.map((e) => e.code)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(list[0].sharedWith).toEqual(["SAN FILIPPO"]);
    expect(list[3].sharedWith).toEqual(["BMS"]);
    expect(list[1].sharedWith).toEqual([]);
  });

  it("returns the booking window with weekends and holidays closed", async () => {
    const days = await getBookableDays(db, 1, NOW);
    expect(days[0]).toEqual({ date: "2026-11-10", open: true });
    expect(days.at(-1)!.date).toBe("2026-11-23");
    expect(days.filter((d) => !d.open).map((d) => d.date)).toEqual([
      "2026-11-14",
      "2026-11-15",
      "2026-11-21",
      "2026-11-22",
    ]);
    const december = await getBookableDays(db, 1, zonedToUtc("2026-12-01", "10:00", TZ));
    expect(december.find((d) => d.date === "2026-12-08")?.open).toBe(false);
  });

  it("finds a booking by its secret link and lets the carrier cancel it in time", async () => {
    const b = await createBooking(
      db,
      { entityCode: 3, date: "2026-11-11", time: "14:30", vehicleType: "truck", pallets: 12, parcels: 0, carrierName: "X", contactEmail: "x@example.com", lang: "en" },
      { now: NOW },
    );
    const view = await getBookingByToken(db, b.manageToken, NOW);
    expect(view).toMatchObject({
      code: b.code,
      status: "requested",
      lang: "en",
      entityName: "SAN FILIPPO",
      date: "2026-11-11",
      time: "14:30",
      endTime: "15:30",
      canCancel: true,
    });
    expect(await getBookingByToken(db, "wrong-token", NOW)).toBeNull();

    const mailer = recordingMailer();
    const after = await cancelByToken(db, b.manageToken, { now: NOW, mailer });
    expect(after.status).toBe("cancelled");
    expect(after.canCancel).toBe(false);
    expect(mailer.sent[0].subject).toBe(`Unloading booking cancelled – ${b.code}`);
    const log = await db.execute(sql`select template, ok from notification_log`);
    expect(log.rows).toEqual([{ template: "cancelled", ok: true }]);

    await expect(cancelByToken(db, "wrong-token", { now: NOW })).rejects.toBeInstanceOf(BookingError);
  });

  it("does not fail when the mail server is down, but logs it", async () => {
    const b = await createBooking(
      db,
      { entityCode: 1, date: "2026-11-10", time: "09:00", vehicleType: "van", pallets: 2, parcels: 0, carrierName: "Y", contactEmail: "y@example.com" },
      { now: NOW },
    );
    const ok = await sendBookingMail(db, recordingMailer(true), { bookingId: b.id, kind: "requested", manageUrl: "https://x/b/t" });
    expect(ok).toBe(false);
    const log = await db.execute(sql`select ok, error from notification_log`);
    expect(log.rows).toEqual([{ ok: false, error: "SMTP down" }]);
  });
});
