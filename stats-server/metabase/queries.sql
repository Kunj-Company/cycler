-- Cycler stats: ready-to-paste Metabase questions (SQLite dialect).
-- In Metabase: + New -> SQL query -> database "Cycler stats" -> paste one block -> Visualize -> Save.
--
-- Tables come from Cloudflare D1 (reports, features, areas). The exporter adds views
-- v_reports / v_features / v_areas with an extra `week_start` column (Monday of the ISO
-- week as YYYY-MM-DD) so charts get a real date axis.
--
-- "Active install" = an install whose weekly report has days_active > 0.
-- The phone also reports quiet weeks (days_active = 0); those count as "reporting", not active.
-- Only installs with stats switched on are counted, so treat totals as a lower bound.
--
-- Each block starts with "-- ===== Q<n>." and ends with a semicolon.


-- ===== Q1. Weekly active installs  (chart: line, x = week_start)
SELECT week_start,
       week,
       SUM(days_active > 0) AS active_installs,
       COUNT(*)             AS reporting_installs
FROM v_reports
GROUP BY week
ORDER BY week;


-- ===== Q2. New installs per week  (chart: bar, x = week_start)
-- "New" = first week we ever received a report from that install. Installs that existed
-- before stats were added to the app show up as "new" in the first weeks after launch.
WITH firsts AS (
  SELECT install, MIN(week_start) AS week_start
  FROM v_reports
  GROUP BY install
)
SELECT week_start,
       COUNT(*) AS new_installs
FROM firsts
GROUP BY week_start
ORDER BY week_start;


-- ===== Q3. Retention by cohort: % of each week's new installs active 1, 4 and 12 weeks later
-- (chart: table, or line with the three % columns). NULL = cohort too young to know yet.
WITH firsts AS (
  SELECT install, MIN(week_start) AS cohort
  FROM v_reports
  GROUP BY install
),
active AS (
  SELECT DISTINCT install, week_start
  FROM v_reports
  WHERE days_active > 0
),
latest AS (
  SELECT MAX(week_start) AS w FROM v_reports
),
flags AS (
  SELECT f.cohort,
         EXISTS (SELECT 1 FROM active a WHERE a.install = f.install AND a.week_start = date(f.cohort, '+7 days'))  AS w1,
         EXISTS (SELECT 1 FROM active a WHERE a.install = f.install AND a.week_start = date(f.cohort, '+28 days')) AS w4,
         EXISTS (SELECT 1 FROM active a WHERE a.install = f.install AND a.week_start = date(f.cohort, '+84 days')) AS w12
  FROM firsts f
)
SELECT cohort AS cohort_week_start,
       COUNT(*) AS cohort_installs,
       CASE WHEN date(cohort, '+7 days')  <= (SELECT w FROM latest) THEN ROUND(100.0 * SUM(w1)  / COUNT(*), 1) END AS week_1_pct,
       CASE WHEN date(cohort, '+28 days') <= (SELECT w FROM latest) THEN ROUND(100.0 * SUM(w4)  / COUNT(*), 1) END AS week_4_pct,
       CASE WHEN date(cohort, '+84 days') <= (SELECT w FROM latest) THEN ROUND(100.0 * SUM(w12) / COUNT(*), 1) END AS week_12_pct
FROM flags
GROUP BY cohort
ORDER BY cohort;


-- ===== Q4. Community totals so far  (chart: number / table)
SELECT ROUND(SUM(km), 1)       AS total_km,
       SUM(rides_finished)     AS total_rides_saved,
       COUNT(DISTINCT install) AS installs_ever_reported,
       COUNT(DISTINCT week)    AS weeks_of_data
FROM reports;


-- ===== Q5. Community km per week and cumulative km  (chart: line/combo, x = week_start)
SELECT week_start,
       week,
       ROUND(SUM(km), 1)                          AS km,
       ROUND(SUM(SUM(km)) OVER (ORDER BY week), 1) AS cumulative_km
FROM v_reports
GROUP BY week
ORDER BY week;


-- ===== Q6. Rides started / finished / discarded per week  (chart: bar or line, x = week_start)
SELECT week_start,
       week,
       SUM(rides_started)   AS rides_started,
       SUM(rides_finished)  AS rides_finished,
       SUM(rides_discarded) AS rides_discarded,
       ROUND(100.0 * SUM(rides_discarded) / NULLIF(SUM(rides_started), 0), 1) AS discarded_pct
FROM v_reports
GROUP BY week
ORDER BY week;


