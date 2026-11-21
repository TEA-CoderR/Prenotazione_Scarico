import { getDb } from "@/db/client";
import { currentList, putLines } from "@/lib/store-lists";
import { bookingErrorResponse } from "../../errors";
import { requireDevice } from "../auth";

// GET /api/scanner/list → this week's DETERSIVO list of the scanner's store (shared by its scanners),
// the deadline, last week's list with what was ordered, and "remind me" items.
export async function GET(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  try {
    return Response.json(await currentList(getDb(), device.entityCode));
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
    return Response.json(await putLines(getDb(), device, body ?? {}));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
