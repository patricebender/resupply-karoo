#!/usr/bin/env bash
# Build a bundled first-run seed asset from an arbitrary set of regions — the data an
# edition ships in its APK (see app BundledSeed / product flavors).
#
#   ./build-seed-set.sh <output-basename> <regionId> [regionId ...]
#
# e.g.
#   ./build-seed-set.sh pois-usa usa
#   ./build-seed-set.sh pois-core-europe germany france italy belgium netherlands luxembourg
#
# Each regionId is built (or reused) via build-region-file.sh, producing a poi-only,
# R*Tree-stripped dist file. Those are merged (id-offset + osm_id dedup, like Germany
# Complete), VACUUMed, and the resulting uncompressed .sqlite is written to the matching
# Gradle flavor's asset dir (app/src/<flavor>/assets/<output-basename>.sqlite), where:
#   pois-usa         -> usa flavor
#   pois-core-europe -> coreEurope flavor
# The app rebuilds the R*Tree on first-run seed, so the asset stays poi-only (small).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_SRC="$(cd "$HERE/../.." && pwd)/app/src"
DIST="${DIST_DIR:-$HERE/dist}"
WORK="${WORK_DIR:-$HERE/work}"
mkdir -p "$WORK"

BASENAME="${1:-}"
shift || true
if [[ -z "$BASENAME" || $# -eq 0 ]]; then
  echo "usage: build-seed-set.sh <output-basename> <regionId> [regionId ...]" >&2
  exit 1
fi
IDS=("$@")

# Map the output basename to its Gradle flavor asset dir.
case "$BASENAME" in
  pois-usa) FLAVOR="usa" ;;
  pois-core-europe) FLAVOR="coreEurope" ;;
  *) echo "!! unknown seed basename '$BASENAME' (expected pois-usa | pois-core-europe)" >&2; exit 1 ;;
esac
ASSET_DIR="$APP_SRC/$FLAVOR/assets"
mkdir -p "$ASSET_DIR"

# Build each region's dist file (reuses cached per-extract DBs) and collect the gunzipped
# poi-only SQLites.
built=()
for id in "${IDS[@]}"; do
  echo "==> Region file: $id"
  "$HERE/build-region-file.sh" "$id"
  gz="$(ls -t "$DIST/$id"-v*.sqlite.gz | head -1)"
  if [[ -z "$gz" || ! -f "$gz" ]]; then
    echo "!! no dist/$id-v*.sqlite.gz produced" >&2
    exit 1
  fi
  raw="$WORK/seed-src-$id.sqlite"
  gunzip -c "$gz" > "$raw"
  built+=("$raw")
done

# Merge the poi-only DBs: copy the first, append the rest with an id offset, then dedup
# cross-region boundary osm_ids (keep lowest id). No poi_rtree here — the dist files are
# stripped and the app rebuilds it on seed. VACUUM clears user_version, so restore it.
OUT="$WORK/$BASENAME.merged.sqlite"
rm -f "$OUT"
cp "${built[0]}" "$OUT"
VERSION="$(sqlite3 "$OUT" 'PRAGMA user_version;')"
echo "==> Merging ${#built[@]} region DB(s) (schema v$VERSION)"

for db in "${built[@]:1}"; do
  offset="$(sqlite3 "$OUT" 'SELECT COALESCE(MAX(id),0) FROM poi;')"
  echo "   + $(basename "$db") (id offset $offset)"
  sqlite3 "$OUT" <<SQL
ATTACH '$db' AS r;
BEGIN;
INSERT INTO poi (id, osm_id, lat, lng, type, category, name, tags, region_id)
  SELECT id + $offset, osm_id, lat, lng, type, category, name, tags, region_id FROM r.poi;
COMMIT;
DETACH r;
SQL
done

if [[ ${#built[@]} -gt 1 ]]; then
  echo "   de-duplicating boundary osm_ids"
  sqlite3 "$OUT" <<'SQL'
DELETE FROM poi WHERE id NOT IN (SELECT MIN(id) FROM poi GROUP BY osm_id);
SQL
fi

sqlite3 "$OUT" "VACUUM; PRAGMA user_version=$VERSION;"

POIS="$(sqlite3 "$OUT" 'SELECT COUNT(*) FROM poi;')"
INTEGRITY="$(sqlite3 "$OUT" 'PRAGMA integrity_check;')"
if [[ "$INTEGRITY" != "ok" ]]; then
  echo "!! integrity_check failed: $INTEGRITY" >&2
  exit 1
fi
if [[ -z "$VERSION" || "$VERSION" == "0" ]]; then
  echo "!! merged seed has user_version=$VERSION; expected the pipeline's schema version" >&2
  exit 1
fi

DEST="$ASSET_DIR/$BASENAME.sqlite"
cp "$OUT" "$DEST"
echo "==> Seed asset: $DEST"
echo "   flavor=$FLAVOR poiCount=$POIS schema=$VERSION bytes=$(wc -c < "$DEST" | tr -d ' ')"
