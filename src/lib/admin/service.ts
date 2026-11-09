// Administration: warehouse settings, closures, durations, recipients, staff accounts.
// Admins can change everything; warehouse managers can manage closures of their own sites.
import { randomBytes } from "node:crypto";
import { and, asc, count, desc, eq, gte, inArray, isNull, lt, ne } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import {
  appUser,
  auditLog,
  booking,
  bookingPolicy,
  closure,
  dock,
  entity,
  openingHours,
  site,
  slotRule,
  staffSession,
  userRole,
  userSite,
  vehicleType,
} from "@/db/schema";
import { hashPassword } from "@/lib/auth/password";
import type { SessionUser } from "@/lib/auth/site-access";
import { BookingError, PG_UNIQUE_VIOLATION, pgErrorCode } from "@/lib/booking/errors";
import { LIVE_STATUSES } from "@/lib/booking/service";
import { DEFAULT_SLOT_RULES } from "@/lib/scheduling/duration";
import { addDays, isoWeekday, localDate, localTime, toMinutes, zonedToUtc } from "@/lib/time";

type Tx = Parameters<Parameters<Db["transaction"]>[0]>[0];

function requireAdmin(user: SessionUser) {
  if (user.role !== "admin") throw new BookingError("FORBIDDEN", "Admins only");
}

function invalid(error: z.ZodError): never {
  throw new BookingError("VALIDATION", z.prettifyError(error));
}

async function audit(db: Db | Tx, user: SessionUser, action: string, target: string, details?: unknown) {
  await db.insert(auditLog).values({ userId: user.id, action, target, details: details ?? null });
}

// ---------------------------------------------------------------- settings

const quarter = z
  .string()
  .regex(/^([01]\d|2[0-3]):(00|15|30|45)$/, "Use HH:MM on the quarter hour (e.g. 09:00, 13:30)");

export const siteSettingsSchema = z
  .object({
    name: z.string().trim().min(1).max(120),
    address: z
      .string()
      .trim()
      .max(200)
      .nullish()
      .transform((v) => v || null),
    hours: z.array(z.object({ weekday: z.number().int().min(1).max(7), opens: quarter, closes: quarter })).max(21),
    policy: z.object({
      maxDaysAhead: z.number().int().min(1).max(90),
      bookingCutoffTime: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
      cancelCutoffHours: z.number().int().min(0).max(72),
      noShowAfterMin: z.number().int().min(5).max(240),
      confirmSlaHours: z.number().int().min(1).max(72),
      dailyPalletCap: z.number().int().min(1).max(2000).nullable(),
    }),
  })
  .superRefine((v, ctx) => {
    for (let d = 1; d <= 7; d++) {
      const ranges = v.hours.filter((h) => h.weekday === d).sort((a, b) => a.opens.localeCompare(b.opens));
      ranges.forEach((r, i) => {
        if (r.closes <= r.opens) ctx.addIssue({ code: "custom", message: `Day ${d}: closing must be after opening`, path: ["hours"] });
        if (i > 0 && r.opens < ranges[i - 1].closes) ctx.addIssue({ code: "custom", message: `Day ${d}: time ranges overlap`, path: ["hours"] });
      });
    }
  });

export type SiteSettingsInput = z.input<typeof siteSettingsSchema>;

