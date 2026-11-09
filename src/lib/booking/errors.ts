export type BookingErrorCode =
  | "VALIDATION"
  | "ENTITY_NOT_FOUND"
  | "BOOKING_NOT_FOUND"
  | "DATE_NOT_BOOKABLE"
  | "SITE_CLOSED"
  | "SLOT_UNAVAILABLE"
  | "LOAD_NOT_ALLOWED"
  | "DAILY_CAP_REACHED"
  | "ORDER_INVALID"
  | "INVALID_TRANSITION"
  | "TOO_LATE"
  | "TOO_EARLY"
  | "FORBIDDEN"
  | "CONFLICT"
  | "NOT_FOUND";

export class BookingError extends Error {
  constructor(
    public readonly code: BookingErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "BookingError";
  }
}

/** Postgres SQLSTATE of an error thrown by pg, possibly wrapped by Drizzle. */
export function pgErrorCode(err: unknown): string | undefined {
  let e: unknown = err;
  for (let i = 0; i < 3 && e && typeof e === "object"; i++) {
    const code = (e as { code?: unknown }).code;
    if (typeof code === "string" && /^[0-9A-Z]{5}$/.test(code)) return code;
    e = (e as { cause?: unknown }).cause;
  }
  return undefined;
}

export const PG_EXCLUSION_VIOLATION = "23P01";
export const PG_UNIQUE_VIOLATION = "23505";
