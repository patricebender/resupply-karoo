// Load a GeoJSONSeq of filtered OSM POIs into a spatial SQLite database.
//
//   npx tsx load-into-sqlite.ts <input.geojsonseq> <output.sqlite>
//
// Produces:
//   poi(id, lat, lng, type, category, name)  — one row per POI
//   poi_rtree(rowid, minLat, maxLat, minLng, maxLng)  — R*Tree spatial index
//
// Type/category come from the shared category rules so the map pins match.

import Database from "better-sqlite3";
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";
import { resolvePoi } from "./categories.js";

interface GeoJsonFeature {
  type: "Feature";
  id?: string; // osmium --add-unique-id=type_id puts e.g. "n16257496" here
  geometry: { type: string; coordinates: unknown };
  properties: Record<string, string>;
}

// Types worth keeping even when unnamed — given a generic label instead of dropping.
// Water sources are named by subtype instead (nameForWater) since they share the
// REST_STOP type; this table covers the non-water default.
const DEFAULT_NAME: Record<string, string> = {
  RESTROOM: "Toilet", // amenity=toilets
  ATM: "ATM", // amenity=atm/bank — almost never carries a name in OSM
  CAMPING: "Campsite", // camp_site/wilderness_hut/shelter — huts & shelters often unnamed
  PHARMACY: "Pharmacy", // usually named, but keep the unnamed ones too
};

/** Water subtypes derived from the matched OSM tag. Drives the list/detail label. */
type WaterSubtype = "tap" | "fountain" | "spring" | "well" | "graveyard";

/** Generic display name for an unnamed water source, by subtype. */
const WATER_NAME: Record<WaterSubtype, string> = {
  tap: "Water",
  fountain: "Fountain",
  spring: "Spring",
  well: "Well",
  graveyard: "Graveyard",
};

/**
 * Classify a water POI by its OSM tags. Order matters only for the (rare) element
 * carrying several of these; the first match wins, mirroring the filter order.
 */
function waterSubtype(props: Record<string, string>): WaterSubtype {
  if (props.amenity === "drinking_water" || props.man_made === "water_tap") return "tap";
  if (props.amenity === "fountain") return "fountain";
  if (props.natural === "spring") return "spring";
  if (props.man_made === "water_well") return "well";
  // amenity=grave_yard / landuse=cemetery
  return "graveyard";
}

/**
 * Potability for a water source: yes | no | unknown.
 * - Graveyards are a heuristic (the tap is never tagged) → always unknown.
 * - amenity=drinking_water means potable by definition → yes unless tagged otherwise.
 * - Fountains/springs/wells are often non-potable → trust the drinking_water tag,
 *   else unknown. The rider decides from the detail card.
 */
function drinkingWater(
  props: Record<string, string>,
  subtype: WaterSubtype,
): "yes" | "no" | "unknown" {
  if (subtype === "graveyard") return "unknown";
  const tag = props.drinking_water;
  if (tag === "yes") return "yes";
  if (tag === "no") return "no";
  if (subtype === "tap") return "yes"; // drinking_water/water_tap: potable by definition
  return "unknown";
}

// OSM tags worth carrying to the detail view. Allowlist (not a passthrough of all
// tags) so the DB stays small and predictable. Kept in sync with the extension's
// PoiQuery tag parsing and the backend contract.
const TAG_ALLOWLIST = [
  "opening_hours",
  "website",
  "contact:website",
  "phone",
  "contact:phone",
  // Street address, shown on the detail view for places that have one (cafés,
  // shops, fuel — water/toilets rarely do). Kept as bare addr:* keys.
  "addr:street",
  "addr:housenumber",
  "addr:postcode",
  "addr:city",
  "description",
  "wikipedia",
  "wikidata",
] as const;

