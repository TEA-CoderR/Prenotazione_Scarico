import { getDb } from "@/db/client";
import { latestCatalogInfo } from "@/lib/scanner";
import { requireDevice } from "../auth";

// GET /api/scanner/me → {device, catalog: {id, createdAt, itemCount} | null}   (cheap "is there a new catalog?")
export async function GET(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  const c = await latestCatalogInfo(getDb());
  return Response.json({ device, catalog: c && { id: c.id, createdAt: c.createdAt.toISOString(), itemCount: c.itemCount } });
}
