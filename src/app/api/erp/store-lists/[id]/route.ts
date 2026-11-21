import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { markReceived, setFeedback } from "@/lib/store-lists";
import { bookingErrorResponse } from "../../../errors";
import { requireErp } from "../../auth";

// POST /api/erp/store-lists/:id {action: "received"} | {action: "feedback", feedback: {lines, note?, deliveries?}}
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const id = Number((await params).id);
  if (!Number.isInteger(id) || id <= 0) return Response.json({ error: "VALIDATION", message: "id" }, { status: 400 });
  const body = await request.json().catch(() => null);
  try {
    if (body?.action === "received") return Response.json(await markReceived(getDb(), id));
    if (body?.action === "feedback") return Response.json(await setFeedback(getDb(), id, body.feedback ?? {}));
    return Response.json({ error: "VALIDATION", message: "action" }, { status: 400 });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
