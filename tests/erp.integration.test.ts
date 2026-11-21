import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { bookingEvent, incident } from "@/db/schema";
import { seed } from "@/db/seed";
import { erpCreateBooking, erpSetStatus, listErpBookings, pathTo } from "@/lib/booking/erp";
import { BookingError } from "@/lib/booking/errors";
import { createBooking, getAvailability } from "@/lib/booking/service";
import type { Mailer, OutgoingMail } from "@/lib/mail/mailer";
import { zonedToUtc } from "@/lib/time";

const url = process.env.TEST_DATABASE_URL;
const TZ = "Europe/Rome";
// Monday 23/11/2026 at 17:00: past the carriers' 16:00 cutoff for tomorrow.
const NOW = zonedToUtc("2026-11-23", "17:00", TZ);

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe("status path", () => {
  it("walks confirmed → arrived → unloading → completed in one go", () => {
    expect(pathTo("confirmed", "completed")).toEqual(["arrived", "unloading", "completed"]);
    expect(pathTo("confirmed", "refused")).toEqual(["refused"]);
    expect(pathTo("arrived", "refused")).toEqual(["refused"]);
    expect(pathTo("completed", "refused")).toBeNull();
    expect(pathTo("requested", "completed")).toEqual(["confirmed", "arrived", "unloading", "completed"]);
  });
});

describe.skipIf(!url)("purchasing program link (PostgreSQL)", () => {
  let db: Db;
  let close: () => Promise<void>;
  const sent: OutgoingMail[] = [];
  const mailer: Mailer = { send: async (m) => void sent.push(m) };
  const phone = {
    entityCode: 1,
    date: "2026-11-24",
    time: "09:00",
    vehicleType: "van" as const,
    pallets: 2,
    parcels: 0,
    carrierName: "BRT",
    driverPhone: "+39 333 1234567",
    supplier: "MELONI",
    orderNo: "FO-2311-3F9A",
    by: "Marco",
  };

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
    await db.execute(sql`truncate booking restart identity cascade`);
    sent.length = 0;
  });

  afterAll(async () => close());

  it("lets the office book tomorrow after the carriers' cutoff, confirmed at once, no e-mail needed", async () => {
    // Carriers can no longer book tomorrow at 17:00 …
    const carrierSlots = await getAvailability(db, { entityCode: 1, date: "2026-11-24", vehicleType: "van", pallets: 2, parcels: 0 }, NOW);
    expect(carrierSlots.slots).toHaveLength(0);
    // … the office still can.
    const staffSlots = await getAvailability(db, { entityCode: 1, date: "2026-11-24", vehicleType: "van", pallets: 2, parcels: 0 }, NOW, { staff: true });
    expect(staffSlots.slots[0]).toBeDefined();

    const b = await erpCreateBooking(db, phone, { now: NOW, mailer });
    expect(b.status).toBe("confirmed");
    expect(sent).toHaveLength(0); // no carrier e-mail given
    const ev = await db.select().from(bookingEvent);
    expect(ev[0]).toMatchObject({ toStatus: "confirmed", actorType: "system", note: "Ufficio acquisti · Marco" });

    // The slot is taken for carriers and office alike.
    await expectCode(erpCreateBooking(db, { ...phone, orderNo: "FO-2311-0000" }, { now: NOW }), "SLOT_UNAVAILABLE");
    // Times already past today are not offered.
    const today = await getAvailability(db, { entityCode: 1, date: "2026-11-23", vehicleType: "van", pallets: 2, parcels: 0 }, NOW, { staff: true });
    expect(today.slots.every((s) => s.start.getTime() > NOW.getTime())).toBe(true);
    await expectCode(erpCreateBooking(db, { ...phone, date: "2026-11-23", time: "10:00" }, { now: NOW }), "DATE_NOT_BOOKABLE");
  });

  it("mails the carrier the confirmation when an e-mail is given", async () => {
    const b = await erpCreateBooking(db, { ...phone, contactEmail: "Ops@BRT.it" }, { now: NOW, mailer });
    expect(sent.map((m) => [m.to, m.subject])).toEqual([["ops@brt.it", `Scarico confermato – ${b.code}`]]);
  });

  it("finds bookings by order number, also inside the carrier's own text", async () => {
    await erpCreateBooking(db, phone, { now: NOW });
    await createBooking(
      db,
      { entityCode: 0, date: "2026-11-26", time: "11:00", vehicleType: "truck", pallets: 6, parcels: 0, carrierName: "GLS", contactEmail: "g@gls.it", orderNo: "ordini bms-2311-aa01 e BMS-2311-BB02" },
      { now: NOW },
    );
    const got = await listErpBookings(db, { orderNos: ["FO-2311-3F9A", "BMS-2311-BB02"] });
    expect(got.map((b) => [b.orderNo, b.status, b.date, b.time, b.entityCode])).toEqual([
      ["FO-2311-3F9A", "confirmed", "2026-11-24", "09:00", 1],
      ["ordini bms-2311-aa01 e BMS-2311-BB02", "requested", "2026-11-26", "11:00", 0],
    ]);
    expect(await listErpBookings(db, { from: "2026-11-25", to: "2026-11-30" })).toHaveLength(1);
    await expectCode(listErpBookings(db, {}), "VALIDATION");
  });

  it("records a receipt in one call and a refusal with its reason", async () => {
    const a = await erpCreateBooking(db, phone, { now: NOW });
    const later = zonedToUtc("2026-11-24", "09:20", TZ);
    expect(await erpSetStatus(db, a.id, { to: "completed", by: "Ronaldo" }, { now: later })).toEqual({ from: "confirmed", to: "completed" });
    const steps = await db.select({ to: bookingEvent.toStatus, note: bookingEvent.note }).from(bookingEvent).orderBy(bookingEvent.id);
    expect(steps.map((s) => s.to)).toEqual(["confirmed", "arrived", "unloading", "completed"]);
    expect(steps[3].note).toBe("Ufficio acquisti · Ronaldo");
    // Same status again: nothing to do.
    expect(await erpSetStatus(db, a.id, { to: "completed", by: "Ronaldo" }, { now: later })).toEqual({ from: "completed", to: "completed" });

    const b = await erpCreateBooking(db, { ...phone, time: "11:00", orderNo: "FO-2311-77AB" }, { now: NOW });
    await expectCode(erpSetStatus(db, b.id, { to: "refused", by: "Lucas" }, { now: later }), "VALIDATION");
    await erpSetStatus(db, b.id, { to: "refused", by: "Lucas", note: "Imballo danneggiato" }, { now: later });
    const inc = await db.select().from(incident);
    expect(inc).toEqual([expect.objectContaining({ bookingId: b.id, type: "refused", note: "Imballo danneggiato (Lucas)" })]);
    // A refused booking frees the dock: the same time can be booked again.
    await erpCreateBooking(db, { ...phone, time: "11:00", orderNo: "FO-2311-77AB" }, { now: NOW });
    // The program may not confirm or reject requests (that stays with the warehouse).
    await expectCode(erpSetStatus(db, b.id, { to: "confirmed", by: "Lucas" }), "FORBIDDEN");
  });
});
