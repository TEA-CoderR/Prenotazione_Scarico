import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { handleReport } from "@/lib/stock-reports";
import { bookingErrorResponse } from "../../../errors";
import { requireErp } from "../../auth";

// POST /api/erp/stock-reports/:id {action: "fixed" | "rejected" | "reopen", answer?, by?}
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const id = Number((await params).id);
  if (!Number.isInteger(id) || id <= 0) return Response.json({ error: "VALIDATION", message: "id" }, { status: 400 });
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await handleReport(getDb(), id, body ?? {}));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
