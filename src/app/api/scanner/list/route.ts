import { getDb } from "@/db/client";
import { storeRequests } from "@/lib/product-requests";
import { currentList, putLines } from "@/lib/store-lists";
import { bookingErrorResponse } from "../../errors";
import { requireDevice } from "../auth";

// GET /api/scanner/list → this week's DETERSIVO list of the scanner's store (shared by its scanners),
// the deadline, last week's list with what was ordered, "remind me" items, stock reports and product
// requests to the purchasing office.
export async function GET(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  try {
    const db = getDb();
    return Response.json({ ...(await currentList(db, device.entityCode)), requests: await storeRequests(db, device.entityCode) });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}

// PUT /api/scanner/list {lines: [{ean, name, boxes, kind, reason?, origEan?, origName?}]} → same as GET
export async function PUT(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  const body = await request.json().catch(() => null);
  try {
    const db = getDb();
    return Response.json({ ...(await putLines(db, device, body ?? {})), requests: await storeRequests(db, device.entityCode) });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
