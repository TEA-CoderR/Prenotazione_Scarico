import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { erpReports } from "@/lib/stock-reports";
import { requireErp } from "../auth";

// GET /api/erp/stock-reports[?status=open|fixed|rejected|all] → {reports}   (last 90 days)
export async function GET(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  return Response.json({ reports: await erpReports(getDb(), request.nextUrl.searchParams.get("status")) });
}
