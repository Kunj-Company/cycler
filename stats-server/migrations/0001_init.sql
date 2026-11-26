-- Cycler anonymous stats, schema v1.
-- Nothing here stores an IP address, request headers, user agent or a timestamp
-- finer than an ISO week ("2026-W48").

-- One row per install per completed week. Upserted on (install, week).
CREATE TABLE reports (
  install         TEXT    NOT NULL,             -- random v4 UUID made on the phone
  week            TEXT    NOT NULL,             -- ISO week the stats are for, e.g. 2026-W48
  app_version     TEXT    NOT NULL,
  days_active     INTEGER NOT NULL CHECK (days_active BETWEEN 0 AND 7),
  rides_started   INTEGER NOT NULL CHECK (rides_started BETWEEN 0 AND 500),
  rides_finished  INTEGER NOT NULL CHECK (rides_finished BETWEEN 0 AND 500),
  rides_discarded INTEGER NOT NULL CHECK (rides_discarded BETWEEN 0 AND 500),
  km              REAL    NOT NULL CHECK (km BETWEEN 0 AND 20000),
  received_week   TEXT    NOT NULL,             -- server ISO week when last (re)received
  PRIMARY KEY (install, week)
) WITHOUT ROWID;

CREATE INDEX reports_week ON reports (week);
CREATE INDEX reports_week_version ON reports (week, app_version);

-- Feature usage counts for one install-week. Replaced together with its report.
CREATE TABLE features (
  install TEXT    NOT NULL,
  week    TEXT    NOT NULL,
  key     TEXT    NOT NULL,
  count   INTEGER NOT NULL CHECK (count BETWEEN 1 AND 10000),
  PRIMARY KEY (install, week, key)
) WITHOUT ROWID;

CREATE INDEX features_week_key ON features (week, key);

-- Anonymous ~11 km location cells (lat/lon floored to 0.1 degree, south-west corner).
-- No install ID and no received timestamp. WITHOUT ROWID so physical/insertion order
-- cannot be used to link an area report to an install report.
CREATE TABLE areas (
  report      TEXT    NOT NULL,                 -- random UUID per area report, only for retry dedup
  week        TEXT    NOT NULL,
  app_version TEXT    NOT NULL,
  lat         REAL    NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lon         REAL    NOT NULL CHECK (lon BETWEEN -180 AND 180),
  kind        TEXT    NOT NULL CHECK (kind IN ('start', 'end')),
  rides       INTEGER NOT NULL CHECK (rides BETWEEN 1 AND 500),
  km          REAL    NOT NULL CHECK (km BETWEEN 0 AND 20000),
  PRIMARY KEY (report, lat, lon, kind)
) WITHOUT ROWID;

CREATE INDEX areas_week ON areas (week);
CREATE INDEX areas_cell ON areas (lat, lon, kind);
