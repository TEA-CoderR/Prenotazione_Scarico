import { getDb } from "@/db/client";
import { latestCatalog } from "@/lib/scanner";
import { requireDevice } from "../auth";

// GET /api/scanner/catalog → the latest catalog (ETag = its id; If-None-Match → 304)
export async function GET(request: Request) {
  const device = await requireDevice(request);
  if (device instanceof Response) return device;
  const c = await latestCatalog(getDb());
  if (!c) return Response.json({ error: "NO_CATALOG" }, { status: 404 });
  const etag = `"c${c.id}"`;
  if (request.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers: { ETag: etag } });
  return Response.json({ id: c.id, ...(c.payload as object) }, { headers: { ETag: etag, "Cache-Control": "no-cache" } });
}
