import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { getBookableDays } from "@/lib/booking/carrier";
import { bookingErrorResponse } from "../errors";

// GET /api/booking-days?entity=0  → every date in the booking window, open or closed
export async function GET(request: NextRequest) {
  const entityCode = Number(request.nextUrl.searchParams.get("entity"));
  if (!Number.isInteger(entityCode) || entityCode < 0) {
    return Response.json({ error: "VALIDATION", message: "entity is required" }, { status: 400 });
  }
  try {
    return Response.json({ days: await getBookableDays(getDb(), entityCode) });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