/** Pick the allowlisted, non-empty OSM tags from a feature's properties. */
function pickTags(props: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const key of TAG_ALLOWLIST) {
    const v = props[key];
    if (v != null && v !== "") {
      // Normalize contact:* aliases onto their bare form; first one wins.
      const norm = key.startsWith("contact:") ? key.slice("contact:".length) : key;
      if (out[norm] == null) out[norm] = v;
    }
  }
  return out;
}

/** Representative [lng, lat] for a feature: point directly, else polygon/line centroid. */
function representativePoint(geom: GeoJsonFeature["geometry"]): [number, number] | null {
  const c = geom.coordinates;
  if (geom.type === "Point") return c as [number, number];
  // Flatten nested coordinate arrays and average — good enough for a pin.
  const pts: number[][] = [];
  const walk = (a: unknown): void => {
    if (Array.isArray(a) && typeof a[0] === "number") {
      pts.push(a as number[]);
    } else if (Array.isArray(a)) {
      a.forEach(walk);
    }
  };
  walk(c);
  if (pts.length === 0) return null;
  const sum = pts.reduce<[number, number]>(
    (acc, p) => [acc[0] + (p[0] ?? 0), acc[1] + (p[1] ?? 0)],
    [0, 0],
  );
  return [sum[0] / pts.length, sum[1] / pts.length];
}

