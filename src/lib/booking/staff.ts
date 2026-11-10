// Warehouse-side reads and actions. Every function checks the user's site access.
import { and, asc, eq, gte, inArray, isNull, lt, notInArray, or } from "drizzle-orm";
import type { Db } from "@/db/client";
import { booking, bookingPolicy, closure, entity, openingHours, site, type BookingStatus } from "@/db/schema";
import { assertCanAccessSite, type SessionUser } from "@/lib/auth/site-access";
import type { Mailer } from "@/lib/mail/mailer";
import type { MailKind } from "@/lib/mail/templates";
import { addDays, isoWeekday, localDate, localTime, zonedToUtc } from "@/lib/time";
import { sendBookingMail } from "./carrier";
import { BookingError } from "./errors";
import { transitionBooking } from "./service";

export interface StaffBooking {
  id: number;
  code: string;
  status: BookingStatus;
  date: string;
  time: string;
  endTime: string;
  start: string;
  entityCode: number;
  entityName: string;
  carrierName: string;
  contactEmail: string;
  driverName: string | null;
  driverPhone: string | null;
  plate: string | null;
  vehicleType: string;
  pallets: number;
  parcels: number;
  supplier: string | null;
  orderNo: string | null;
  ddtNo: string | null;
  notes: string | null;
  createdAt: string;
  /** Pending for longer than the site's confirmation target (confirm_sla_hours). */
  overdue: boolean;
  /** Late enough (start + no_show_after_min) that "no-show" is allowed. */
  noShowAllowed: boolean;
}

const columns = {
  id: booking.id,
  code: booking.code,
  status: booking.status,
  startAt: booking.startAt,
  endAt: booking.endAt,
  entityCode: entity.code,
  entityName: entity.name,
  carrierName: booking.carrierName,
  contactEmail: booking.contactEmail,
  driverName: booking.driverName,
  driverPhone: booking.driverPhone,
  plate: booking.plate,
  vehicleType: booking.vehicleType,
  pallets: booking.pallets,
  parcels: booking.parcels,
  supplier: booking.supplier,
  orderNo: booking.orderNo,
  ddtNo: booking.ddtNo,
  notes: booking.notes,
  createdAt: booking.createdAt,
  tz: site.timezone,
  slaHours: bookingPolicy.confirmSlaHours,
  noShowAfterMin: bookingPolicy.noShowAfterMin,
};

function baseQuery(db: Db) {
  return db
    .select(columns)
    .from(booking)
    .innerJoin(entity, eq(entity.id, booking.entityId))
    .innerJoin(site, eq(site.id, booking.siteId))
    .leftJoin(bookingPolicy, eq(bookingPolicy.siteId, booking.siteId));
}

type Row = Awaited<ReturnType<typeof baseQuery>>[number];

function toStaffBooking(r: Row, now: Date): StaffBooking {
  return {
    id: r.id,
    code: r.code,
    status: r.status,
    date: localDate(r.startAt, r.tz),
    time: localTime(r.startAt, r.tz),
    endTime: localTime(r.endAt, r.tz),
    start: r.startAt.toISOString(),
    entityCode: r.entityCode,
    entityName: r.entityName,
    carrierName: r.carrierName,
    contactEmail: r.contactEmail,
    driverName: r.driverName,
    driverPhone: r.driverPhone,
    plate: r.plate,
    vehicleType: r.vehicleType,
    pallets: r.pallets,
    parcels: r.parcels,
    supplier: r.supplier,
    orderNo: r.orderNo,
    ddtNo: r.ddtNo,
    notes: r.notes,
    createdAt: r.createdAt.toISOString(),
    overdue: r.status === "requested" && now.getTime() - r.createdAt.getTime() > (r.slaHours ?? 4) * 3600_000,
    noShowAllowed: now.getTime() >= r.startAt.getTime() + (r.noShowAfterMin ?? 30) * 60_000,
  };
}


export interface DayCount {
  date: string;
  /** Bookings holding a slot that day (not declined or cancelled). */
  bookings: number;
  /** Of which still waiting for confirmation. */
  pending: number;
  pallets: number;
  /** Receiving that day (has opening hours and is not a closure). */
  open: boolean;
}

export interface Board {
  siteId: number;
  date: string;
  /** Requests still waiting for confirmation, any upcoming date, soonest first. */
  pending: StaffBooking[];
  /** Everything on `date`, by time. */
  day: StaffBooking[];
  /** Live bookings from today for the next AGENDA_DAYS days, soonest first. */
  upcoming: StaffBooking[];
  /** Per-day totals for the date strip (STRIP_DAYS days from `stripFrom`). */
  days: DayCount[];
}

export const AGENDA_DAYS = 31;
export const STRIP_DAYS = 14;

