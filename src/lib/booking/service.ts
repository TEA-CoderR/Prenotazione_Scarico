import { createHash, randomBytes, randomInt } from "node:crypto";
import { and, eq, gt, inArray, isNull, lt, or, sql } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import {
  booking,
  bookingEvent,
  bookingPolicy,
  closure,
  dock,
  entity,
  openingHours,
  site,
  slotRule,
  vehicleType,
  type ActorType,
  type BookingStatus,
} from "@/db/schema";
import { noopOrderValidator, type OrderValidator } from "@/lib/orders/validator";
import {
  freeSlotsForDock,
  isDateBookable,
  type BookingWindowPolicy,
  type Interval,
  type OpeningRange,
} from "@/lib/scheduling/availability";
import { DEFAULT_SLOT_RULES, LoadNotAllowedError, resolveDuration, type SlotRule } from "@/lib/scheduling/duration";
import { addDays, isoWeekday, localDate, zonedToUtc } from "@/lib/time";
import { BookingError, PG_EXCLUSION_VIOLATION, PG_UNIQUE_VIOLATION, pgErrorCode } from "./errors";

/** Statuses that hold a dock slot. Must match the WHERE clause of booking_no_overlap. */
export const LIVE_STATUSES: BookingStatus[] = ["requested", "confirmed", "arrived", "unloading"];

const ymd = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const hhmm = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);
const optText = (max: number) =>
  z
    .string()
    .trim()
    .max(max)
    .optional()
    .transform((v) => (v ? v : null));

export const loadSchema = z.object({
  vehicleType: z.enum(vehicleType.enumValues),
  pallets: z.number().int().min(0).max(40),
  parcels: z.number().int().min(0).max(999),
});

export const createBookingSchema = loadSchema.extend({
  entityCode: z.number().int().min(0),
  date: ymd,
  time: hhmm,
  carrierName: z.string().trim().min(1).max(120),
  contactEmail: z.string().trim().toLowerCase().pipe(z.email()),
  driverName: optText(120),
  driverPhone: optText(40),
  plate: z
    .string()
    .trim()
    .max(20)
    .optional()
    .transform((v) => (v ? v.replace(/\s+/g, "").toUpperCase() : null)),
  supplier: optText(120),
  orderNo: optText(60),
  ddtNo: optText(60),
  notes: optText(500),
  lang: z.enum(["it", "en"]).default("it"),
});

export type CreateBookingInput = z.input<typeof createBookingSchema>;

interface SiteContext {
  entityId: number;
  entityCode: number;
  siteId: number;
  tz: string;
  policy: BookingWindowPolicy & {
    cancelCutoffHours: number;
    noShowAfterMin: number;
    dailyPalletCap: number | null;
  };
  rules: SlotRule[];
  dockIds: number[];
}

export async function loadSiteContext(db: Db, entityCode: number): Promise<SiteContext> {
  const [row] = await db
    .select({
      entityId: entity.id,
      siteId: site.id,
      tz: site.timezone,
      policy: bookingPolicy,
    })
    .from(entity)
    .innerJoin(site, eq(site.id, entity.siteId))
    .leftJoin(bookingPolicy, eq(bookingPolicy.siteId, site.id))
    .where(and(eq(entity.code, entityCode), eq(entity.isActive, true), eq(site.isActive, true)));
  if (!row) throw new BookingError("ENTITY_NOT_FOUND", `Unknown entity ${entityCode}`);

  const ruleRows = await db
    .select()
    .from(slotRule)
    .where(or(eq(slotRule.siteId, row.siteId), isNull(slotRule.siteId)));
  // Site-specific rule overrides the global one, which overrides the built-in default.
  const rules = DEFAULT_SLOT_RULES.map((def) => {
    const pick =
      ruleRows.find((r) => r.siteId === row.siteId && r.vehicleType === def.vehicleType) ??
      ruleRows.find((r) => r.siteId === null && r.vehicleType === def.vehicleType);
    return pick
      ? { vehicleType: pick.vehicleType, maxPallets: pick.maxPallets, maxParcels: pick.maxParcels, durationMin: pick.durationMin }
      : def;
  });

  const docks = await db
    .select({ id: dock.id })
    .from(dock)
    .where(and(eq(dock.siteId, row.siteId), eq(dock.isActive, true)))
    .orderBy(dock.id);

  const p = row.policy;
  return {
    entityId: row.entityId,
    entityCode,
    siteId: row.siteId,
    tz: row.tz,
    policy: {
      maxDaysAhead: p?.maxDaysAhead ?? 14,
      bookingCutoffTime: (p?.bookingCutoffTime ?? "16:00").slice(0, 5),
      cancelCutoffHours: p?.cancelCutoffHours ?? 12,
      noShowAfterMin: p?.noShowAfterMin ?? 30,
      dailyPalletCap: p?.dailyPalletCap ?? null,
    },
    rules,
    dockIds: docks.map((d) => d.id),
  };
}

