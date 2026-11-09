import type { NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { bookingStatus } from "@/db/schema";
import { staffSetStatus } from "@/lib/booking/staff";
import { getMailer } from "@/lib/mail/mailer";
import { bookingErrorResponse } from "../../../../errors";
import { rejectCrossSite, requireStaff } from "../../../auth";

const body = z.object({ to: z.enum(bookingStatus.enumValues), note: z.string().max(500).optional() });

// POST /api/staff/bookings/:id/status  {to: "confirmed" | "rejected" | ..., note?}
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const blocked = rejectCrossSite(request);
  if (blocked) return blocked;
  const user = await requireStaff(request);
  if (user instanceof Response) return user;

  const id = Number((await params).id);
  const parsed = body.safeParse(await request.json().catch(() => null));
  if (!Number.isInteger(id) || !parsed.success) return Response.json({ error: "VALIDATION" }, { status: 400 });
  try {
    const result = await staffSetStatus(getDb(), user, { bookingId: id, ...parsed.data }, { mailer: getMailer() });
    return Response.json(result);
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
