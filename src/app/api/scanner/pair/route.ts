import { connection } from "next/server";
import { getDb } from "@/db/client";
import { pair } from "@/lib/scanner";
import { clientIp, rateLimit } from "@/lib/rate-limit";
import { bookingErrorResponse, tooManyRequests } from "../../errors";

// POST /api/scanner/pair {code} → {token, device}   (the code is shown once by the purchasing program)
export async function POST(request: Request) {
  await connection();
  if (!rateLimit(`pair:${clientIp(request.headers)}`, 10, 15 * 60_000)) return tooManyRequests();
  const body = await request.json().catch(() => null);
  try {
    return Response.json(await pair(getDb(), String(body?.code ?? "")));
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
