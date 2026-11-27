import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { erpRequests } from "@/lib/product-requests";
import { requireErp } from "../auth";

// GET /api/erp/requests[?status=open,info,answered|all][&days=120] → {requests}
export async function GET(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const q = request.nextUrl.searchParams;
  const days = Math.min(3650, Math.max(1, Number(q.get("days")) || 120));
  return Response.json({ requests: await erpRequests(getDb(), q.get("status"), new Date(), days) });
}
