-- Local-only helper views, added to the laptop's stats.db after each pull.
-- They add `week_start` (the Monday of the ISO week, 'YYYY-MM-DD') so Metabase can
-- chart weeks on a real date axis. Jan 4 is always in ISO week 1.

DROP VIEW IF EXISTS v_reports;
DROP VIEW IF EXISTS v_features;
DROP VIEW IF EXISTS v_areas;

CREATE VIEW v_reports AS
SELECT r.*,
       date(substr(r.week, 1, 4) || '-01-04',
            '-' || ((CAST(strftime('%w', substr(r.week, 1, 4) || '-01-04') AS INTEGER) + 6) % 7) || ' days',
            '+' || ((CAST(substr(r.week, 7, 2) AS INTEGER) - 1) * 7) || ' days') AS week_start
FROM reports r;

CREATE VIEW v_features AS
SELECT f.*,
       date(substr(f.week, 1, 4) || '-01-04',
            '-' || ((CAST(strftime('%w', substr(f.week, 1, 4) || '-01-04') AS INTEGER) + 6) % 7) || ' days',
            '+' || ((CAST(substr(f.week, 7, 2) AS INTEGER) - 1) * 7) || ' days') AS week_start
FROM features f;

CREATE VIEW v_areas AS
SELECT a.*,
       date(substr(a.week, 1, 4) || '-01-04',
            '-' || ((CAST(strftime('%w', substr(a.week, 1, 4) || '-01-04') AS INTEGER) + 6) % 7) || ' days',
            '+' || ((CAST(substr(a.week, 7, 2) AS INTEGER) - 1) * 7) || ' days') AS week_start
FROM areas a;
