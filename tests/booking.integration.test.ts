// Runs against a real PostgreSQL (the overlap guarantee lives in the database).
// Set TEST_DATABASE_URL to a throwaway database; the test wipes it.
import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { seed } from "@/db/seed";
import { BookingError } from "@/lib/booking/errors";
import { createBooking, getAvailability, transitionBooking, type CreateBookingInput } from "@/lib/booking/service";
import { localTime, zonedToUtc } from "@/lib/time";

const url = process.env.TEST_DATABASE_URL;
const TZ = "Europe/Rome";
// Monday 9 Nov 2026, 10:00 in Rome. Tuesday 10 Nov is bookable; Wed 11 Nov too.
const NOW = zonedToUtc("2026-11-09", "10:00", TZ);
const DAY = "2026-11-10";

const base = (over: Partial<CreateBookingInput> = {}): CreateBookingInput => ({
  entityCode: 1,
  date: DAY,
  time: "10:00",
  vehicleType: "van",
  pallets: 4,
  parcels: 0,
  carrierName: "Trasporti Rossi srl",
  contactEmail: "Logistica@Rossi.example",
  driverName: "Mario",
  plate: "ab 123 cd",
  orderNo: "PO-1",
  ...over,
});

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe.skipIf(!url)("booking service (PostgreSQL)", () => {
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
    await seed(db, { holidayYears: [2026, 2027] }); // idempotent
  });

  beforeEach(async () => {
    await db.execute(sql`truncate booking restart identity cascade`);
  });

  afterAll(async () => close());

  it("seeds 6 entities on 5 sites, with 0 and 3 sharing one site", async () => {
    const rows = await db.execute<{ code: number; site_code: string }>(
      sql`select e.code, s.code as site_code from entity e join site s on s.id = e.site_id order by e.code`,
    );
    expect(rows.rows).toHaveLength(6);
    expect(rows.rows[0].site_code).toBe(rows.rows[3].site_code);
    const docks = await db.execute(sql`select count(*)::int as n from dock`);
    expect(docks.rows[0].n).toBe(5);
  });

  it("creates a requested booking, normalises input and logs the event", async () => {
    const b = await createBooking(db, base(), { now: NOW });
    expect(b.status).toBe("requested");
    expect(b.code).toMatch(/^P261110-[2-9A-Z]{5}$/);
    expect(b.manageToken.length).toBeGreaterThan(20);
    const row = (await db.execute(sql`select plate, contact_email, manage_token_hash from booking where id = ${b.id}`)).rows[0];
    expect(row.plate).toBe("AB123CD");
    expect(row.contact_email).toBe("logistica@rossi.example");
    expect(row.manage_token_hash).not.toBe(b.manageToken);
    const ev = await db.execute(sql`select to_status from booking_event where booking_id = ${b.id}`);
    expect(ev.rows).toEqual([{ to_status: "requested" }]);
  });

  it("holds the slot while waiting for confirmation", async () => {
    await createBooking(db, base(), { now: NOW });
    const avail = await getAvailability(db, { entityCode: 1, date: DAY, vehicleType: "van", pallets: 4, parcels: 0 }, NOW);
    const times = avail.slots.map((s) => localTime(s.start, TZ));
    expect(times).not.toContain("10:00");
    expect(times).not.toContain("09:45");
    expect(times).toContain("10:30");
  });

  it("makes BMS (0) and SAN FILIPPO (3) share the same dock", async () => {
    await createBooking(db, base({ entityCode: 0 }), { now: NOW });
    await expectCode(createBooking(db, base({ entityCode: 3, time: "10:15" }), { now: NOW }), "SLOT_UNAVAILABLE");
    // A different warehouse is unaffected.
    await createBooking(db, base({ entityCode: 2 }), { now: NOW });
  });

  it("lets exactly one of many concurrent requests win the same slot", async () => {
    const attempts = await Promise.allSettled(
      Array.from({ length: 8 }, (_, i) =>
        createBooking(db, base({ entityCode: i % 2 === 0 ? 0 : 3, carrierName: `Carrier ${i}` }), { now: NOW }),
      ),
    );
    expect(attempts.filter((a) => a.status === "fulfilled")).toHaveLength(1);
    for (const a of attempts.filter((x) => x.status === "rejected")) {
      expect((a as PromiseRejectedResult).reason).toBeInstanceOf(BookingError);
    }
  });

  it("also blocks overlaps written directly to the database", async () => {
    const b = await createBooking(db, base(), { now: NOW });
    await expect(
      db.execute(sql`
        insert into booking (code, entity_id, site_id, dock_id, start_at, end_at, carrier_name, contact_email,
                             vehicle_type, pallets, manage_token_hash)
        select 'X1', entity_id, site_id, dock_id, start_at + interval '15 minutes', end_at, 'x', 'x@x', 'van', 1, 'h'
        from booking where id = ${b.id}`),
    ).rejects.toSatisfy((e: unknown) => JSON.stringify(e).includes("23P01") || String(e).includes("booking_no_overlap"));
  });

  it("frees the slot after a rejection", async () => {
    const b = await createBooking(db, base(), { now: NOW });
    await transitionBooking(db, { bookingId: b.id, to: "rejected", actor: { type: "user" } }, NOW);
    await createBooking(db, base({ carrierName: "Another carrier" }), { now: NOW });
  });

  it("enforces the booking window, opening hours, closures and load rules", async () => {
    const after16 = zonedToUtc("2026-11-09", "16:30", TZ);
    await expectCode(createBooking(db, base(), { now: after16 }), "DATE_NOT_BOOKABLE");
    await expectCode(createBooking(db, base({ date: "2026-11-30" }), { now: NOW }), "DATE_NOT_BOOKABLE");
    await expectCode(createBooking(db, base({ date: "2026-11-14" }), { now: NOW }), "SITE_CLOSED"); // Saturday
    await expectCode(createBooking(db, base({ time: "17:45" }), { now: NOW }), "SLOT_UNAVAILABLE"); // runs past 18:00
    await expectCode(createBooking(db, base({ time: "10:10" }), { now: NOW }), "SLOT_UNAVAILABLE"); // off grid
    await expectCode(createBooking(db, base({ pallets: 9 }), { now: NOW }), "LOAD_NOT_ALLOWED");
    await expectCode(createBooking(db, base({ entityCode: 9 }), { now: NOW }), "ENTITY_NOT_FOUND");
    await expectCode(createBooking(db, base({ contactEmail: "not-an-email" }), { now: NOW }), "VALIDATION");

    // 8 Dec is a national holiday (Immacolata).
    const early = zonedToUtc("2026-12-01", "10:00", TZ);
    await expectCode(createBooking(db, base({ date: "2026-12-08" }), { now: early }), "SITE_CLOSED");
  });

  it("follows the status flow and the carrier cancellation cutoff", async () => {
    const b = await createBooking(db, base(), { now: NOW });
    await expectCode(
      transitionBooking(db, { bookingId: b.id, to: "arrived", actor: { type: "user" } }, NOW),
      "INVALID_TRANSITION",
    );
    await transitionBooking(db, { bookingId: b.id, to: "confirmed", actor: { type: "user" } }, NOW);

    // Slot at 10:00 on the 10th; cancelling online closes 12 h before.
    const tooLate = zonedToUtc("2026-11-09", "23:00", TZ);
    await expectCode(
      transitionBooking(db, { bookingId: b.id, to: "cancelled", actor: { type: "carrier" } }, tooLate),
      "TOO_LATE",
    );
    await expectCode(
      transitionBooking(db, { bookingId: b.id, to: "no_show", actor: { type: "system" } }, zonedToUtc(DAY, "10:20", TZ)),
      "TOO_EARLY",
    );

    const arrival = zonedToUtc(DAY, "10:05", TZ);
    await transitionBooking(db, { bookingId: b.id, to: "arrived", actor: { type: "user" } }, arrival);
    await transitionBooking(db, { bookingId: b.id, to: "unloading", actor: { type: "user" } }, arrival);
    await transitionBooking(db, { bookingId: b.id, to: "completed", actor: { type: "user" } }, arrival);
    const ev = await db.execute(sql`select to_status from booking_event where booking_id = ${b.id} order by id`);
    expect(ev.rows.map((r) => r.to_status)).toEqual(["requested", "confirmed", "arrived", "unloading", "completed"]);
  });
});
