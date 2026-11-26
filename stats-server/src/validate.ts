// Strict validation for the v1 data contract. Anything unexpected is rejected.

import { WEEK_MS, isoWeekOf, parseWeek, weekMonday } from "./week";

export const MAX_BODY_BYTES = 16 * 1024;
/** Accept weeks from this many weeks ago (phone keeps 8; small slack for late senders). */
export const MAX_WEEKS_OLD = 10;
/** Accept weeks up to this many weeks ahead of server time (device clock / timezone skew). */
export const MAX_WEEKS_AHEAD = 1;
export const MAX_FEATURE_KEYS = 64;
export const MAX_CELLS = 50;

const UUID_V4_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const APP_VERSION_RE = /^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$/;
export const FEATURE_KEY_RE = /^[a-z][a-z0-9_]{1,39}$/;

export class Invalid extends Error {}

export interface Report {
  install: string;
  app_version: string;
  week: string;
  days_active: number;
  rides_started: number;
  rides_finished: number;
  rides_discarded: number;
  km: number;
  features: [string, number][];
}

export interface AreaCell {
  lat: number;
  lon: number;
  kind: "start" | "end";
  rides: number;
  km: number;
}

export interface AreaReport {
  report: string;
  app_version: string;
  week: string;
  cells: AreaCell[];
}

function fail(what: string): never {
  throw new Invalid(what);
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function onlyKeys(obj: Record<string, unknown>, required: string[], optional: string[], where: string) {
  for (const k of Object.keys(obj)) {
    if (!required.includes(k) && !optional.includes(k)) fail(`${where}: unknown field`);
  }
  for (const k of required) {
    if (!(k in obj)) fail(`${where}: missing ${k}`);
  }
}

function int(v: unknown, min: number, max: number, name: string): number {
  if (typeof v !== "number" || !Number.isInteger(v) || v < min || v > max) fail(name);
  return v;
}

function km(v: unknown, name: string): number {
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0 || v > 20000) fail(name);
  return Math.round(v * 10) / 10;
}

/** A coordinate already floored to one decimal by the phone. */
function cellCoord(v: unknown, limit: number, name: string): number {
  if (typeof v !== "number" || !Number.isFinite(v) || v < -limit || v > limit) fail(name);
  const tenths = Math.round(v * 10);
  if (Math.abs(v * 10 - tenths) > 1e-6) fail(name);
  return tenths / 10;
}

function uuid(v: unknown, name: string): string {
  if (typeof v !== "string" || !UUID_V4_RE.test(v)) fail(name);
  return v.toLowerCase();
}

function appVersion(v: unknown): string {
  if (typeof v !== "string" || !APP_VERSION_RE.test(v)) fail("app_version");
  return v;
}

function schema(v: unknown) {
  if (v !== 1) fail("schema");
}

/** Week must be well-formed and within [now - MAX_WEEKS_OLD, now + MAX_WEEKS_AHEAD]. */
export function week(v: unknown, now: number): string {
  const monday = parseWeek(v);
  if (monday === null) fail("week");
  const cur = isoWeekOf(now);
  const diff = Math.round((monday - weekMonday(cur.year, cur.week)) / WEEK_MS);
  if (diff > MAX_WEEKS_AHEAD || diff < -MAX_WEEKS_OLD) fail("week out of range");
  return v as string;
}

export function parseReport(body: unknown, now: number): Report {
  if (!isPlainObject(body)) fail("body");
  onlyKeys(
    body,
    ["schema", "install", "app_version", "week", "days_active", "rides_started", "rides_finished", "rides_discarded", "km"],
    ["features"],
    "report",
  );
  schema(body.schema);
  const features: [string, number][] = [];
  if (body.features !== undefined) {
    if (!isPlainObject(body.features)) fail("features");
    const entries = Object.entries(body.features);
    if (entries.length > MAX_FEATURE_KEYS) fail("features: too many keys");
    for (const [k, v] of entries) {
      if (!FEATURE_KEY_RE.test(k)) fail("features: bad key");
      features.push([k, int(v, 1, 10000, "features: bad count")]);
    }
  }
  return {
    install: uuid(body.install, "install"),
    app_version: appVersion(body.app_version),
    week: week(body.week, now),
    days_active: int(body.days_active, 0, 7, "days_active"),
    rides_started: int(body.rides_started, 0, 500, "rides_started"),
    rides_finished: int(body.rides_finished, 0, 500, "rides_finished"),
    rides_discarded: int(body.rides_discarded, 0, 500, "rides_discarded"),
    km: km(body.km, "km"),
    features,
  };
}

export function parseArea(body: unknown, now: number): AreaReport {
  if (!isPlainObject(body)) fail("body");
  onlyKeys(body, ["schema", "report", "app_version", "week", "cells"], [], "area");
  schema(body.schema);
  if (!Array.isArray(body.cells) || body.cells.length < 1 || body.cells.length > MAX_CELLS) fail("cells");
  const seen = new Set<string>();
  const cells: AreaCell[] = body.cells.map((c: unknown) => {
    if (!isPlainObject(c)) fail("cell");
    onlyKeys(c, ["lat", "lon", "kind", "rides", "km"], [], "cell");
    if (c.kind !== "start" && c.kind !== "end") fail("cell kind");
    const cell: AreaCell = {
      lat: cellCoord(c.lat, 90, "cell lat"),
      lon: cellCoord(c.lon, 180, "cell lon"),
      kind: c.kind,
      rides: int(c.rides, 1, 500, "cell rides"),
      km: km(c.km, "cell km"),
    };
    const id = `${cell.lat}|${cell.lon}|${cell.kind}`;
    if (seen.has(id)) fail("cell duplicate");
    seen.add(id);
    return cell;
  });
  return {
    report: uuid(body.report, "report"),
    app_version: appVersion(body.app_version),
    week: week(body.week, now),
    cells,
  };
}