async function main() {
  const [input, output] = process.argv.slice(2);
  if (!input || !output) {
    console.error("usage: load-into-sqlite.ts <input.geojsonseq> <output.sqlite>");
    process.exit(1);
  }

  const db = new Database(output);
  // DELETE journal (not WAL): the output is a self-contained file meant to be
  // copied/bundled as a read-only asset, with no -wal/-shm sidecars.
  db.pragma("journal_mode = DELETE");
  db.exec(`
    DROP TABLE IF EXISTS poi;
    DROP TABLE IF EXISTS poi_rtree;
    CREATE TABLE poi (
      id        INTEGER PRIMARY KEY,
      osm_id    TEXT NOT NULL,
      lat       REAL NOT NULL,
      lng       REAL NOT NULL,
      type      TEXT NOT NULL,
      category  TEXT NOT NULL,
      name      TEXT,
      tags      TEXT,
      -- Which downloadable region a row came from. Left empty here; the region-file
      -- builder stamps it with the region id (build-region-file.sh) so the device can
      -- attribute rows for additive install (dedup) and per-region removal.
      region_id TEXT NOT NULL DEFAULT ''
    );
    CREATE VIRTUAL TABLE poi_rtree USING rtree(id, minLat, maxLat, minLng, maxLng);
  `);
  // Bump when the schema/tag allowlist/dedup changes so installed apps re-seed.
  // Kept in lockstep with BUNDLED_DB_VERSION in PoiDatabase.kt.
  db.pragma("user_version = 7");

  const insertPoi = db.prepare(
    "INSERT INTO poi (id, osm_id, lat, lng, type, category, name, tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
  );
  const insertRtree = db.prepare(
    "INSERT INTO poi_rtree (id, minLat, maxLat, minLng, maxLng) VALUES (?, ?, ?, ?, ?)",
  );

  let rowId = 0;
  let skipped = 0;
  let deduped = 0;
  const seen = new Set<string>();

  // Spatial dedup: OSM commonly maps the same place as *both* a node and its
  // building way/area, so we ingest two near-identical rows (different osm_id, same
  // name+category, coords equal to ~5 decimals). Collapse them by bucketing into a
  // ~40 m grid keyed by name+category. Checking the 3×3 neighbourhood avoids missing
  // a true pair that straddles a cell boundary. Generic-named POIs (Toilet/Water)
  // dedup only when co-located, so two distinct toilets are still kept.
  const GRID_LAT = 0.00036; // ~40 m
  const GRID_LNG = 0.00055; // ~40 m at ~48°N
  const placeSeen = new Set<string>();
  const cell = (lat: number, lng: number): [number, number] =>
    [Math.round(lat / GRID_LAT), Math.round(lng / GRID_LNG)];
  const placeKey = (name: string, category: string, gl: number, gn: number) =>
    `${name} ${category} ${gl} ${gn}`;
  const isDuplicatePlace = (name: string, category: string, lat: number, lng: number) => {
    const [cl, cn] = cell(lat, lng);
    for (let dl = -1; dl <= 1; dl++) {
      for (let dn = -1; dn <= 1; dn++) {
        if (placeSeen.has(placeKey(name, category, cl + dl, cn + dn))) return true;
      }
    }
    placeSeen.add(placeKey(name, category, cl, cn));
    return false;
  };

  const load = db.transaction((features: GeoJsonFeature[]) => {
    for (const f of features) {
      const resolved = resolvePoi(f.properties ?? {});
      if (!resolved) {
        skipped++;
        continue;
      }
      const pt = representativePoint(f.geometry);
      if (!pt) {
        skipped++;
        continue;
      }
      const [lng, lat] = pt;

      // Water sources share the REST_STOP type; classify by subtype for naming,
      // the distinct label, and potability.
      const subtype =
        resolved.category === "water" ? waterSubtype(f.properties) : null;

      // Quality filter: drop unnamed POIs (they render as a meaningless "Pin"),
      // EXCEPT water/toilets which are useful even without a name — give those a
      // generic label. This is the biggest lever against map clutter.
      const rawName = f.properties["name"] ?? null;
      let name = rawName;
      if (rawName == null) {
        name = subtype ? WATER_NAME[subtype] : DEFAULT_NAME[resolved.type] ?? null;
        if (name == null) {
          skipped++;
          continue; // unnamed and not a labelable type → drop
        }
      }

      const osmId = String(f.id ?? `${lat},${lng}`);
      if (seen.has(osmId)) continue;
      seen.add(osmId);

      // Drop the second (and further) copies of a place mapped multiple times.
      if (isDuplicatePlace(name!, resolved.category, lat, lng)) {
        deduped++;
        continue;
      }

      const tags = pickTags(f.properties ?? {});
      // Synthetic tags for water: the subtype (distinct label, shared pin) and a
      // normalized potability the detail page reads. Written here, not in pickTags,
      // since they're derived rather than passed through from OSM.
      if (subtype) {
        tags.water_subtype = subtype;
        tags.drinking_water = drinkingWater(f.properties, subtype);
      }
      const tagsJson = Object.keys(tags).length ? JSON.stringify(tags) : null;

      rowId++;
      insertPoi.run(
        rowId, osmId, lat, lng, resolved.type, resolved.category, name, tagsJson,
      );
      insertRtree.run(rowId, lat, lat, lng, lng);
    }
  });

  const batch: GeoJsonFeature[] = [];
  const rl = createInterface({ input: createReadStream(input), crlfDelay: Infinity });
  for await (const line of rl) {
    const trimmed = line.trim();
    if (!trimmed || trimmed === "\x1e") continue; // GeoJSONSeq record separator
    // geojsonseq may prefix each line with the RS (0x1e) control char.
    const json = trimmed.replace(/^\x1e/, "");
    try {
      batch.push(JSON.parse(json));
    } catch {
      skipped++;
    }
    if (batch.length >= 10_000) {
      load(batch.splice(0));
    }
  }
  if (batch.length) load(batch);

  db.exec("CREATE INDEX idx_poi_category ON poi(category);");
  const count = (db.prepare("SELECT COUNT(*) AS n FROM poi").get() as { n: number }).n;
  db.close();
  console.log(
    `loaded ${count} POIs (skipped ${skipped} non-matching/invalid, ` +
      `${deduped} co-located duplicates collapsed)`,
  );
}

main()
  .then(() => {
    // Exit explicitly on success so the process ends before better-sqlite3's native
    // cleanup hook runs. On some Node 24 builds that hook aborts (SIGABRT in
    // ~Statement) during teardown even though all work + db.close() already completed
    // — which would fail the CI step with exit 134. The DB is fully written by here.
    process.exit(0);
  })
  .catch((e) => {
    console.error(e);
    process.exit(1);
  });