async function openingFor(db: Db, ctx: SiteContext, date: string): Promise<OpeningRange[]> {
  const closed = await db
    .select({ id: closure.id })
    .from(closure)
    .where(and(eq(closure.date, date), or(eq(closure.siteId, ctx.siteId), isNull(closure.siteId))))
    .limit(1);
  if (closed.length) return [];
  const rows = await db
    .select({ opens: openingHours.opens, closes: openingHours.closes })
    .from(openingHours)
    .where(and(eq(openingHours.siteId, ctx.siteId), eq(openingHours.weekday, isoWeekday(date))));
  return rows.map((r) => ({ opens: r.opens.slice(0, 5), closes: r.closes.slice(0, 5) }));
}

async function busyByDock(db: Db, ctx: SiteContext, date: string): Promise<Map<number, Interval[]>> {
  const dayStart = zonedToUtc(date, "00:00", ctx.tz);
  const dayEnd = zonedToUtc(addDays(date, 1), "00:00", ctx.tz);
  const rows = await db
    .select({ dockId: booking.dockId, start: booking.startAt, end: booking.endAt })
    .from(booking)
    .where(
      and(
        eq(booking.siteId, ctx.siteId),
        inArray(booking.status, LIVE_STATUSES),
        lt(booking.startAt, dayEnd),
        gt(booking.endAt, dayStart),
      ),
    );
  const map = new Map<number, Interval[]>(ctx.dockIds.map((id) => [id, []]));
  for (const r of rows) map.get(r.dockId)?.push({ start: r.start, end: r.end });
  return map;
}

function durationOrThrow(ctx: SiteContext, load: z.infer<typeof loadSchema>): number {
  try {
    return resolveDuration(ctx.rules, load.vehicleType, load.pallets, load.parcels);
  } catch (e) {
    if (e instanceof LoadNotAllowedError) throw new BookingError("LOAD_NOT_ALLOWED", e.message);
    throw e;
  }
}

export interface AvailabilityResult {
  date: string;
  durationMin: number;
  /** Start times with at least one free dock, in chronological order. */
  slots: Interval[];
}

/** Free slots shown to the carrier for an entity, date and load. */
export async function getAvailability(
  db: Db,
  params: { entityCode: number; date: string } & z.input<typeof loadSchema>,
  now: Date = new Date(),
): Promise<AvailabilityResult> {
  const load = loadSchema.parse(params);
  const ctx = await loadSiteContext(db, params.entityCode);
  const durationMin = durationOrThrow(ctx, load);
  if (!isDateBookable(params.date, now, ctx.tz, ctx.policy)) {
    return { date: params.date, durationMin, slots: [] };
  }
  const opening = await openingFor(db, ctx, params.date);
  if (!opening.length) return { date: params.date, durationMin, slots: [] };

  const busy = await busyByDock(db, ctx, params.date);
  const byStart = new Map<number, Interval>();
  for (const dockId of ctx.dockIds) {
    const free = freeSlotsForDock({ date: params.date, tz: ctx.tz, opening, durationMin, busy: busy.get(dockId) ?? [] });
    for (const s of free) byStart.set(s.start.getTime(), s);
  }
  const slots = [...byStart.values()].sort((a, b) => a.start.getTime() - b.start.getTime());
  return { date: params.date, durationMin, slots };
}

