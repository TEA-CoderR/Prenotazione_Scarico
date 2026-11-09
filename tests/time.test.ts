import { describe, expect, it } from "vitest";
import { addDays, isoWeekday, localDate, localTime, zonedToUtc } from "@/lib/time";

const TZ = "Europe/Rome";

describe("time helpers", () => {
  it("converts Rome wall time to UTC in winter (+01:00)", () => {
    expect(zonedToUtc("2026-11-10", "09:00", TZ).toISOString()).toBe("2026-11-10T08:00:00.000Z");
  });

  it("converts Rome wall time to UTC in summer (+02:00)", () => {
    expect(zonedToUtc("2026-06-10", "09:00", TZ).toISOString()).toBe("2026-06-10T07:00:00.000Z");
  });

  it("handles the day after the spring DST switch (29 Mar 2026)", () => {
    expect(zonedToUtc("2026-03-30", "09:00", TZ).toISOString()).toBe("2026-03-30T07:00:00.000Z");
    expect(zonedToUtc("2026-03-27", "09:00", TZ).toISOString()).toBe("2026-03-27T08:00:00.000Z");
  });

  it("reads local date and time back", () => {
    const t = new Date("2026-11-10T22:30:00Z"); // 23:30 in Rome
    expect(localDate(t, TZ)).toBe("2026-11-10");
    expect(localTime(t, TZ)).toBe("23:30");
    expect(localDate(new Date("2026-11-10T23:30:00Z"), TZ)).toBe("2026-11-11");
  });

  it("computes ISO weekdays and adds days across months", () => {
    expect(isoWeekday("2026-11-09")).toBe(1); // Monday
    expect(isoWeekday("2026-11-15")).toBe(7); // Sunday
    expect(addDays("2026-11-30", 1)).toBe("2026-12-01");
  });
});
