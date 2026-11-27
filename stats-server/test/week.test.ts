import { describe, expect, it } from "vitest";
import { currentWeek, isoWeekOf, parseWeek, weekOffset, weeksInYear } from "../src/week";

describe("ISO week helpers", () => {
  it("computes ISO weeks across year boundaries", () => {
    expect(isoWeekOf(Date.UTC(2026, 10, 27))).toEqual({ year: 2026, week: 48 });
    expect(isoWeekOf(Date.UTC(2021, 0, 3))).toEqual({ year: 2020, week: 53 }); // Sunday
    expect(isoWeekOf(Date.UTC(2024, 11, 30))).toEqual({ year: 2025, week: 1 }); // Monday
    expect(isoWeekOf(Date.UTC(2027, 0, 1))).toEqual({ year: 2026, week: 53 });
  });

  it("knows which years have 53 weeks", () => {
    expect(weeksInYear(2020)).toBe(53);
    expect(weeksInYear(2025)).toBe(52);
    expect(weeksInYear(2026)).toBe(53);
  });

  it("parses only real weeks", () => {
    expect(parseWeek("2026-W48")).toBe(Date.UTC(2026, 10, 23));
    expect(parseWeek("2025-W01")).toBe(Date.UTC(2024, 11, 30));
    expect(parseWeek("2026-W53")).toBe(Date.UTC(2026, 11, 28));
    expect(parseWeek("2025-W53")).toBeNull();
    expect(parseWeek("2025-W00")).toBeNull();
    expect(parseWeek("2025-W1")).toBeNull();
    expect(parseWeek("2025W01")).toBeNull();
    expect(parseWeek("1999-W10")).toBeNull();
    expect(parseWeek(202501)).toBeNull();
  });

  it("offsets weeks", () => {
    const now = Date.UTC(2026, 11, 30); // 2026-W53
    expect(currentWeek(now)).toBe("2026-W53");
    expect(weekOffset(1, now)).toBe("2027-W01");
    expect(weekOffset(-53, now)).toBe("2025-W52");
  });
});
