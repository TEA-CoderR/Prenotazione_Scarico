import type { NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { vehicleType } from "@/db/schema";
import { getAvailability } from "@/lib/booking/service";
import { localTime } from "@/lib/time";
import { bookingErrorResponse } from "../../errors";
import { requireErp } from "../auth";

const query = z.object({
  entity: z.coerce.number().int().min(0),
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  vehicle: z.enum(vehicleType.enumValues),
  pallets: z.coerce.number().int().min(0).default(0),
  parcels: z.coerce.number().int().min(0).default(0),
});

// GET /api/erp/availability?entity=1&date=2026-11-23&vehicle=van&pallets=2
// Free times for the purchasing office: today is allowed too (only times still ahead).
export async function GET(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const parsed = query.safeParse(Object.fromEntries(request.nextUrl.searchParams));
  if (!parsed.success) return Response.json({ error: "VALIDATION", message: z.prettifyError(parsed.error) }, { status: 400 });
  const q = parsed.data;
  try {
    const r = await getAvailability(
      getDb(),
      { entityCode: q.entity, date: q.date, vehicleType: q.vehicle, pallets: q.pallets, parcels: q.parcels },
      new Date(),
      { staff: true },
    );
    return Response.json({
      date: r.date,
      durationMin: r.durationMin,
      slots: r.slots.map((s) => ({ time: localTime(s.start, "Europe/Rome"), start: s.start.toISOString(), end: s.end.toISOString() })),
    });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
