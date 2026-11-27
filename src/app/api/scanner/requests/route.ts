import { getDb } from "@/db/client";
import { askRequest, storeRequests } from "@/lib/product-requests";
import { bookingErrorResponse } from "../../errors";
import { requireDevice } from "../auth";

// GET /api/scanner/requests → {requests} (this store, last 90 days, no photos)
export async function GET(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  return Response.json({ requests: await storeRequests(getDb(), device.entityCode) });
}

// POST /api/scanner/requests {ean, name, kind, reason, boxes?, note?, photo? (base64 JPEG)}
// The store asks the purchasing office about an item it cannot order; replaces its open request of the same item.
export async function POST(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await askRequest(getDb(), device, body ?? {}), { status: 201 });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
