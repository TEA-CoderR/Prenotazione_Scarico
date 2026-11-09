import { BookingError, type BookingErrorCode } from "@/lib/booking/errors";

const STATUS: Partial<Record<BookingErrorCode, number>> = {
  VALIDATION: 400,
  ENTITY_NOT_FOUND: 404,
  BOOKING_NOT_FOUND: 404,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  SLOT_UNAVAILABLE: 409,
  DAILY_CAP_REACHED: 409,
};

/** JSON error for a BookingError; rethrows anything else (logged as a 500 by Next.js). */
export function bookingErrorResponse(err: unknown): Response {
  if (err instanceof BookingError) {
    return Response.json({ error: err.code, message: err.message }, { status: STATUS[err.code] ?? 422 });
  }
  throw err;
}

export function tooManyRequests(): Response {
  return Response.json({ error: "RATE_LIMITED", message: "Too many requests, try again later" }, { status: 429 });
}
