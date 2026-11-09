import { describe, expect, it } from "vitest";
import { easterSunday, italianHolidays } from "@/lib/holidays";

describe("Italian holidays", () => {
  it("computes Easter", () => {
    expect(easterSunday(2026)).toBe("2026-04-05");
    expect(easterSunday(2027)).toBe("2027-03-28");
  });

  it("includes Easter Monday and San Francesco from 2026", () => {
    const dates = italianHolidays(2027).map((h) => h.date);
    expect(dates).toContain("2027-03-29");
    expect(dates).toContain("2027-10-04");
    expect(dates).toContain("2027-12-26");
    expect(dates).toHaveLength(13);
  });

  it("does not include San Francesco before 2026", () => {
    expect(italianHolidays(2025).map((h) => h.date)).not.toContain("2025-10-04");
  });
});
