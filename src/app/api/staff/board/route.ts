import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { getBoard } from "@/lib/booking/staff";
import { localDate } from "@/lib/time";
import { bookingErrorResponse } from "../../errors";
import { requireStaff } from "../auth";

// GET /api/staff/board?site=1&date=2026-11-10 → pending requests + that day's bookings
export async function GET(request: NextRequest) {
  const user = await requireStaff(request);
  if (user instanceof Response) return user;
  const q = request.nextUrl.searchParams;
  const siteId = Number(q.get("site"));
  const date = q.get("date") ?? localDate(new Date(), "Europe/Rome");
  if (!Number.isInteger(siteId) || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    return Response.json({ error: "VALIDATION" }, { status: 400 });
  }
  try {
    return Response.json(await getBoard(getDb(), user, siteId, date));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