const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

function bookingCode(date: string): string {
  let suffix = "";
  for (let i = 0; i < 5; i++) suffix += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return `P${date.slice(2).replaceAll("-", "")}-${suffix}`;
}

export function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

export interface CreatedBooking {
  id: number;
  code: string;
  siteId: number;
  dockId: number;
  startAt: Date;
  endAt: Date;
  status: BookingStatus;
  /** Plain token for the carrier's manage link. Only its hash is stored. */
  manageToken: string;
}

/**
 * Creates a booking in status "requested" (the warehouse confirms it manually).
 * The slot is held immediately; a concurrent request for the same slot fails with
 * SLOT_UNAVAILABLE thanks to the database exclusion constraint.
 */
export async function createBooking(
  db: Db,
  rawInput: CreateBookingInput,
  opts: { now?: Date; orderValidator?: OrderValidator } = {},
): Promise<CreatedBooking> {
  const parsed = createBookingSchema.safeParse(rawInput);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const input = parsed.data;
  const now = opts.now ?? new Date();
  const validator = opts.orderValidator ?? noopOrderValidator;

  const ctx = await loadSiteContext(db, input.entityCode);
  const durationMin = durationOrThrow(ctx, input);
  if (!isDateBookable(input.date, now, ctx.tz, ctx.policy)) {
    throw new BookingError("DATE_NOT_BOOKABLE", `${input.date} is outside the booking window`);
  }
  const opening = await openingFor(db, ctx, input.date);
  if (!opening.length) throw new BookingError("SITE_CLOSED", `Site closed on ${input.date}`);

  const startAt = zonedToUtc(input.date, input.time, ctx.tz);
  const endAt = new Date(startAt.getTime() + durationMin * 60000);

  // Docks where this exact start is a valid, currently free slot.
  const busy = await busyByDock(db, ctx, input.date);
  const candidateDocks = ctx.dockIds.filter((dockId) =>
    freeSlotsForDock({ date: input.date, tz: ctx.tz, opening, durationMin, busy: busy.get(dockId) ?? [] }).some(
      (s) => s.start.getTime() === startAt.getTime(),
    ),
  );
  if (!candidateDocks.length) throw new BookingError("SLOT_UNAVAILABLE", "This time is no longer available");

  const order = await validator.check({ entityCode: input.entityCode, orderNo: input.orderNo, supplier: input.supplier });
  if (!order.ok) throw new BookingError("ORDER_INVALID", order.reason);

  const manageToken = randomBytes(24).toString("base64url");

  return db.transaction(async (tx) => {
    if (ctx.policy.dailyPalletCap !== null) {
      const dayStart = zonedToUtc(input.date, "00:00", ctx.tz);
      const dayEnd = zonedToUtc(addDays(input.date, 1), "00:00", ctx.tz);
      const [{ total }] = await tx
        .select({ total: sql<number>`coalesce(sum(${booking.pallets}), 0)::int` })
        .from(booking)
        .where(
          and(
            eq(booking.siteId, ctx.siteId),
            inArray(booking.status, LIVE_STATUSES),
            sql`${booking.startAt} >= ${dayStart} and ${booking.startAt} < ${dayEnd}`,
          ),
        );
      if (total + input.pallets > ctx.policy.dailyPalletCap) {
        throw new BookingError("DAILY_CAP_REACHED", "Daily pallet capacity reached for this site");
      }
    }

    for (const dockId of candidateDocks) {
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          // Savepoint, so a lost race on one dock does not abort the whole transaction.
          const created = await tx.transaction(async (sp) => {
            const [row] = await sp
              .insert(booking)
              .values({
                code: bookingCode(input.date),
                entityId: ctx.entityId,
                siteId: ctx.siteId,
                dockId,
                startAt,
                endAt,
                status: "requested",
                carrierName: input.carrierName,
                contactEmail: input.contactEmail,
                driverName: input.driverName,
                driverPhone: input.driverPhone,
                plate: input.plate,
                vehicleType: input.vehicleType,
                pallets: input.pallets,
                parcels: input.parcels,
                supplier: input.supplier,
                orderNo: input.orderNo,
                ddtNo: input.ddtNo,
                notes: input.notes,
                manageTokenHash: hashToken(manageToken),
                lang: input.lang,
              })
              .returning();
            await sp.insert(bookingEvent).values({
              bookingId: row.id,
              fromStatus: null,
              toStatus: "requested",
              actorType: "carrier",
            });
            return row;
          });
          return {
            id: created.id,
            code: created.code,
            siteId: created.siteId,
            dockId: created.dockId,
            startAt: created.startAt,
            endAt: created.endAt,
            status: created.status,
            manageToken,
          };
        } catch (err) {
          const code = pgErrorCode(err);
          if (code === PG_EXCLUSION_VIOLATION) break; // taken meanwhile: try the next dock
          if (code === PG_UNIQUE_VIOLATION) continue; // booking code collision: retry
          throw err;
        }
      }
    }
    throw new BookingError("SLOT_UNAVAILABLE", "This time is no longer available");
  });
}