export async function getSettings(db: Db, user: SessionUser) {
  requireAdmin(user);
  const [sites, hours, policies, docks, entities, rules] = await Promise.all([
    db.select().from(site).orderBy(asc(site.id)),
    db.select().from(openingHours).orderBy(asc(openingHours.weekday), asc(openingHours.opens)),
    db.select().from(bookingPolicy),
    db.select({ siteId: dock.siteId, n: count() }).from(dock).where(eq(dock.isActive, true)).groupBy(dock.siteId),
    db.select().from(entity).orderBy(asc(entity.code)),
    db.select().from(slotRule).where(isNull(slotRule.siteId)),
  ]);
  return {
    sites: sites.map((s) => {
      const p = policies.find((x) => x.siteId === s.id);
      return {
        id: s.id,
        code: s.code,
        name: s.name,
        address: s.address,
        docks: docks.find((d) => d.siteId === s.id)?.n ?? 0,
        hours: hours.filter((h) => h.siteId === s.id).map((h) => ({ weekday: h.weekday, opens: h.opens.slice(0, 5), closes: h.closes.slice(0, 5) })),
        policy: {
          maxDaysAhead: p?.maxDaysAhead ?? 14,
          bookingCutoffTime: (p?.bookingCutoffTime ?? "16:00").slice(0, 5),
          cancelCutoffHours: p?.cancelCutoffHours ?? 12,
          noShowAfterMin: p?.noShowAfterMin ?? 30,
          confirmSlaHours: p?.confirmSlaHours ?? 4,
          dailyPalletCap: p?.dailyPalletCap ?? null,
        },
      };
    }),
    entities: entities.map((e) => ({ id: e.id, code: e.code, name: e.name, legalName: e.legalName, siteId: e.siteId, isActive: e.isActive })),
    slotRules: DEFAULT_SLOT_RULES.map((d) => {
      const r = rules.find((x) => x.vehicleType === d.vehicleType);
      return r ? { vehicleType: r.vehicleType, maxPallets: r.maxPallets, maxParcels: r.maxParcels, durationMin: r.durationMin } : d;
    }),
  };
}

export interface OutsideBooking {
  code: string;
  date: string;
  time: string;
  carrierName: string;
}

/** Saves a site's details, receiving hours and booking rules. Returns future bookings now outside the hours. */
export async function updateSite(db: Db, user: SessionUser, siteId: number, raw: SiteSettingsInput, now = new Date()) {
  requireAdmin(user);
  const parsed = siteSettingsSchema.safeParse(raw);
  if (!parsed.success) invalid(parsed.error);
  const v = parsed.data;
  const [s] = await db.select({ id: site.id, tz: site.timezone }).from(site).where(eq(site.id, siteId));
  if (!s) throw new BookingError("NOT_FOUND", "Unknown site");

  await db.transaction(async (tx) => {
    await tx.update(site).set({ name: v.name, address: v.address }).where(eq(site.id, siteId));
    await tx.delete(openingHours).where(eq(openingHours.siteId, siteId));
    if (v.hours.length) await tx.insert(openingHours).values(v.hours.map((h) => ({ siteId, ...h })));
    await tx
      .insert(bookingPolicy)
      .values({ siteId, ...v.policy })
      .onConflictDoUpdate({ target: bookingPolicy.siteId, set: v.policy });
    await audit(tx, user, "site.update", `site:${siteId}`, v);
  });

  // Existing bookings are kept; list those that no longer fit so staff can contact the carriers.
  const future = await db
    .select({ code: booking.code, startAt: booking.startAt, endAt: booking.endAt, carrierName: booking.carrierName })
    .from(booking)
    .where(and(eq(booking.siteId, siteId), inArray(booking.status, LIVE_STATUSES), gte(booking.startAt, now)))
    .orderBy(asc(booking.startAt));
  const outside: OutsideBooking[] = future
    .filter((b) => {
      const date = localDate(b.startAt, s.tz);
      const start = toMinutes(localTime(b.startAt, s.tz));
      const end = start + (b.endAt.getTime() - b.startAt.getTime()) / 60000;
      return !v.hours.some((h) => h.weekday === isoWeekday(date) && toMinutes(h.opens) <= start && end <= toMinutes(h.closes));
    })
    .map((b) => ({ code: b.code, date: localDate(b.startAt, s.tz), time: localTime(b.startAt, s.tz), carrierName: b.carrierName }));
  return { outside };
}

