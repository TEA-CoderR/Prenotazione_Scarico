import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { erpCreateBooking, listErpBookings } from "@/lib/booking/erp";
import { getMailer } from "@/lib/mail/mailer";
import { localTime } from "@/lib/time";
import { bookingErrorResponse } from "../../errors";
import { requireErp } from "../auth";

// GET /api/erp/bookings?order=FO-2111-3F9A&order=…   and/or   ?from=2026-11-01&to=2026-11-30
export async function GET(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const sp = request.nextUrl.searchParams;
  try {
    const bookings = await listErpBookings(getDb(), {
      orderNos: sp.getAll("order").flatMap((v) => v.split(",")).map((v) => v.trim()).filter(Boolean),
      from: sp.get("from") ?? undefined,
      to: sp.get("to") ?? undefined,
    });
    return Response.json({ bookings });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}

// POST /api/erp/bookings  {entityCode, date, time, vehicleType, pallets, parcels, carrierName, by, orderNo?, supplier?, …}
// A time booked by the purchasing office (phone call, or chosen when registering the shipment): confirmed at once.
export async function POST(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const body = await request.json().catch(() => null);
  if (!body) return Response.json({ error: "VALIDATION", message: "Invalid JSON" }, { status: 400 });
  try {
    const b = await erpCreateBooking(getDb(), body, { mailer: getMailer() });
    return Response.json(
      { id: b.id, code: b.code, status: b.status, time: localTime(b.startAt, "Europe/Rome"), start: b.startAt.toISOString(), end: b.endAt.toISOString() },
      { status: 201 },
    );
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
