import { sql } from "drizzle-orm";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createDb, type Db } from "@/db/client";
import { runMigrations } from "@/db/migrate";
import { appUser, site, userSite } from "@/db/schema";
import { seed } from "@/db/seed";
import {
  addClosure,
  createUser,
  deleteClosure,
  getSettings,
  listAudit,
  listClosures,
  listUsers,
  resetPassword,
  resetTotp,
  updateEntity,
  updateSite,
  updateSlotRules,
  updateUser,
} from "@/lib/admin/service";
import { hashPassword } from "@/lib/auth/password";
import { authenticate, beginTotpSetup, confirmTotpSetup, createSession, getSessionUser } from "@/lib/auth/session";
import type { SessionUser } from "@/lib/auth/site-access";
import { totpCode } from "@/lib/auth/totp";
import { publicSlotRules } from "@/lib/booking/carrier";
import { BookingError } from "@/lib/booking/errors";
import { createBooking, getAvailability } from "@/lib/booking/service";
import { localTime, zonedToUtc } from "@/lib/time";

const url = process.env.TEST_DATABASE_URL;
const TZ = "Europe/Rome";
const NOW = zonedToUtc("2026-11-09", "10:00", TZ);

async function expectCode(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toSatisfy((e: unknown) => e instanceof BookingError && e.code === code);
}