export async function updateEntity(db: Db, user: SessionUser, entityId: number, raw: { legalName?: string | null; isActive: boolean }) {
  requireAdmin(user);
  const parsed = z
    .object({ legalName: z.string().trim().max(200).nullish().transform((v) => v || null), isActive: z.boolean() })
    .safeParse(raw);
  if (!parsed.success) invalid(parsed.error);
  const res = await db.update(entity).set(parsed.data).where(eq(entity.id, entityId)).returning({ id: entity.id });
  if (!res.length) throw new BookingError("NOT_FOUND", "Unknown recipient");
  await audit(db, user, "entity.update", `entity:${entityId}`, parsed.data);
}

const ruleSchema = z.array(
  z.object({
    vehicleType: z.enum(vehicleType.enumValues),
    maxPallets: z.number().int().min(0).max(40),
    maxParcels: z.number().int().min(1).max(999).nullable(),
    durationMin: z.number().int().min(15).max(240).multipleOf(15),
  }),
);

/** Default unloading durations and load limits (all sites). */
export async function updateSlotRules(db: Db, user: SessionUser, raw: z.input<typeof ruleSchema>) {
  requireAdmin(user);
  const parsed = ruleSchema.safeParse(raw);
  if (!parsed.success) invalid(parsed.error);
  const types = parsed.data.map((r) => r.vehicleType);
  if (new Set(types).size !== types.length) throw new BookingError("VALIDATION", "Each vehicle type once");
  await db.transaction(async (tx) => {
    for (const r of parsed.data) {
      await tx.delete(slotRule).where(and(isNull(slotRule.siteId), eq(slotRule.vehicleType, r.vehicleType)));
      await tx.insert(slotRule).values({ siteId: null, ...r });
    }
    await audit(tx, user, "slot_rules.update", "slot_rule:global", parsed.data);
  });
}

// ---------------------------------------------------------------- closures

function closureSitesFor(user: SessionUser): number[] | "all" {
  if (user.role === "admin") return "all";
  if (user.role === "warehouse_manager") return user.siteIds;
  throw new BookingError("FORBIDDEN", "Managers and admins only");
}

export async function listClosures(db: Db, user: SessionUser, now = new Date()) {
  const allowed = closureSitesFor(user);
  const today = localDate(now, "Europe/Rome");
  const rows = await db
    .select({ id: closure.id, siteId: closure.siteId, siteName: site.name, date: closure.date, reason: closure.reason })
    .from(closure)
    .leftJoin(site, eq(site.id, closure.siteId))
    .where(gte(closure.date, today))
    .orderBy(asc(closure.date));
  return rows
    .filter((r) => allowed === "all" || r.siteId === null || allowed.includes(r.siteId))
    .map((r) => ({ ...r, editable: allowed === "all" || (r.siteId !== null && allowed.includes(r.siteId)) }));
}

const closureSchema = z.object({
  siteId: z.number().int().nullable(),
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  reason: z.string().trim().min(1).max(120),
});

/** Closes a site (or all sites) for a day. Existing bookings are kept and returned so staff can act on them. */
export async function addClosure(db: Db, user: SessionUser, raw: z.input<typeof closureSchema>, now = new Date()) {
  const allowed = closureSitesFor(user);
  const parsed = closureSchema.safeParse(raw);
  if (!parsed.success) invalid(parsed.error);
  const v = parsed.data;
  if (allowed !== "all" && (v.siteId === null || !allowed.includes(v.siteId))) {
    throw new BookingError("FORBIDDEN", "Only your own warehouses");
  }
  if (v.date < localDate(now, "Europe/Rome")) throw new BookingError("VALIDATION", "The date is in the past");

  try {
    await db.insert(closure).values(v);
  } catch (e) {
    if (pgErrorCode(e) === PG_UNIQUE_VIOLATION) throw new BookingError("CONFLICT", "Already closed on that day");
    throw e;
  }
  await audit(db, user, "closure.add", v.siteId ? `site:${v.siteId}` : "site:all", v);

  const dayStart = zonedToUtc(v.date, "00:00", "Europe/Rome");
  const dayEnd = zonedToUtc(addDays(v.date, 1), "00:00", "Europe/Rome");
  const affected = await db
    .select({ code: booking.code, startAt: booking.startAt, carrierName: booking.carrierName, siteName: site.name })
    .from(booking)
    .innerJoin(site, eq(site.id, booking.siteId))
    .where(
      and(
        v.siteId === null ? undefined : eq(booking.siteId, v.siteId),
        inArray(booking.status, LIVE_STATUSES),
        gte(booking.startAt, dayStart),
        lt(booking.startAt, dayEnd),
      ),
    )
    .orderBy(asc(booking.startAt));
  return {
    affected: affected.map((b) => ({ code: b.code, time: localTime(b.startAt, "Europe/Rome"), carrierName: b.carrierName, siteName: b.siteName })),
  };
}

