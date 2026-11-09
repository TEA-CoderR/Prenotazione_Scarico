import { describe, expect, it } from "vitest";
import { bookableDateRange, freeSlotsForDock } from "@/lib/scheduling/availability";
import { DEFAULT_SLOT_RULES, LoadNotAllowedError, resolveDuration } from "@/lib/scheduling/duration";
import { localTime, zonedToUtc } from "@/lib/time";

const TZ = "Europe/Rome";
const HOURS = [{ opens: "09:00", closes: "18:00" }];

describe("resolveDuration", () => {
  it("uses the agreed defaults", () => {
    expect(resolveDuration(DEFAULT_SLOT_RULES, "parcel", 0, 20)).toBe(15);
    expect(resolveDuration(DEFAULT_SLOT_RULES, "van", 6, 0)).toBe(30);
    expect(resolveDuration(DEFAULT_SLOT_RULES, "truck", 18, 0)).toBe(60);
    expect(resolveDuration(DEFAULT_SLOT_RULES, "semi", 33, 0)).toBe(90);
  });

  it("rejects loads that do not fit the vehicle", () => {
    expect(() => resolveDuration(DEFAULT_SLOT_RULES, "parcel", 0, 21)).toThrow(LoadNotAllowedError);
    expect(() => resolveDuration(DEFAULT_SLOT_RULES, "van", 7, 0)).toThrow(LoadNotAllowedError);
    expect(() => resolveDuration(DEFAULT_SLOT_RULES, "van", 0, 0)).toThrow(LoadNotAllowedError);
  });
});

describe("freeSlotsForDock", () => {
  it("offers 18 half-hour slots in a 09:00–18:00 day", () => {
    const slots = freeSlotsForDock({ date: "2026-11-10", tz: TZ, opening: HOURS, durationMin: 30, busy: [], stepMin: 30 });
    expect(slots).toHaveLength(18);
    expect(localTime(slots[0].start, TZ)).toBe("09:00");
    expect(localTime(slots.at(-1)!.start, TZ)).toBe("17:30");
  });

  it("never lets a slot run past closing time", () => {
    const slots = freeSlotsForDock({ date: "2026-11-10", tz: TZ, opening: HOURS, durationMin: 90, busy: [] });
    expect(localTime(slots.at(-1)!.start, TZ)).toBe("16:30");
  });

  it("removes slots that overlap a busy interval but keeps adjacent ones", () => {
    const busy = [{ start: zonedToUtc("2026-11-10", "10:00", TZ), end: zonedToUtc("2026-11-10", "11:00", TZ) }];
    const times = freeSlotsForDock({ date: "2026-11-10", tz: TZ, opening: HOURS, durationMin: 30, busy }).map((s) =>
      localTime(s.start, TZ),
    );
    expect(times).toContain("09:30");
    expect(times).not.toContain("09:45");
    expect(times).not.toContain("10:30");
    expect(times).toContain("11:00");
  });

  it("respects a lunch break expressed as two opening ranges", () => {
    const opening = [
      { opens: "09:00", closes: "13:00" },
      { opens: "14:00", closes: "18:00" },
    ];
    const times = freeSlotsForDock({ date: "2026-11-10", tz: TZ, opening, durationMin: 60, busy: [] }).map((s) =>
      localTime(s.start, TZ),
    );
    expect(times).toContain("12:00");
    expect(times).not.toContain("12:15");
    expect(times).toContain("14:00");
  });
});

describe("bookableDateRange", () => {
  const policy = { maxDaysAhead: 14, bookingCutoffTime: "16:00" };

  it("allows tomorrow before 16:00", () => {
    const now = zonedToUtc("2026-11-09", "15:59", TZ);
    expect(bookableDateRange(now, TZ, policy)).toEqual({ first: "2026-11-10", last: "2026-11-23" });
  });

  it("starts the day after tomorrow from 16:00", () => {
    const now = zonedToUtc("2026-11-09", "16:00", TZ);
    expect(bookableDateRange(now, TZ, policy).first).toBe("2026-11-11");
  });
});
