// Link with the purchasing program (CASA&TE 采购系统): it reads the bookings of its orders,
// books a time for carriers who call, and records arrivals / refusals at the dock.
// The order number (e.g. "FO-2111-3F9A") is the key both systems share.
import { and, asc, eq, gte, lt } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import { booking, bookingStatus, entity, incident, site, type BookingStatus } from "@/db/schema";
import type { Mailer } from "@/lib/mail/mailer";
import type { MailKind } from "@/lib/mail/templates";
import { addDays, localDate, localTime, zonedToUtc } from "@/lib/time";
import { sendBookingMail } from "./carrier";
import { BookingError } from "./errors";
import { createBooking, staffBookingSchema, transitionBooking, TRANSITIONS, type CreateBookingInput } from "./service";

export interface ErpBooking {
  id: number;
  code: string;
  status: BookingStatus;
  entityCode: number;
  date: string;
  time: string;
  endTime: string;
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
}

const listQuery = z.object({
  orderNos: z.array(z.string().trim().min(1).max(60)).max(500).default([]),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
});

/**
 * Bookings for these order numbers (an order number may appear inside a longer text the
 * carrier typed, e.g. "FO-2111-3F9A, FO-2111-77AB"), and/or every booking between from and to.
 */
export async function listErpBookings(db: Db, raw: z.input<typeof listQuery>): Promise<ErpBooking[]> {
  const q = listQuery.parse(raw);
  const tz = "Europe/Rome";
  const conds = [];
  if (q.from) conds.push(gte(booking.startAt, zonedToUtc(q.from, "00:00", tz)));
  if (q.to) conds.push(lt(booking.startAt, zonedToUtc(addDays(q.to, 1), "00:00", tz)));
  if (!q.orderNos.length && !conds.length) throw new BookingError("VALIDATION", "Give order numbers or a date range");
  const rows = await db
    .select({
      id: booking.id,
      code: booking.code,
      status: booking.status,
      startAt: booking.startAt,
      endAt: booking.endAt,
      tz: site.timezone,
      entityCode: entity.code,
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
    })
    .from(booking)
    .innerJoin(entity, eq(entity.id, booking.entityId))
    .innerJoin(site, eq(site.id, booking.siteId))
    .where(conds.length ? and(...conds) : undefined)
    .orderBy(asc(booking.startAt))
    .limit(q.orderNos.length ? 5000 : 1000);
  const wanted = q.orderNos.map((n) => n.toUpperCase());
  const hit = (text: string | null) => {
    const t = (text ?? "").toUpperCase();
    return wanted.some((n) => t.includes(n));
  };
  return rows
    .filter((r) => !wanted.length || hit(r.orderNo) || hit(r.notes) || hit(r.ddtNo))
    .map(({ startAt, endAt, tz: z_, ...r }) => ({
      ...r,
      date: localDate(startAt, z_),
      time: localTime(startAt, z_),
      endTime: localTime(endAt, z_),
    }));
}

export const erpCreateSchema = staffBookingSchema.extend({
  by: z.string().trim().min(1).max(80),
});

/** Booking entered by the purchasing office: confirmed at once; the carrier gets the confirmation if an e-mail is given. */
export async function erpCreateBooking(
  db: Db,
  raw: z.input<typeof erpCreateSchema>,
  opts: { now?: Date; mailer?: Mailer } = {},
) {
  const parsed = erpCreateSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const { by: _by, ...rest } = raw as z.input<typeof erpCreateSchema>;
  void _by;
  const created = await createBooking(db, rest as CreateBookingInput, { now: opts.now, staff: { by: parsed.data.by } });
  if (parsed.data.contactEmail && opts.mailer) {
    await sendBookingMail(db, opts.mailer, { bookingId: created.id, kind: "confirmed" });
  }
  return created;
}

/** Status changes the purchasing program may make. */
export const ERP_TARGETS: BookingStatus[] = ["arrived", "unloading", "completed", "refused", "cancelled", "no_show"];
const MAIL_ON: Partial<Record<BookingStatus, MailKind>> = { cancelled: "cancelled" };

export const erpStatusSchema = z.object({
  to: z.enum(bookingStatus.enumValues),
  by: z.string().trim().min(1).max(80),
  note: z.string().trim().max(500).optional(),
});

/**
 * Moves a booking to `to`, stepping through arrived → unloading when needed (the program records
 * a receipt in one go). "refused" needs a reason, stored as an incident too.
 */
export async function erpSetStatus(
  db: Db,
  bookingId: number,
  raw: z.input<typeof erpStatusSchema>,
  opts: { now?: Date; mailer?: Mailer } = {},
): Promise<{ from: BookingStatus; to: BookingStatus }> {
  const parsed = erpStatusSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const { to, by, note } = parsed.data;
  if (!ERP_TARGETS.includes(to)) throw new BookingError("FORBIDDEN", "Not allowed");
  if (to === "refused" && !note) throw new BookingError("VALIDATION", "Give the reason for the refusal");
  const now = opts.now ?? new Date();

  const [row] = await db.select({ status: booking.status }).from(booking).where(eq(booking.id, bookingId));
  if (!row) throw new BookingError("BOOKING_NOT_FOUND", `Booking ${bookingId} not found`);
  const from = row.status;
  if (from === to) return { from, to };

  const path = pathTo(from, to);
  if (!path) throw new BookingError("INVALID_TRANSITION", `Cannot go from ${from} to ${to}`);
  const text = `Ufficio acquisti · ${by}${note ? ` · ${note}` : ""}`.slice(0, 500);
  for (const step of path) {
    await transitionBooking(db, { bookingId, to: step, actor: { type: "system" }, note: text }, now);
  }
  if (to === "refused") {
    await db.insert(incident).values({ bookingId, type: "refused", note: `${note} (${by})`.slice(0, 500), createdAt: now });
  }
  const kind = MAIL_ON[to];
  if (kind && opts.mailer) await sendBookingMail(db, opts.mailer, { bookingId, kind, note: note ?? null });
  return { from, to };
}

/** Shortest chain of allowed transitions from → to (e.g. confirmed → arrived → unloading → completed). */
export function pathTo(from: BookingStatus, to: BookingStatus): BookingStatus[] | null {
  const prev = new Map<BookingStatus, BookingStatus>();
  const queue: BookingStatus[] = [from];
  const seen = new Set<BookingStatus>([from]);
  while (queue.length) {
    const cur = queue.shift()!;
    if (cur === to) break;
    for (const next of TRANSITIONS[cur]) {
      if (!seen.has(next)) {
        seen.add(next);
        prev.set(next, cur);
        queue.push(next);
      }
    }
  }
  if (!seen.has(to) || from === to) return null;
  const path: BookingStatus[] = [];
  for (let s: BookingStatus | undefined = to; s && s !== from; s = prev.get(s)) path.unshift(s);
  return path;
}
