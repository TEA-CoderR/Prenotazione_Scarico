import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { erpSetStatus } from "@/lib/booking/erp";
import { getMailer } from "@/lib/mail/mailer";
import { bookingErrorResponse } from "../../../../errors";
import { requireErp } from "../../../auth";

// POST /api/erp/bookings/:id/status  {to: "completed" | "refused" | "arrived" | "cancelled" | …, by, note?}
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const id = Number((await params).id);
  const body = await request.json().catch(() => null);
  if (!Number.isInteger(id) || !body) return Response.json({ error: "VALIDATION" }, { status: 400 });
  try {
    return Response.json(await erpSetStatus(getDb(), id, body, { mailer: getMailer() }));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