export async function deleteClosure(db: Db, user: SessionUser, closureId: number) {
  const allowed = closureSitesFor(user);
  const [c] = await db.select().from(closure).where(eq(closure.id, closureId));
  if (!c) throw new BookingError("NOT_FOUND", "Unknown closure");
  if (allowed !== "all" && (c.siteId === null || !allowed.includes(c.siteId))) {
    throw new BookingError("FORBIDDEN", "Only your own warehouses");
  }
  await db.delete(closure).where(eq(closure.id, closureId));
  await audit(db, user, "closure.delete", c.siteId ? `site:${c.siteId}` : "site:all", { date: c.date, reason: c.reason });
}

// ---------------------------------------------------------------- users

const userSchema = z.object({
  name: z.string().trim().min(1).max(120),
  role: z.enum(userRole.enumValues),
  siteIds: z.array(z.number().int()).max(50),
  isActive: z.boolean().default(true),
});

export async function listUsers(db: Db, user: SessionUser) {
  requireAdmin(user);
  const [users, links] = await Promise.all([
    db
      .select({
        id: appUser.id,
        email: appUser.email,
        name: appUser.name,
        role: appUser.role,
        isActive: appUser.isActive,
        totpSecret: appUser.totpSecret,
        createdAt: appUser.createdAt,
      })
      .from(appUser)
      .orderBy(desc(appUser.isActive), asc(appUser.name)),
    db.select().from(userSite),
  ]);
  return users.map(({ totpSecret, ...u }) => ({
    ...u,
    hasTotp: !!totpSecret,
    siteIds: links.filter((l) => l.userId === u.id).map((l) => l.siteId),
  }));
}

function tempPassword(): string {
  return randomBytes(12).toString("base64url"); // 16 characters
}

async function setSites(tx: Tx, userId: number, role: string, siteIds: number[]) {
  await tx.delete(userSite).where(eq(userSite.userId, userId));
  if (role !== "admin" && siteIds.length) {
    await tx.insert(userSite).values([...new Set(siteIds)].map((siteId) => ({ userId, siteId })));
  }
}

/** Creates an account and returns its one-time temporary password. */
export async function createUser(db: Db, admin: SessionUser, raw: z.input<typeof userSchema> & { email: string }) {
  requireAdmin(admin);
  const parsed = userSchema.extend({ email: z.string().trim().toLowerCase().pipe(z.email()) }).safeParse(raw);
  if (!parsed.success) invalid(parsed.error);
  const v = parsed.data;
  if (v.role !== "admin" && !v.siteIds.length) throw new BookingError("VALIDATION", "Choose at least one warehouse");
  const password = tempPassword();
  const passwordHash = await hashPassword(password);
  try {
    const id = await db.transaction(async (tx) => {
      const [u] = await tx
        .insert(appUser)
        .values({ email: v.email, name: v.name, role: v.role, isActive: v.isActive, passwordHash })
        .returning({ id: appUser.id });
      await setSites(tx, u.id, v.role, v.siteIds);
      await audit(tx, admin, "user.create", `user:${u.id}`, { email: v.email, role: v.role, siteIds: v.siteIds });
      return u.id;
    });
    return { id, password };
  } catch (e) {
    if (pgErrorCode(e) === PG_UNIQUE_VIOLATION) throw new BookingError("CONFLICT", "This e-mail already has an account");
    throw e;
  }
}

