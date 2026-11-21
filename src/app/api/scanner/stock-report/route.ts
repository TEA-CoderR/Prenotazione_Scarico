import { getDb } from "@/db/client";
import { reportStock } from "@/lib/stock-reports";
import { bookingErrorResponse } from "../../errors";
import { requireDevice } from "../auth";

// POST /api/scanner/stock-report {ean, name, systemQty, counted, countedBy, shelf: true, backroom: true}
// The store counted the item and the stock in the system is wrong; replaces its open report of the same item.
export async function POST(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await reportStock(getDb(), device, body ?? {}), { status: 201 });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
