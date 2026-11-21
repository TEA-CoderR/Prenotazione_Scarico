import { getDb } from "@/db/client";
import { submitList } from "@/lib/store-lists";
import { bookingErrorResponse } from "../../../errors";
import { requireDevice } from "../../auth";

// POST /api/scanner/list/submit → sends this week's list to the purchasing office (still editable until the deadline)
export async function POST(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  try {
    return Response.json(await submitList(getDb(), device));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