async function assertOtherActiveAdmin(db: Db | Tx, exceptUserId: number) {
  const [{ n }] = await db
    .select({ n: count() })
    .from(appUser)
    .where(and(eq(appUser.role, "admin"), eq(appUser.isActive, true), ne(appUser.id, exceptUserId)));
  if (n === 0) throw new BookingError("CONFLICT", "There must always be at least one active administrator");
}

export async function updateUser(db: Db, admin: SessionUser, userId: number, raw: z.input<typeof userSchema>) {
  requireAdmin(admin);
  const parsed = userSchema.safeParse(raw);
  if (!parsed.success) invalid(parsed.error);
  const v = parsed.data;
  if (v.role !== "admin" && !v.siteIds.length) throw new BookingError("VALIDATION", "Choose at least one warehouse");
  const [current] = await db.select({ role: appUser.role, isActive: appUser.isActive }).from(appUser).where(eq(appUser.id, userId));
  if (!current) throw new BookingError("NOT_FOUND", "Unknown user");
  if (userId === admin.id && (!v.isActive || v.role !== "admin")) {
    throw new BookingError("CONFLICT", "You cannot remove your own administrator access");
  }

  await db.transaction(async (tx) => {
    if (current.role === "admin" && current.isActive && (v.role !== "admin" || !v.isActive)) await assertOtherActiveAdmin(tx, userId);
    await tx.update(appUser).set({ name: v.name, role: v.role, isActive: v.isActive }).where(eq(appUser.id, userId));
    await setSites(tx, userId, v.role, v.siteIds);
    // Less access, or disabled: sign out everywhere.
    if (!v.isActive || v.role !== current.role) await tx.delete(staffSession).where(eq(staffSession.userId, userId));
    await audit(tx, admin, "user.update", `user:${userId}`, v);
  });
}

/** New temporary password; the user is signed out everywhere. */
export async function resetPassword(db: Db, admin: SessionUser, userId: number) {
  requireAdmin(admin);
  const password = tempPassword();
  const res = await db
    .update(appUser)
    .set({ passwordHash: await hashPassword(password) })
    .where(eq(appUser.id, userId))
    .returning({ id: appUser.id });
  if (!res.length) throw new BookingError("NOT_FOUND", "Unknown user");
  await db.delete(staffSession).where(eq(staffSession.userId, userId));
  await audit(db, admin, "user.reset_password", `user:${userId}`);
  return { password };
}

/** For a lost phone: the user sets up the authenticator again at next sign-in. */
export async function resetTotp(db: Db, admin: SessionUser, userId: number) {
  requireAdmin(admin);
  const res = await db
    .update(appUser)
    .set({ totpSecret: null, totpPendingSecret: null, totpLastStep: null })
    .where(eq(appUser.id, userId))
    .returning({ id: appUser.id });
  if (!res.length) throw new BookingError("NOT_FOUND", "Unknown user");
  await db.delete(staffSession).where(eq(staffSession.userId, userId));
  await audit(db, admin, "user.reset_2fa", `user:${userId}`);
}

export async function listAudit(db: Db, user: SessionUser, limit = 100) {
  requireAdmin(user);
  return db
    .select({ id: auditLog.id, at: auditLog.createdAt, userName: appUser.name, action: auditLog.action, target: auditLog.target })
    .from(auditLog)
    .leftJoin(appUser, eq(appUser.id, auditLog.userId))
    .orderBy(desc(auditLog.id))
    .limit(limit);
}
