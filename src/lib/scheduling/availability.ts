import { addDays, localDate, localTime, toMinutes, zonedToUtc, fromMinutes } from "@/lib/time";

export interface OpeningRange {
  opens: string; // "HH:MM" local
  closes: string; // "HH:MM" local
}

export interface BookingWindowPolicy {
  maxDaysAhead: number;
  /** Bookings for day D must be placed before this local time on D-1, e.g. "16:00". */
  bookingCutoffTime: string;
}

export interface Interval {
  start: Date;
  end: Date;
}

/** Slot starts are aligned to this grid, counted from each opening time. */
export const SLOT_STEP_MIN = 15;

/** First and last local dates a carrier may book right now. */
export function bookableDateRange(now: Date, tz: string, policy: BookingWindowPolicy) {
  const today = localDate(now, tz);
  const beforeCutoff = toMinutes(localTime(now, tz)) < toMinutes(policy.bookingCutoffTime);
  return {
    first: addDays(today, beforeCutoff ? 1 : 2),
    last: addDays(today, policy.maxDaysAhead),
  };
}

export function isDateBookable(ymd: string, now: Date, tz: string, policy: BookingWindowPolicy): boolean {
  const { first, last } = bookableDateRange(now, tz, policy);
  return ymd >= first && ymd <= last;
}

function overlaps(a: Interval, b: Interval): boolean {
  return a.start < b.end && b.start < a.end;
}

/**
 * Free start times for ONE dock on ONE local date.
 * `busy` = live bookings on that dock (requested/confirmed/arrived/unloading).
 * Caller is responsible for closures and the bookable-date window.
 */
export function freeSlotsForDock(params: {
  date: string;
  tz: string;
  opening: OpeningRange[];
  durationMin: number;
  busy: Interval[];
  stepMin?: number;
}): Interval[] {
  const { date, tz, opening, durationMin, busy } = params;
  const step = params.stepMin ?? SLOT_STEP_MIN;
  const slots: Interval[] = [];
  for (const range of [...opening].sort((a, b) => a.opens.localeCompare(b.opens))) {
    const open = toMinutes(range.opens);
    const close = toMinutes(range.closes);
    for (let m = open; m + durationMin <= close; m += step) {
      const start = zonedToUtc(date, fromMinutes(m), tz);
      const end = new Date(start.getTime() + durationMin * 60000);
      const candidate = { start, end };
      if (!busy.some((b) => overlaps(candidate, b))) slots.push(candidate);
    }
  }
  return slots;
}