-- ===== Q7. km and rides per active install: average, median, p90  (chart: line, x = week_start)
-- SQLite has no percentile function, so ranks are computed with window functions.
-- Median = mean of the middle one or two values; p90 = nearest-rank (smallest value with
-- rank >= 0.9 * n), written with integer maths so it needs no math extension.
WITH a AS (
  SELECT week, week_start, km, rides_finished
  FROM v_reports
  WHERE days_active > 0
),
by_km AS (
  SELECT week, km,
         ROW_NUMBER() OVER (PARTITION BY week ORDER BY km) AS rn,
         COUNT(*)     OVER (PARTITION BY week)             AS n
  FROM a
),
by_rides AS (
  SELECT week, rides_finished,
         ROW_NUMBER() OVER (PARTITION BY week ORDER BY rides_finished) AS rn,
         COUNT(*)     OVER (PARTITION BY week)                         AS n
  FROM a
),
km_stats AS (
  SELECT week,
         AVG(CASE WHEN rn IN ((n + 1) / 2, (n + 2) / 2) THEN km END) AS median_km,
         MIN(CASE WHEN rn * 10 >= n * 9 THEN km END)                 AS p90_km
  FROM by_km
  GROUP BY week
),
ride_stats AS (
  SELECT week,
         AVG(CASE WHEN rn IN ((n + 1) / 2, (n + 2) / 2) THEN rides_finished END) AS median_rides,
         MIN(CASE WHEN rn * 10 >= n * 9 THEN rides_finished END)                 AS p90_rides
  FROM by_rides
  GROUP BY week
)
SELECT a.week_start,
       a.week,
       COUNT(*)                         AS active_installs,
       ROUND(AVG(a.km), 1)              AS avg_km,
       ROUND(k.median_km, 1)            AS median_km,
       ROUND(k.p90_km, 1)               AS p90_km,
       ROUND(AVG(a.rides_finished), 2)  AS avg_rides,
       r.median_rides                   AS median_rides,
       r.p90_rides                      AS p90_rides
FROM a
JOIN km_stats   k ON k.week = a.week
JOIN ride_stats r ON r.week = a.week
GROUP BY a.week
ORDER BY a.week;


-- ===== Q8. Feature adoption: % of active installs that used each feature, per week
-- (chart: line, x = week_start, series = feature; or filter to one feature)
WITH active AS (
  SELECT week, week_start, COUNT(*) AS active_installs
  FROM v_reports
  WHERE days_active > 0
  GROUP BY week
),
used AS (
  SELECT f.week, f.key, COUNT(*) AS installs, SUM(f.count) AS uses
  FROM features f
  JOIN reports r ON r.install = f.install AND r.week = f.week AND r.days_active > 0
  GROUP BY f.week, f.key
)
SELECT a.week_start,
       a.week,
       u.key                                           AS feature,
       u.installs                                      AS installs_using,
       a.active_installs,
       ROUND(100.0 * u.installs / a.active_installs, 1) AS pct_of_active,
       u.uses
FROM used u
JOIN active a ON a.week = u.week
ORDER BY a.week, pct_of_active DESC;


-- ===== Q9. Feature adoption over the last 4 weeks (one row per feature)  (chart: row/bar)
WITH recent AS (
  SELECT DISTINCT week FROM reports ORDER BY week DESC LIMIT 4
),
active AS (
  SELECT COUNT(DISTINCT install) AS n
  FROM reports
  WHERE days_active > 0 AND week IN (SELECT week FROM recent)
)
SELECT f.key                                                  AS feature,
       COUNT(DISTINCT f.install)                              AS installs_using,
       ROUND(100.0 * COUNT(DISTINCT f.install) / (SELECT n FROM active), 1) AS pct_of_active,
       SUM(f.count)                                           AS uses
FROM features f
JOIN reports r ON r.install = f.install AND r.week = f.week AND r.days_active > 0
WHERE f.week IN (SELECT week FROM recent)
GROUP BY f.key
ORDER BY pct_of_active DESC;


-- ===== Q10. Active installs by app version per week  (chart: stacked area/bar, x = week_start, series = app_version)
-- Debug builds ("-debug") are folded into their release version; drop the replace() to see them apart.
SELECT week_start,
       week,
       replace(app_version, '-debug', '') AS app_version,
       COUNT(*)                            AS active_installs
FROM v_reports
WHERE days_active > 0
GROUP BY week, replace(app_version, '-debug', '')
ORDER BY week, app_version;


-- ===== Q11. Rides and km by area cell (ride starts), all time  (chart: Map -> Pin map)
-- Cells are ~11 km squares (lat/lon floored to 0.1 degree). latitude/longitude are the
-- cell centre. Cells with fewer than 5 area reports are hidden so small places stay private.
-- Use kind = 'end' to map where rides finish instead. Add e.g. AND week >= '2026-W40' to limit time.
SELECT ROUND(lat + 0.05, 2)    AS latitude,
       ROUND(lon + 0.05, 2)    AS longitude,
       COUNT(DISTINCT report)  AS area_reports,
       SUM(rides)              AS rides,
       ROUND(SUM(km), 1)       AS km
FROM areas
WHERE kind = 'start'
GROUP BY lat, lon
HAVING COUNT(DISTINCT report) >= 5
ORDER BY rides DESC;


-- ===== Q12. Area reports per week (how many anonymous area reports arrived)  (chart: bar)
SELECT week_start,
       week,
       COUNT(DISTINCT report) AS area_reports,
       SUM(CASE WHEN kind = 'start' THEN rides ELSE 0 END) AS rides_with_location
FROM v_areas
GROUP BY week
ORDER BY week;
