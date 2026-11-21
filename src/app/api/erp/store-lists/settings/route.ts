import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { getRule, setRule } from "@/lib/store-lists";
import { bookingErrorResponse } from "../../../errors";
import { requireErp } from "../../auth";

// GET /api/erp/store-lists/settings → {day, time}   (weekly deadline of the store lists: ISO weekday 1–7, HH:MM Rome time)
export async function GET(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  return Response.json(await getRule(getDb()));
}

// PUT /api/erp/store-lists/settings {day, time}
export async function PUT(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await setRule(getDb(), body ?? {}));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
