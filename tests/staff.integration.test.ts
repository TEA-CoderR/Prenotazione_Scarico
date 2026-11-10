import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { appUser, site, userSite } from "@/db/schema";
import { seed } from "@/db/seed";
import { hashPassword, verifyPassword } from "@/lib/auth/password";
import { checkCredentials, createSession, deleteSession, getSessionUser, sitesForUser } from "@/lib/auth/session";
import type { SessionUser } from "@/lib/auth/site-access";
import { BookingError } from "@/lib/booking/errors";
import { createBooking, type CreateBookingInput } from "@/lib/booking/service";
import { getBoard, staffSetStatus } from "@/lib/booking/staff";
import type { Mailer, OutgoingMail } from "@/lib/mail/mailer";
import { zonedToUtc } from "@/lib/time";

const url = process.env.TEST_DATABASE_URL;
const TZ = "Europe/Rome";
const NOW = zonedToUtc("2026-11-09", "10:00", TZ);

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe("password hashing", () => {
  it("verifies the right password only", async () => {
    const h = await hashPassword("correct horse battery");
    expect(h.startsWith("scrypt$")).toBe(true);
    expect(await verifyPassword("correct horse battery", h)).toBe(true);
    expect(await verifyPassword("wrong", h)).toBe(false);
    expect(await verifyPassword("x", "garbage")).toBe(false);
  });
});

