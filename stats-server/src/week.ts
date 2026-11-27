// ISO-8601 week helpers (UTC). Weeks are strings like "2026-W48".

const DAY_MS = 86_400_000;
export const WEEK_MS = 7 * DAY_MS;

export interface IsoWeek {
  year: number;
  week: number;
}

/** ISO week containing the given instant (UTC). */
export function isoWeekOf(ms: number): IsoWeek {
  const d = new Date(ms);
  const midnight = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  const dayIndex = (d.getUTCDay() + 6) % 7; // Monday = 0
  const thursday = midnight + (3 - dayIndex) * DAY_MS;
  const year = new Date(thursday).getUTCFullYear();
  const week = Math.floor((thursday - Date.UTC(year, 0, 1)) / WEEK_MS) + 1;
  return { year, week };
}

export function formatWeek({ year, week }: IsoWeek): string {
  return `${year}-W${String(week).padStart(2, "0")}`;
}

/** UTC midnight of the Monday that starts the given ISO week. */
export function weekMonday(year: number, week: number): number {
  const jan4 = Date.UTC(year, 0, 4); // Jan 4 is always in week 1
  const jan4Index = (new Date(jan4).getUTCDay() + 6) % 7;
  return jan4 - jan4Index * DAY_MS + (week - 1) * WEEK_MS;
}

export function weeksInYear(year: number): number {
  return isoWeekOf(Date.UTC(year, 11, 28)).week; // Dec 28 is always in the last week
}

const WEEK_RE = /^(\d{4})-W(\d{2})$/;

/** Parses "YYYY-Www". Returns the Monday (UTC ms) or null if malformed / nonexistent. */
export function parseWeek(s: unknown): number | null {
  if (typeof s !== "string") return null;
  const m = WEEK_RE.exec(s);
  if (!m) return null;
  const year = Number(m[1]);
  const week = Number(m[2]);
  if (year < 2000 || year > 2999) return null;
  if (week < 1 || week > weeksInYear(year)) return null;
  return weekMonday(year, week);
}

/** Server's current ISO week as a string. */
export function currentWeek(now: number = Date.now()): string {
  return formatWeek(isoWeekOf(now));
}

/** Week string N weeks away from the week containing `now` (negative = past). */
export function weekOffset(n: number, now: number = Date.now()): string {
  const { year, week } = isoWeekOf(now);
  return formatWeek(isoWeekOf(weekMonday(year, week) + n * WEEK_MS + 3 * DAY_MS));
}
