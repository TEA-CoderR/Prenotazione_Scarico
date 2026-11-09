import { getDb } from "@/db/client";
import { env } from "@/env";
import { sendBookingMail } from "@/lib/booking/carrier";
import { createBooking } from "@/lib/booking/service";
import { verifyCaptcha } from "@/lib/captcha";
import { getMailer } from "@/lib/mail/mailer";
import { clientIp, rateLimit } from "@/lib/rate-limit";
import { localTime } from "@/lib/time";
import { bookingErrorResponse, tooManyRequests } from "../errors";

// POST /api/bookings  → creates a booking request (status "requested")
export async function POST(request: Request) {
  const ip = clientIp(request.headers);
  if (!rateLimit(`book:${ip}`, 10, 10 * 60_000)) return tooManyRequests();

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return Response.json({ error: "VALIDATION", message: "Invalid JSON" }, { status: 400 });
  }

  const captcha = await verifyCaptcha((body as { captchaToken?: unknown } | null)?.captchaToken, {
    secret: env().TURNSTILE_SECRET_KEY,
    remoteIp: ip,
  });
  if (!captcha.ok) {
    return Response.json({ error: "CAPTCHA_FAILED", message: captcha.codes.join(",") }, { status: 400 });
  }

  const db = getDb();
  try {
    const created = await createBooking(db, body as Parameters<typeof createBooking>[1]);
    const manageUrl = `${env().APP_URL}/b/${created.manageToken}`;
    await sendBookingMail(db, getMailer(), { bookingId: created.id, kind: "requested", manageUrl });
    return Response.json(
      {
        code: created.code,
        status: created.status,
        time: localTime(created.startAt, "Europe/Rome"),
        start: created.startAt.toISOString(),
        end: created.endAt.toISOString(),
        manageUrl,
      },
      { status: 201 },
    );
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