/** Allowed status changes. Rescheduling is a cancel + new request (it needs re-confirmation). */
export const TRANSITIONS: Record<BookingStatus, BookingStatus[]> = {
  requested: ["confirmed", "rejected", "cancelled"],
  confirmed: ["arrived", "cancelled", "no_show"],
  arrived: ["unloading"],
  unloading: ["completed"],
  completed: [],
  rejected: [],
  cancelled: [],
  no_show: [],
};

export interface Actor {
  type: ActorType;
  userId?: number;
}

export async function transitionBooking(
  db: Db,
  params: { bookingId: number; to: BookingStatus; actor: Actor; note?: string },
  now: Date = new Date(),
): Promise<{ from: BookingStatus; to: BookingStatus }> {
  return db.transaction(async (tx) => {
    const [row] = await tx
      .select({
        id: booking.id,
        status: booking.status,
        startAt: booking.startAt,
        cancelCutoffHours: bookingPolicy.cancelCutoffHours,
        noShowAfterMin: bookingPolicy.noShowAfterMin,
      })
      .from(booking)
      .leftJoin(bookingPolicy, eq(bookingPolicy.siteId, booking.siteId))
      .where(eq(booking.id, params.bookingId))
      .for("update", { of: booking });
    if (!row) throw new BookingError("BOOKING_NOT_FOUND", `Booking ${params.bookingId} not found`);

    const from = row.status;
    if (!TRANSITIONS[from].includes(params.to)) {
      throw new BookingError("INVALID_TRANSITION", `Cannot go from ${from} to ${params.to}`);
    }
    if (params.actor.type === "carrier") {
      if (params.to !== "cancelled") throw new BookingError("FORBIDDEN", "Carriers can only cancel");
      const cutoff = row.startAt.getTime() - (row.cancelCutoffHours ?? 12) * 3600_000;
      if (now.getTime() > cutoff) {
        throw new BookingError("TOO_LATE", "Too close to the slot to cancel online; please call the warehouse");
      }
    }
    if (params.to === "no_show") {
      const earliest = row.startAt.getTime() + (row.noShowAfterMin ?? 30) * 60_000;
      if (now.getTime() < earliest) throw new BookingError("TOO_EARLY", "The driver is not late enough yet");
    }

    await tx.update(booking).set({ status: params.to, updatedAt: now }).where(eq(booking.id, row.id));
    await tx.insert(bookingEvent).values({
      bookingId: row.id,
      fromStatus: from,
      toStatus: params.to,
      actorType: params.actor.type,
      actorUserId: params.actor.userId ?? null,
      note: params.note ?? null,
      createdAt: now,
    });
    return { from, to: params.to };
  });
}

/** Local date of a booking's start, for display and grouping. */
export function bookingLocalDate(startAt: Date, tz = "Europe/Rome"): string {
  return localDate(startAt, tz);
}
