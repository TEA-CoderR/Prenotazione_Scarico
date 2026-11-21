import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { putCatalogPart } from "@/lib/scanner";
import { bookingErrorResponse } from "../../errors";
import { requireErp } from "../auth";

// POST /api/erp/catalog {upload, part, parts, meta?, items, by?}
// The purchasing program publishes the scanners' catalog in parts; the last part completes it.
export async function POST(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const body = await request.json().catch(() => null);
  if (!body) return Response.json({ error: "VALIDATION", message: "Invalid JSON" }, { status: 400 });
  const by = typeof body.by === "string" && body.by.trim() ? body.by.trim().slice(0, 80) : "?";
  try {
    return Response.json(await putCatalogPart(getDb(), body, by));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
