import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { answerRequest } from "@/lib/product-requests";
import { bookingErrorResponse } from "../../../errors";
import { requireErp } from "../../auth";

// POST /api/erp/requests/:id {action: "answer", kind, answer, ean?, name?, boxes?, by?} | {action: "reopen"}
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const id = Number((await params).id);
  if (!Number.isInteger(id) || id <= 0) return Response.json({ error: "VALIDATION", message: "id" }, { status: 400 });
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await answerRequest(getDb(), id, body ?? {}));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
