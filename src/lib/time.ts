// Wall-clock helpers for a named IANA time zone (default Europe/Rome), without extra deps.
// Dates are passed around as "YYYY-MM-DD" strings and times as "HH:MM"; instants as Date (UTC).

const fmtCache = new Map<string, Intl.DateTimeFormat>();

function formatter(tz: string): Intl.DateTimeFormat {
  let f = fmtCache.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    fmtCache.set(tz, f);
  }
  return f;
}

function parts(instant: Date, tz: string) {
  const out: Record<string, number> = {};
  for (const p of formatter(tz).formatToParts(instant)) {
    if (p.type !== "literal") out[p.type] = Number(p.value);
  }
  return out as { year: number; month: number; day: number; hour: number; minute: number; second: number };
}

/** Offset of `tz` from UTC at `instant`, in minutes (Rome: +60 in winter, +120 in summer). */
export function tzOffsetMinutes(instant: Date, tz: string): number {
  const p = parts(instant, tz);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return Math.round((asUtc - Math.floor(instant.getTime() / 1000) * 1000) / 60000);
}

/** The UTC instant of local wall time `hhmm` on `ymd` in `tz`. */
export function zonedToUtc(ymd: string, hhmm: string, tz: string): Date {
  const [y, m, d] = ymd.split("-").map(Number);
  const [hh, mm] = hhmm.split(":").map(Number);
  const guess = Date.UTC(y, m - 1, d, hh, mm);
  const off1 = tzOffsetMinutes(new Date(guess), tz);
  let t = guess - off1 * 60000;
  const off2 = tzOffsetMinutes(new Date(t), tz);
  if (off2 !== off1) t = guess - off2 * 60000;
  return new Date(t);
}

/** Local calendar date ("YYYY-MM-DD") of `instant` in `tz`. */
export function localDate(instant: Date, tz: string): string {
  const p = parts(instant, tz);
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

/** Local wall time ("HH:MM") of `instant` in `tz`. */
export function localTime(instant: Date, tz: string): string {
  const p = parts(instant, tz);
  return `${pad(p.hour)}:${pad(p.minute)}`;
}

/** ISO weekday of a calendar date: 1 = Monday .. 7 = Sunday. */
export function isoWeekday(ymd: string): number {
  const [y, m, d] = ymd.split("-").map(Number);
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return dow === 0 ? 7 : dow;
}

export function addDays(ymd: string, days: number): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** "HH:MM" or "HH:MM:SS" → minutes after midnight. */
export function toMinutes(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

export function fromMinutes(min: number): string {
  return `${pad(Math.floor(min / 60))}:${pad(min % 60)}`;
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}
