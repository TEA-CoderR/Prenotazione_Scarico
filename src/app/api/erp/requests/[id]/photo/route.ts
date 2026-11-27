import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { requestPhoto } from "@/lib/product-requests";
import { requireErp } from "../../../auth";

// GET /api/erp/requests/:id/photo → the store's photo (image/jpeg) or 404
export async function GET(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const denied = await requireErp(request);
  if (denied) return denied;
  const id = Number((await params).id);
  const photo = Number.isInteger(id) && id > 0 ? await requestPhoto(getDb(), id) : null;
  if (!photo) return Response.json({ error: "NOT_FOUND" }, { status: 404 });
  return new Response(new Uint8Array(photo), { headers: { "content-type": "image/jpeg", "cache-control": "private, max-age=86400" } });
}
