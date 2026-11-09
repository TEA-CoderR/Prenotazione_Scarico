import { getDb } from "@/db/client";
import { cancelByToken, getBookingByToken } from "@/lib/booking/carrier";
import { getMailer } from "@/lib/mail/mailer";
import { clientIp, rateLimit } from "@/lib/rate-limit";
import { bookingErrorResponse, tooManyRequests } from "../../errors";

type Ctx = { params: Promise<{ token: string }> };

// GET /api/manage/:token  → the carrier's booking, looked up by the secret link
export async function GET(request: Request, { params }: Ctx) {
  if (!rateLimit(`manage:${clientIp(request.headers)}`, 60, 10 * 60_000)) return tooManyRequests();
  const { token } = await params;
  const b = await getBookingByToken(getDb(), token);
  if (!b) return Response.json({ error: "BOOKING_NOT_FOUND" }, { status: 404 });
  return Response.json({ booking: b });
}

// DELETE /api/manage/:token  → carrier cancels (allowed until the cancel cutoff)
export async function DELETE(request: Request, { params }: Ctx) {
  if (!rateLimit(`manage:${clientIp(request.headers)}`, 60, 10 * 60_000)) return tooManyRequests();
  const { token } = await params;
  try {
    return Response.json({ booking: await cancelByToken(getDb(), token, { mailer: getMailer() }) });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