describe.skipIf(!url)("staff (PostgreSQL)", () => {
  let db: Db;
  let close: () => Promise<void>;
  let siteIds: Record<string, number>;
  let clerkShared: SessionUser; // BMS / San Filippo warehouse
  let clerkFoiano: SessionUser;
  let manager: SessionUser;
  const sent: OutgoingMail[] = [];
  const mailer: Mailer = { send: async (m) => void sent.push(m) };

  const book = (over: Partial<CreateBookingInput> = {}) =>
    createBooking(
      db,
      { entityCode: 0, date: "2026-11-10", time: "10:00", vehicleType: "van", pallets: 3, parcels: 0, carrierName: "Rossi", contactEmail: "r@example.com", ...over },
      { now: NOW },
    );

  beforeAll(async () => {
    const c = createDb(url!);
    db = c.db;
    close = () => c.pool.end();
    await db.execute(sql`drop schema if exists public cascade`);
    await db.execute(sql`drop schema if exists drizzle cascade`);
    await db.execute(sql`create schema public`);
    await runMigrations(db);
    await seed(db, { holidayYears: [2026] });
    const sites = await db.select().from(site);
    siteIds = Object.fromEntries(sites.map((s) => [s.code, s.id]));

    const pw = await hashPassword("password-123");
    const users = await db
      .insert(appUser)
      .values([
        { email: "clerk@casa-te.com", name: "Clerk", role: "warehouse_clerk", passwordHash: pw },
        { email: "foiano@casa-te.com", name: "Foiano", role: "warehouse_clerk", passwordHash: pw },
        { email: "manager@casa-te.com", name: "Manager", role: "warehouse_manager", passwordHash: pw },
        { email: "off@casa-te.com", name: "Off", role: "admin", passwordHash: pw, isActive: false },
      ])
      .returning();
    await db.insert(userSite).values([
      { userId: users[0].id, siteId: siteIds["BMS-SFILIPPO"] },
      { userId: users[1].id, siteId: siteIds.FOIANO },
      { userId: users[2].id, siteId: siteIds["BMS-SFILIPPO"] },
    ]);
    clerkShared = { id: users[0].id, role: "warehouse_clerk", siteIds: [siteIds["BMS-SFILIPPO"]] };
    clerkFoiano = { id: users[1].id, role: "warehouse_clerk", siteIds: [siteIds.FOIANO] };
    manager = { id: users[2].id, role: "warehouse_manager", siteIds: [siteIds["BMS-SFILIPPO"]] };
  });

  beforeEach(async () => {
    await db.execute(sql`truncate booking restart identity cascade`);
    sent.length = 0;
  });

  afterAll(async () => close());

  it("logs in with the right password, case-insensitive e-mail, active users only", async () => {
    expect(await checkCredentials(db, " Clerk@Casa-TE.com ", "password-123")).toBe(clerkShared.id);
    expect(await checkCredentials(db, "clerk@casa-te.com", "nope")).toBeNull();
    expect(await checkCredentials(db, "nobody@casa-te.com", "password-123")).toBeNull();
    expect(await checkCredentials(db, "off@casa-te.com", "password-123")).toBeNull();
  });

  it("creates, reads, renews, expires and deletes sessions", async () => {
    const { token } = await createSession(db, clerkShared.id, NOW);
    const u = await getSessionUser(db, token, NOW);
    expect(u).toMatchObject({ email: "clerk@casa-te.com", role: "warehouse_clerk", siteIds: [siteIds["BMS-SFILIPPO"]] });
    expect(await getSessionUser(db, "forged", NOW)).toBeNull();

    // Used after 10 days: renewed for another 14 (valid until day 24, not just day 14).
    const day10 = new Date(NOW.getTime() + 10 * 86_400_000);
    expect(await getSessionUser(db, token, day10)).not.toBeNull();
    const row = await db.execute(sql`select expires_at from staff_session`);
    expect(new Date(row.rows[0].expires_at as string).getTime()).toBe(day10.getTime() + 14 * 86_400_000);
    // Unused for longer than that: expired.
    expect(await getSessionUser(db, token, new Date(day10.getTime() + 15 * 86_400_000))).toBeNull();

    await deleteSession(db, token);
    expect(await getSessionUser(db, token, day10)).toBeNull();
  });

  it("only shows warehouses the user belongs to", async () => {
    expect((await sitesForUser(db, clerkShared)).map((s) => s.code)).toEqual(["BMS-SFILIPPO"]);
    expect(await sitesForUser(db, { id: 0, role: "admin", siteIds: [] })).toHaveLength(5);
    await expectCode(getBoard(db, clerkFoiano, siteIds["BMS-SFILIPPO"], "2026-11-10", NOW), "FORBIDDEN");
  });

  it("puts requests for both BMS and SAN FILIPPO on the shared warehouse board", async () => {
    await book({ entityCode: 0, time: "10:00" });
    await book({ entityCode: 3, time: "11:00" });
    await book({ entityCode: 1, time: "10:00" }); // Foiano: other board
    const board = await getBoard(db, clerkShared, siteIds["BMS-SFILIPPO"], "2026-11-10", NOW);
    expect(board.pending.map((b) => [b.entityName, b.time])).toEqual([
      ["BMS", "10:00"],
      ["SAN FILIPPO", "11:00"],
    ]);
    expect(board.day).toHaveLength(2);
    expect(board.pending[0]).toMatchObject({ carrierName: "Rossi", overdue: false, noShowAllowed: false });
    // After the 4-hour confirmation target the request is flagged.
    const later = await getBoard(db, clerkShared, siteIds["BMS-SFILIPPO"], "2026-11-10", new Date(NOW.getTime() + 5 * 3600_000));
    expect(later.pending[0].overdue).toBe(true);
  });

  it("confirms with an e-mail to the carrier and logs who did it", async () => {
    const b = await book();
    await staffSetStatus(db, clerkShared, { bookingId: b.id, to: "confirmed" }, { now: NOW, mailer });
    expect(sent.map((m) => m.subject)).toEqual([`Scarico confermato – ${b.code}`]);
    const ev = await db.execute(sql`select to_status, actor_type, actor_user_id from booking_event where booking_id = ${b.id} order by id`);
    expect(ev.rows.at(-1)).toEqual({ to_status: "confirmed", actor_type: "user", actor_user_id: clerkShared.id });
  });

  it("requires a reason to reject and sends it to the carrier", async () => {
    const b = await book();
    await expectCode(staffSetStatus(db, clerkShared, { bookingId: b.id, to: "rejected" }, { now: NOW, mailer }), "VALIDATION");
    await staffSetStatus(db, clerkShared, { bookingId: b.id, to: "rejected", note: "Magazzino pieno, prenoti giovedì" }, { now: NOW, mailer });
    expect(sent[0].subject).toBe(`Richiesta di scarico non accettata – ${b.code}`);
    expect(sent[0].text).toContain("Messaggio del magazzino: Magazzino pieno, prenoti giovedì");
    // The slot is free again.
    await book({ carrierName: "Next carrier" });
  });

  it("blocks other warehouses, clerk cancellations and changes to closed bookings", async () => {
    const b = await book();
    await expectCode(staffSetStatus(db, clerkFoiano, { bookingId: b.id, to: "confirmed" }, { now: NOW }), "FORBIDDEN");
    await expectCode(staffSetStatus(db, clerkShared, { bookingId: b.id, to: "cancelled" }, { now: NOW }), "FORBIDDEN");
    await staffSetStatus(db, manager, { bookingId: b.id, to: "cancelled", note: "Chiusura straordinaria" }, { now: NOW, mailer });
    expect(sent[0].text).toContain("Chiusura straordinaria");
    await expectCode(staffSetStatus(db, manager, { bookingId: b.id, to: "confirmed" }, { now: NOW }), "INVALID_TRANSITION");
    await expectCode(staffSetStatus(db, manager, { bookingId: 999999, to: "confirmed" }, { now: NOW }), "INVALID_TRANSITION");
  });

  it("runs the unloading flow on the day and allows no-show only when late", async () => {
    const a = await book({ time: "10:00" });
    const c = await book({ time: "14:00" });
    for (const id of [a.id, c.id]) await staffSetStatus(db, clerkShared, { bookingId: id, to: "confirmed" }, { now: NOW });

    const at = (t: string) => zonedToUtc("2026-11-10", t, TZ);
    await staffSetStatus(db, clerkShared, { bookingId: a.id, to: "arrived" }, { now: at("09:55") });
    await staffSetStatus(db, clerkShared, { bookingId: a.id, to: "unloading" }, { now: at("10:00") });
    await staffSetStatus(db, clerkShared, { bookingId: a.id, to: "completed" }, { now: at("10:25") });

    const board = await getBoard(db, clerkShared, siteIds["BMS-SFILIPPO"], "2026-11-10", at("14:20"));
    expect(board.day.map((b) => [b.time, b.status, b.noShowAllowed])).toEqual([
      ["10:00", "completed", true],
      ["14:00", "confirmed", false],
    ]);
    await expectCode(staffSetStatus(db, clerkShared, { bookingId: c.id, to: "no_show" }, { now: at("14:20") }), "TOO_EARLY");
    await staffSetStatus(db, clerkShared, { bookingId: c.id, to: "no_show" }, { now: at("14:31") });
  });
  it("lists upcoming bookings and per-day counts for the date strip", async () => {
    const a = await book({ date: "2026-11-10", time: "10:00" });
    const b = await book({ date: "2026-11-12", time: "09:00", pallets: 5 });
    const c = await book({ date: "2026-11-11", time: "11:00" });
    await staffSetStatus(db, clerkShared, { bookingId: b.id, to: "confirmed" }, { now: NOW });
    await staffSetStatus(db, manager, { bookingId: c.id, to: "cancelled" }, { now: NOW });

    const board = await getBoard(db, clerkShared, siteIds["BMS-SFILIPPO"], "2026-11-09", NOW);
    expect(board.upcoming.map((x) => [x.date, x.time, x.status])).toEqual([
      ["2026-11-10", "10:00", "requested"],
      ["2026-11-12", "09:00", "confirmed"],
    ]);
    expect(board.days).toHaveLength(14);
    const day = (d: string) => board.days.find((x) => x.date === d)!;
    expect(board.days[0]).toEqual({ date: "2026-11-09", bookings: 0, pending: 0, pallets: 0, open: true });
    expect(day("2026-11-10")).toMatchObject({ bookings: 1, pending: 1, pallets: 3 });
    expect(day("2026-11-11")).toMatchObject({ bookings: 0 }); // cancelled does not count
    expect(day("2026-11-12")).toMatchObject({ bookings: 1, pending: 0, pallets: 5 });
    expect(day("2026-11-14").open).toBe(false); // Saturday
    expect(day("2026-12-08")).toBeUndefined();

    // Strip starting elsewhere: 8 December (holiday) is shown closed.
    const dec = await getBoard(db, clerkShared, siteIds["BMS-SFILIPPO"], "2026-12-07", NOW, "2026-12-07");
    expect(dec.days[1]).toMatchObject({ date: "2026-12-08", open: false });
    expect(a.status).toBe("requested");
  });
});
