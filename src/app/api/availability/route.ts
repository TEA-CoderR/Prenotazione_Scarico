import type { NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { vehicleType } from "@/db/schema";
import { BookingError } from "@/lib/booking/errors";
import { getAvailability } from "@/lib/booking/service";
import { localTime } from "@/lib/time";

const query = z.object({
  entity: z.coerce.number().int().min(0),
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  vehicle: z.enum(vehicleType.enumValues),
  pallets: z.coerce.number().int().min(0).default(0),
  parcels: z.coerce.number().int().min(0).default(0),
});

// GET /api/availability?entity=0&date=2026-11-12&vehicle=van&pallets=4
export async function GET(request: NextRequest) {
  const parsed = query.safeParse(Object.fromEntries(request.nextUrl.searchParams));
  if (!parsed.success) return Response.json({ error: z.prettifyError(parsed.error) }, { status: 400 });
  const q = parsed.data;
  try {
    const result = await getAvailability(getDb(), {
      entityCode: q.entity,
      date: q.date,
      vehicleType: q.vehicle,
      pallets: q.pallets,
      parcels: q.parcels,
    });
    return Response.json({
      date: result.date,
      durationMin: result.durationMin,
      slots: result.slots.map((s) => ({
        time: localTime(s.start, "Europe/Rome"),
        start: s.start.toISOString(),
        end: s.end.toISOString(),
      })),
    });
  } catch (err) {
    if (err instanceof BookingError) return Response.json({ error: err.code, message: err.message }, { status: 422 });
    throw err;
  }
}
