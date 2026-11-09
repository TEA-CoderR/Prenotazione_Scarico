// Carrier-facing reads and actions: entity list, bookable days, manage-by-link, mails.
import { and, asc, eq, gte, isNull, lte, or } from "drizzle-orm";
import type { Db } from "@/db/client";
import { booking, bookingPolicy, closure, entity, notificationLog, openingHours, site, type BookingStatus } from "@/db/schema";
import { renderBookingMail, type Lang, type MailKind } from "@/lib/mail/templates";
import type { Mailer } from "@/lib/mail/mailer";
import { bookableDateRange } from "@/lib/scheduling/availability";
import { addDays, isoWeekday, localDate, localTime } from "@/lib/time";
import { BookingError } from "./errors";
import { hashToken, loadSiteContext, transitionBooking } from "./service";

export interface EntityOption {
  code: number;
  name: string;
  siteName: string;
  siteAddress: string | null;
  /** Other entities unloading at the same site (0 ↔ 3). */
  sharedWith: string[];
}

export async function listEntities(db: Db): Promise<EntityOption[]> {
  const rows = await db
    .select({ code: entity.code, name: entity.name, siteId: site.id, siteName: site.name, siteAddress: site.address })
    .from(entity)
    .innerJoin(site, eq(site.id, entity.siteId))
    .where(and(eq(entity.isActive, true), eq(site.isActive, true)))
    .orderBy(asc(entity.code));
  return rows.map((r) => ({
    code: r.code,
    name: r.name,
    siteName: r.siteName,
    siteAddress: r.siteAddress,
    sharedWith: rows.filter((o) => o.siteId === r.siteId && o.code !== r.code).map((o) => o.name),
  }));
}

export interface BookableDay {
  date: string;
  open: boolean;
}

/** Every date in the booking window, marked open/closed (weekend, holiday, no hours). */
export async function getBookableDays(db: Db, entityCode: number, now: Date = new Date()): Promise<BookableDay[]> {
  const ctx = await loadSiteContext(db, entityCode);
  const { first, last } = bookableDateRange(now, ctx.tz, ctx.policy);
  const [hours, closed] = await Promise.all([
    db.select({ weekday: openingHours.weekday }).from(openingHours).where(eq(openingHours.siteId, ctx.siteId)),
    db
      .select({ date: closure.date })
      .from(closure)
      .where(
        and(
          gte(closure.date, first),
          lte(closure.date, last),
          or(eq(closure.siteId, ctx.siteId), isNull(closure.siteId)),
        ),
      ),
  ]);
  const openWeekdays = new Set(hours.map((h) => h.weekday));
  const closedDates = new Set(closed.map((c) => c.date));
  const days: BookableDay[] = [];
  for (let d = first; d <= last; d = addDays(d, 1)) {
    days.push({ date: d, open: openWeekdays.has(isoWeekday(d)) && !closedDates.has(d) });
  }
  return days;
}

export interface ManagedBooking {
  code: string;
  status: BookingStatus;
  lang: Lang;
  entityName: string;
  siteName: string;
  siteAddress: string | null;
  date: string;
  time: string;
  endTime: string;
  vehicleType: string;
  pallets: number;
  parcels: number;
  canCancel: boolean;
  /** ISO instant after which online cancellation is no longer possible. */
  cancelDeadline: string;
}

async function findByToken(db: Db, token: string) {
  if (!token || token.length > 100) return undefined;
  const [row] = await db
    .select({
      id: booking.id,
      code: booking.code,
      status: booking.status,
      lang: booking.lang,
      startAt: booking.startAt,
      endAt: booking.endAt,
      vehicleType: booking.vehicleType,
      pallets: booking.pallets,
      parcels: booking.parcels,
      entityName: entity.name,
      siteName: site.name,
      siteAddress: site.address,
      tz: site.timezone,
      cancelCutoffHours: bookingPolicy.cancelCutoffHours,
    })
    .from(booking)
    .innerJoin(entity, eq(entity.id, booking.entityId))
    .innerJoin(site, eq(site.id, booking.siteId))
    .leftJoin(bookingPolicy, eq(bookingPolicy.siteId, booking.siteId))
    .where(eq(booking.manageTokenHash, hashToken(token)));
  return row;
}

export async function getBookingByToken(db: Db, token: string, now: Date = new Date()): Promise<ManagedBooking | null> {
  const r = await findByToken(db, token);
  if (!r) return null;
  const deadline = new Date(r.startAt.getTime() - (r.cancelCutoffHours ?? 12) * 3600_000);
  return {
    code: r.code,
    status: r.status,
    lang: r.lang,
    entityName: r.entityName,
    siteName: r.siteName,
    siteAddress: r.siteAddress,
    date: localDate(r.startAt, r.tz),
    time: localTime(r.startAt, r.tz),
    endTime: localTime(r.endAt, r.tz),
    vehicleType: r.vehicleType,
    pallets: r.pallets,
    parcels: r.parcels,
    canCancel: (r.status === "requested" || r.status === "confirmed") && now <= deadline,
    cancelDeadline: deadline.toISOString(),
  };
}

export async function cancelByToken(
  db: Db,
  token: string,
  opts: { now?: Date; mailer?: Mailer } = {},
): Promise<ManagedBooking> {
  const now = opts.now ?? new Date();
  const r = await findByToken(db, token);
  if (!r) throw new BookingError("BOOKING_NOT_FOUND", "Booking not found");
  await transitionBooking(db, { bookingId: r.id, to: "cancelled", actor: { type: "carrier" } }, now);
  if (opts.mailer) await sendBookingMail(db, opts.mailer, { bookingId: r.id, kind: "cancelled" });
  return (await getBookingByToken(db, token, now))!;
}

/**
 * Sends a booking mail in the carrier's language and records it in notification_log.
 * Never throws: a mail problem must not undo a booking.
 */
export async function sendBookingMail(
  db: Db,
  mailer: Mailer,
  params: { bookingId: number; kind: MailKind; manageUrl?: string },
): Promise<boolean> {
  const [b] = await db
    .select({
      code: booking.code,
      email: booking.contactEmail,
      lang: booking.lang,
      startAt: booking.startAt,
      entityName: entity.name,
      siteName: site.name,
      siteAddress: site.address,
      tz: site.timezone,
    })
    .from(booking)
    .innerJoin(entity, eq(entity.id, booking.entityId))
    .innerJoin(site, eq(site.id, booking.siteId))
    .where(eq(booking.id, params.bookingId));
  if (!b) return false;

  const mail = renderBookingMail(params.kind, b.lang, {
    code: b.code,
    entityName: b.entityName,
    siteName: b.siteName,
    siteAddress: b.siteAddress,
    date: localDate(b.startAt, b.tz),
    time: localTime(b.startAt, b.tz),
    manageUrl: params.manageUrl,
  });
  let ok = true;
  let error: string | null = null;
  try {
    await mailer.send({ to: b.email, ...mail });
  } catch (e) {
    ok = false;
    error = e instanceof Error ? e.message : String(e);
    console.error(`[mail] ${params.kind} for ${b.code} failed: ${error}`);
  }
  await db.insert(notificationLog).values({
    bookingId: params.bookingId,
    channel: "email",
    template: params.kind,
    recipient: b.email,
    ok,
    error,
  });
  return ok;
}
