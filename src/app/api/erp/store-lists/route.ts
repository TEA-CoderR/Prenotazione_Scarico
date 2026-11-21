import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { erpLists } from "@/lib/store-lists";
import { requireErp } from "../auth";

// GET /api/erp/store-lists[?week=YYYY-MM-DD] → {rule, current, deadline, lists: [... with lines]}
// (no week: the current week and the one before)
export async function GET(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const week = request.nextUrl.searchParams.get("week");
  if (week && !/^\d{4}-\d{2}-\d{2}$/.test(week)) return Response.json({ error: "VALIDATION", message: "week" }, { status: 400 });
  return Response.json(await erpLists(getDb(), week));
}
