import type { NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { deleteDevice, renewCode, setActive, updateDevice } from "@/lib/scanner";
import { bookingErrorResponse } from "../../../errors";
import { requireErp } from "../../auth";

const body = z.discriminatedUnion("action", [
  z.object({ action: z.enum(["renew", "deactivate", "activate", "delete"]) }),
  z.object({
    action: z.literal("update"),
    name: z.string().optional(),
    entityCode: z.number().int().nullable().optional(),
  }),
]);

// POST /api/erp/scanners/:id {action: "renew" | "deactivate" | "activate" | "delete" | "update", name?, entityCode?}
// "delete" only works on deactivated scanners.
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const id = Number((await params).id);
  const parsed = body.safeParse(await request.json().catch(() => null));
  if (!Number.isInteger(id) || !parsed.success) return Response.json({ error: "VALIDATION" }, { status: 400 });
  const db = getDb();
  try {
    const a = parsed.data;
    if (a.action === "renew") return Response.json(await renewCode(db, id));
    if (a.action === "delete") return Response.json(await deleteDevice(db, id));
    if (a.action === "update") {
      return Response.json({ device: await updateDevice(db, id, { name: a.name, entityCode: a.entityCode }) });
    }
    return Response.json({ device: await setActive(db, id, a.action === "activate") });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
