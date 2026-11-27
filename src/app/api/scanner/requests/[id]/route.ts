import { getDb } from "@/db/client";
import { storeAction } from "@/lib/product-requests";
import { bookingErrorResponse } from "../../../errors";
import { requireDevice } from "../../auth";

// POST /api/scanner/requests/:id {action: "reply" (note?, photo?) | "seen" | "cancel"}
export async function POST(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  const id = Number((await params).id);
  if (!Number.isInteger(id) || id <= 0) return Response.json({ error: "VALIDATION", message: "id" }, { status: 400 });
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await storeAction(getDb(), device, id, body ?? {}));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
