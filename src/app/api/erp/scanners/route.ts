import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { createDevice, latestCatalogInfo, listDevices } from "@/lib/scanner";
import { bookingErrorResponse } from "../../errors";
import { requireErp } from "../auth";

// GET /api/erp/scanners → {devices, catalog}   (catalog = latest published snapshot info)
export async function GET(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const db = getDb();
  const [devices, catalog] = await Promise.all([listDevices(db), latestCatalogInfo(db)]);
  return Response.json({ devices, catalog });
}

// POST /api/erp/scanners {name, entityCode?} → {device, pairCode}   (pairing code shown once)
export async function POST(request: NextRequest) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await createDevice(getDb(), body ?? {}), { status: 201 });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