describe.skipIf(!url)("administration (PostgreSQL)", () => {
  let db: Db;
  let close: () => Promise<void>;
  let siteIds: Record<string, number>;
  let admin: SessionUser;
  let manager: SessionUser; // Foiano
  let clerk: SessionUser;

  beforeAll(async () => {
    const c = createDb(url!);
    db = c.db;
    close = () => c.pool.end();
    await db.execute(sql`drop schema if exists public cascade`);
    await db.execute(sql`drop schema if exists drizzle cascade`);
    await db.execute(sql`create schema public`);
    await runMigrations(db);
  });

  beforeEach(async () => {
    await db.execute(sql`truncate booking, closure, opening_hours, booking_policy, slot_rule, user_site, staff_session, audit_log restart identity cascade`);
    await db.execute(sql`delete from app_user`);
    await seed(db, { holidayYears: [2026] });
    siteIds = Object.fromEntries((await db.select().from(site)).map((s) => [s.code, s.id]));
    const pw = await hashPassword("password-123");
    const [a, m, k] = await db
      .insert(appUser)
      .values([
        { email: "admin@casa-te.com", name: "Admin", role: "admin", passwordHash: pw },
        { email: "manager@casa-te.com", name: "Manager", role: "warehouse_manager", passwordHash: pw },
        { email: "clerk@casa-te.com", name: "Clerk", role: "warehouse_clerk", passwordHash: pw },
      ])
      .returning();
    await db.insert(userSite).values([
      { userId: m.id, siteId: siteIds.FOIANO },
      { userId: k.id, siteId: siteIds.FOIANO },
    ]);
    admin = { id: a.id, role: "admin", siteIds: [] };
    manager = { id: m.id, role: "warehouse_manager", siteIds: [siteIds.FOIANO] };
    clerk = { id: k.id, role: "warehouse_clerk", siteIds: [siteIds.FOIANO] };
  });

  afterAll(async () => close());

  const foianoSettings = async () => (await getSettings(db, admin)).sites.find((s) => s.code === "FOIANO")!;

  it("only admins see and change settings", async () => {
    await expectCode(getSettings(db, manager), "FORBIDDEN");
    const s = await foianoSettings();
    expect(s.hours).toHaveLength(5);
    expect(s.policy).toMatchObject({ maxDaysAhead: 14, bookingCutoffTime: "16:00", confirmSlaHours: 4 });
    expect(s.docks).toBe(1);
  });

  it("changes hours and rules, which carriers see at once, and reports bookings now outside", async () => {
    const b = await createBooking(
      db,
      { entityCode: 1, date: "2026-11-10", time: "17:00", vehicleType: "van", pallets: 2, parcels: 0, carrierName: "Late Co", contactEmail: "l@example.com" },
      { now: NOW },
    );
    const s = await foianoSettings();
    // Tuesdays close at 16:00 from now on, with a lunch break; Saturdays open in the morning.
    const hours = [
      ...s.hours.filter((h) => h.weekday !== 2),
      { weekday: 2, opens: "08:00", closes: "12:00" },
      { weekday: 2, opens: "13:00", closes: "16:00" },
      { weekday: 6, opens: "08:00", closes: "12:00" },
    ];
    const res = await updateSite(db, admin, s.id, { ...s, address: "Via Roma 1, Foiano della Chiana", hours, policy: { ...s.policy, maxDaysAhead: 30 } }, NOW);
    expect(res.outside).toEqual([{ code: b.code, date: "2026-11-10", time: "17:00", carrierName: "Late Co" }]);

    const times = (await getAvailability(db, { entityCode: 1, date: "2026-11-10", vehicleType: "van", pallets: 2, parcels: 0 }, NOW)).slots.map((x) =>
      localTime(x.start, TZ),
    );
    expect(times[0]).toBe("08:00");
    expect(times).toContain("11:30");
    expect(times).not.toContain("12:00");
    expect(times.at(-1)).toBe("15:30");
    const saturday = await getAvailability(db, { entityCode: 1, date: "2026-11-14", vehicleType: "van", pallets: 2, parcels: 0 }, NOW);
    expect(saturday.slots).toHaveLength(15); // 08:00–11:30 every 15 min
    expect((await foianoSettings()).address).toBe("Via Roma 1, Foiano della Chiana");
    expect((await listAudit(db, admin))[0]).toMatchObject({ action: "site.update", userName: "Admin" });
  });

  it("rejects inconsistent hours", async () => {
    const s = await foianoSettings();
    await expectCode(updateSite(db, admin, s.id, { ...s, hours: [{ weekday: 1, opens: "09:00", closes: "08:00" }] }), "VALIDATION");
    await expectCode(
      updateSite(db, admin, s.id, { ...s, hours: [{ weekday: 1, opens: "09:00", closes: "13:00" }, { weekday: 1, opens: "12:00", closes: "18:00" }] }),
      "VALIDATION",
    );
    await expectCode(updateSite(db, admin, s.id, { ...s, hours: [{ weekday: 1, opens: "09:10", closes: "18:00" }] }), "VALIDATION");
  });

  it("changes load limits and durations everywhere", async () => {
    const rules = (await getSettings(db, admin)).slotRules.map((r) => (r.vehicleType === "van" ? { ...r, maxPallets: 8, durationMin: 45 } : r));
    await updateSlotRules(db, admin, rules);
    expect((await publicSlotRules(db)).find((r) => r.vehicleType === "van")).toMatchObject({ maxPallets: 8, durationMin: 45 });
    const avail = await getAvailability(db, { entityCode: 2, date: "2026-11-10", vehicleType: "van", pallets: 8, parcels: 0 }, NOW);
    expect(avail.durationMin).toBe(45);
    await expectCode(updateSlotRules(db, admin, [{ vehicleType: "van", maxPallets: 6, maxParcels: null, durationMin: 20 }]), "VALIDATION");
  });

  it("deactivates a recipient so carriers cannot book it", async () => {
    const e = (await getSettings(db, admin)).entities.find((x) => x.code === 4)!;
    await updateEntity(db, admin, e.id, { legalName: "San Marco S.r.l.", isActive: false });
    await expectCode(
      createBooking(db, { entityCode: 4, date: "2026-11-10", time: "10:00", vehicleType: "van", pallets: 1, parcels: 0, carrierName: "x", contactEmail: "x@x.it" }, { now: NOW }),
      "ENTITY_NOT_FOUND",
    );
  });

  it("lets managers close their own warehouse and lists bookings already on that day", async () => {
    await createBooking(
      db,
      { entityCode: 1, date: "2026-11-12", time: "09:30", vehicleType: "van", pallets: 2, parcels: 0, carrierName: "Early Co", contactEmail: "e@example.com" },
      { now: NOW },
    );
    const res = await addClosure(db, manager, { siteId: siteIds.FOIANO, date: "2026-11-12", reason: "Inventario" }, NOW);
    expect(res.affected).toEqual([{ code: expect.any(String), time: "09:30", carrierName: "Early Co", siteName: "Magazzino Foiano" }]);
    expect((await getAvailability(db, { entityCode: 1, date: "2026-11-12", vehicleType: "van", pallets: 2, parcels: 0 }, NOW)).slots).toEqual([]);
    expect((await getAvailability(db, { entityCode: 2, date: "2026-11-12", vehicleType: "van", pallets: 2, parcels: 0 }, NOW)).slots.length).toBeGreaterThan(0);

    await expectCode(addClosure(db, manager, { siteId: siteIds.MARLIA, date: "2026-11-12", reason: "x" }, NOW), "FORBIDDEN");
    await expectCode(addClosure(db, manager, { siteId: null, date: "2026-11-13", reason: "x" }, NOW), "FORBIDDEN");
    await expectCode(addClosure(db, clerk, { siteId: siteIds.FOIANO, date: "2026-11-13", reason: "x" }, NOW), "FORBIDDEN");
    await expectCode(addClosure(db, manager, { siteId: siteIds.FOIANO, date: "2026-11-12", reason: "again" }, NOW), "CONFLICT");
    await expectCode(addClosure(db, manager, { siteId: siteIds.FOIANO, date: "2026-11-01", reason: "past" }, NOW), "VALIDATION");

    const list = await listClosures(db, manager, NOW);
    const mine = list.find((c) => c.reason === "Inventario")!;
    expect(mine.editable).toBe(true);
    expect(list.find((c) => c.siteId === null)?.editable).toBe(false); // national holiday: read-only
    await expectCode(deleteClosure(db, manager, list.find((c) => c.siteId === null)!.id), "FORBIDDEN");
    await deleteClosure(db, manager, mine.id);
  });

  it("manages accounts with temporary passwords and keeps at least one admin", async () => {
    const { id, password } = await createUser(db, admin, { email: "New.User@Casa-TE.com", name: "Nuovo", role: "warehouse_clerk", siteIds: [siteIds.MARLIA] });
    expect(password).toHaveLength(16);
    expect((await authenticate(db, "new.user@casa-te.com", password, undefined)).status).toBe("ok");
    await expectCode(createUser(db, admin, { email: "new.user@casa-te.com", name: "Dup", role: "warehouse_clerk", siteIds: [siteIds.MARLIA] }), "CONFLICT");
    await expectCode(createUser(db, admin, { email: "x@casa-te.com", name: "No site", role: "warehouse_clerk", siteIds: [] }), "VALIDATION");
    await expectCode(createUser(db, manager, { email: "y@casa-te.com", name: "Y", role: "admin", siteIds: [] }), "FORBIDDEN");

    // Promote, then a role change signs the user out.
    const { token } = await createSession(db, id, NOW, true);
    await updateUser(db, admin, id, { name: "Nuovo", role: "warehouse_manager", siteIds: [siteIds.MARLIA, siteIds.FOIANO] });
    expect(await getSessionUser(db, token, NOW)).toBeNull();
    expect((await listUsers(db, admin)).find((u) => u.id === id)).toMatchObject({ role: "warehouse_manager", siteIds: [siteIds.MARLIA, siteIds.FOIANO] });

    const { password: p2 } = await resetPassword(db, admin, id);
    expect((await authenticate(db, "new.user@casa-te.com", password, undefined)).status).toBe("bad_credentials");
    expect((await authenticate(db, "new.user@casa-te.com", p2, undefined)).status).toBe("ok");

    await expectCode(updateUser(db, admin, admin.id, { name: "Admin", role: "warehouse_manager", siteIds: [siteIds.FOIANO] }), "CONFLICT");
    await expectCode(updateUser(db, admin, admin.id, { name: "Admin", role: "admin", siteIds: [], isActive: false }), "CONFLICT");
    // With a second admin, the first can be demoted by the second.
    const second = await createUser(db, admin, { email: "admin2@casa-te.com", name: "Admin 2", role: "admin", siteIds: [] });
    await updateUser(db, { id: second.id, role: "admin", siteIds: [] }, admin.id, { name: "Admin", role: "warehouse_manager", siteIds: [siteIds.FOIANO] });
    await expectCode(
      updateUser(db, { id: second.id, role: "admin", siteIds: [] }, second.id, { name: "Admin 2", role: "admin", siteIds: [], isActive: false }),
      "CONFLICT",
    );
  });

  it("requires the authenticator after setup, refuses reused codes, and can be reset", async () => {
    const user = await getSessionUserFor("clerk@casa-te.com");
    expect(user.mfaVerified).toBe(false);
    const { secret, qrSvg, otpauthUrl } = await beginTotpSetup(db, user);
    expect(qrSvg).toContain("<svg");
    expect(otpauthUrl).toContain("clerk%40casa-te.com");
    expect(await confirmTotpSetup(db, user, user.token, "000000", NOW)).toBe(false);
    expect(await confirmTotpSetup(db, user, user.token, totpCode(secret, NOW), NOW)).toBe(true);
    expect((await getSessionUser(db, user.token, NOW))!.mfaVerified).toBe(true);

    const later = new Date(NOW.getTime() + 60_000);
    expect(await authenticate(db, "clerk@casa-te.com", "password-123", undefined, later)).toEqual({ status: "totp_required" });
    expect((await authenticate(db, "clerk@casa-te.com", "password-123", "123456", later)).status).toBe("bad_totp");
    const code = totpCode(secret, later);
    expect(await authenticate(db, "clerk@casa-te.com", "password-123", code, later)).toMatchObject({ status: "ok", mfaVerified: true });
    expect((await authenticate(db, "clerk@casa-te.com", "password-123", code, later)).status).toBe("bad_totp"); // replay

    await resetTotp(db, admin, clerk.id);
    expect((await getSessionUser(db, user.token, NOW))).toBeNull(); // signed out
    expect(await authenticate(db, "clerk@casa-te.com", "password-123", undefined, later)).toMatchObject({ status: "ok", mfaVerified: false });
  });

  async function getSessionUserFor(email: string) {
    const r = await authenticate(db, email, "password-123", undefined, NOW);
    if (r.status !== "ok") throw new Error(r.status);
    const { token } = await createSession(db, r.userId, NOW, r.mfaVerified);
    return { ...(await getSessionUser(db, token, NOW))!, token };
  }
});
