import type { NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { renewCode, setActive } from "@/lib/scanner";
import { bookingErrorResponse } from "../../../errors";
import { requireErp } from "../../auth";

const body = z.object({ action: z.enum(["renew", "deactivate", "activate"]) });

// POST /api/erp/scanners/:id {action: "renew" | "deactivate" | "activate"}
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const id = Number((await params).id);
  const parsed = body.safeParse(await request.json().catch(() => null));
  if (!Number.isInteger(id) || !parsed.success) return Response.json({ error: "VALIDATION" }, { status: 400 });
  const db = getDb();
  try {
    if (parsed.data.action === "renew") return Response.json(await renewCode(db, id));
    return Response.json({ device: await setActive(db, id, parsed.data.action === "activate") });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