export async function getBoard(
  db: Db,
  user: SessionUser,
  siteId: number,
  date: string,
  now = new Date(),
  stripFrom?: string,
): Promise<Board> {
  assertCanAccessSite(user, siteId);
  const [s] = await db.select({ tz: site.timezone }).from(site).where(eq(site.id, siteId));
  if (!s) throw new BookingError("FORBIDDEN", "Unknown site");
  const dayStart = zonedToUtc(date, "00:00", s.tz);
  const dayEnd = zonedToUtc(addDays(date, 1), "00:00", s.tz);
  const today = localDate(now, s.tz);
  const todayStart = zonedToUtc(today, "00:00", s.tz);
  const agendaEnd = zonedToUtc(addDays(today, AGENDA_DAYS), "00:00", s.tz);
  const from = stripFrom ?? today;
  const stripStart = zonedToUtc(from, "00:00", s.tz);
  const stripEnd = zonedToUtc(addDays(from, STRIP_DAYS), "00:00", s.tz);

  const [pending, day, upcoming, stripRows, hours, closed] = await Promise.all([
    baseQuery(db)
      .where(and(eq(booking.siteId, siteId), eq(booking.status, "requested"), gte(booking.endAt, now)))
      .orderBy(asc(booking.startAt)),
    baseQuery(db)
      .where(and(eq(booking.siteId, siteId), gte(booking.startAt, dayStart), lt(booking.startAt, dayEnd)))
      .orderBy(asc(booking.startAt)),
    baseQuery(db)
      .where(
        and(
          eq(booking.siteId, siteId),
          inArray(booking.status, ["requested", "confirmed", "arrived", "unloading"]),
          gte(booking.startAt, todayStart),
          lt(booking.startAt, agendaEnd),
        ),
      )
      .orderBy(asc(booking.startAt))
      .limit(500),
    db
      .select({ startAt: booking.startAt, status: booking.status, pallets: booking.pallets })
      .from(booking)
      .where(
        and(
          eq(booking.siteId, siteId),
          notInArray(booking.status, ["rejected", "cancelled"]),
          gte(booking.startAt, stripStart),
          lt(booking.startAt, stripEnd),
        ),
      ),
    db.select({ weekday: openingHours.weekday }).from(openingHours).where(eq(openingHours.siteId, siteId)),
    db
      .select({ date: closure.date })
      .from(closure)
      .where(
        and(
          gte(closure.date, from),
          lt(closure.date, addDays(from, STRIP_DAYS)),
          or(eq(closure.siteId, siteId), isNull(closure.siteId)),
        ),
      ),
  ]);

  const openDays = new Set(hours.map((h) => h.weekday));
  const closedDates = new Set(closed.map((c) => c.date));
  const days: DayCount[] = Array.from({ length: STRIP_DAYS }, (_, i) => {
    const d = addDays(from, i);
    return { date: d, bookings: 0, pending: 0, pallets: 0, open: openDays.has(isoWeekday(d)) && !closedDates.has(d) };
  });
  for (const row of stripRows) {
    const c = days.find((x) => x.date === localDate(row.startAt, s.tz));
    if (!c) continue;
    c.bookings++;
    c.pallets += row.pallets;
    if (row.status === "requested") c.pending++;
  }

  return {
    siteId,
    date,
    pending: pending.map((r) => toStaffBooking(r, now)),
    day: day.map((r) => toStaffBooking(r, now)),
    upcoming: upcoming.map((r) => toStaffBooking(r, now)),
    days,
  };
}

/** Status changes warehouse staff may make. Cancelling a booking is for managers and admins. */
const STAFF_TARGETS: BookingStatus[] = ["confirmed", "rejected", "arrived", "unloading", "completed", "no_show", "cancelled"];
const MANAGER_ONLY: BookingStatus[] = ["cancelled"];
const MAIL_ON: Partial<Record<BookingStatus, MailKind>> = { confirmed: "confirmed", rejected: "rejected", cancelled: "cancelled" };

export async function staffSetStatus(
  db: Db,
  user: SessionUser,
  params: { bookingId: number; to: BookingStatus; note?: string | null },
  opts: { now?: Date; mailer?: Mailer } = {},
) {
  const now = opts.now ?? new Date();
  const [b] = await db
    .select({ siteId: booking.siteId })
    .from(booking)
    .where(and(eq(booking.id, params.bookingId), inArray(booking.status, ["requested", "confirmed", "arrived", "unloading"])));
  if (!b) {
    // Unknown, or already in a final state: report the same way.
    throw new BookingError("INVALID_TRANSITION", "This booking can no longer be changed");
  }
  assertCanAccessSite(user, b.siteId);
  if (!STAFF_TARGETS.includes(params.to)) throw new BookingError("FORBIDDEN", "Not allowed");
  if (MANAGER_ONLY.includes(params.to) && user.role === "warehouse_clerk") {
    throw new BookingError("FORBIDDEN", "Only a manager can cancel a booking");
  }
  const note = params.note?.trim().slice(0, 500) || null;
  if (params.to === "rejected" && !note) throw new BookingError("VALIDATION", "Give the carrier a reason");

  const result = await transitionBooking(
    db,
    { bookingId: params.bookingId, to: params.to, actor: { type: "user", userId: user.id }, note: note ?? undefined },
    now,
  );
  const kind = MAIL_ON[params.to];
  if (kind && opts.mailer) await sendBookingMail(db, opts.mailer, { bookingId: params.bookingId, kind, note });
  return result;
}
