#!/usr/bin/env bash
# Assemble a COMPOSITE region file (Germany / USA / Canada Complete) by MERGING its already-built
# member leaf files — no raw OSM download. The parallel region build (regions.yml) builds every
# leaf on its own runner; this then combines the relevant leaves into the whole-country file.
#
#   ./build-composite.sh germany     # merges dist/germany-<state>-v<schema>.sqlite.gz -> germany
#
# Prerequisite: every member leaf's distributable file already exists in dist/ (built by
# build-region-file.sh). Output: dist/<compositeId>-v<schema>.sqlite.gz (+ echoed count/sizes/sha
# for the manifest builder), byte-for-byte the same shape build-region-file.sh emits for a
# composite — SEMANTICALLY equal to a from-scratch `build-region-file.sh <compositeId>` (same POI
# set; dedup keys on osm_id, so which physical copy of a boundary duplicate survives is irrelevant).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=merge-lib.sh
source "$HERE/merge-lib.sh"

REGION_ID="${1:-}"
if [[ -z "$REGION_ID" ]]; then
  echo "usage: build-composite.sh <compositeId>   (see: npx tsx regions.ts composites)" >&2
  exit 1
fi

WORK="${WORK_DIR:-$HERE/work}"
DIST="${DIST_DIR:-$HERE/dist}"
mkdir -p "$WORK" "$DIST"

# Resolve the member leaf ids (throws if REGION_ID isn't a composite).
members="$(npx --yes tsx "$HERE/regions.ts" members "$REGION_ID")"
read -ra MEMBER_ARR <<< "$members"
echo ">> Composite $REGION_ID <- ${#MEMBER_ARR[@]} leaves: $members"

# Gunzip each member's distributable file. Its schema version is in the filename
# (<id>-v<schema>.sqlite.gz); all members must share one schema (same pipeline).
gunzipped=()
schema=""
for id in "${MEMBER_ARR[@]}"; do
  gz="$(ls "$DIST/$id"-v*.sqlite.gz 2>/dev/null | head -n1 || true)"
  if [[ -z "$gz" || ! -s "$gz" ]]; then
    echo "!! missing member file for '$id' in $DIST (expected $id-v<schema>.sqlite.gz)" >&2
    echo "   build the leaves first (build-region-file.sh) so their dist files exist." >&2
    exit 1
  fi
  v="$(basename "$gz" | sed -E 's/.*-v([0-9]+)\.sqlite\.gz/\1/')"
  if [[ -z "$schema" ]]; then
    schema="$v"
  elif [[ "$schema" != "$v" ]]; then
    echo "!! member '$id' is schema v$v but others are v$schema — rebuild all at one schema" >&2
    exit 1
  fi
  db="$WORK/composite-src-$id.sqlite"
  gunzip -c "$gz" > "$db"
  gunzipped+=("$db")
done

# Merge the member DBs (they're R*Tree-stripped; merge_dbs handles that and dedups boundary
# osm_ids across states exactly as a from-scratch composite build would).
ASSEMBLED="$WORK/$REGION_ID.assembled.sqlite"
rm -f "$ASSEMBLED"
echo "==> Assembling $REGION_ID from ${#gunzipped[@]} member DBs"
merge_dbs "$ASSEMBLED" "${gunzipped[@]}"

SCHEMA="$(sqlite3 "$ASSEMBLED" 'PRAGMA user_version;')"
if [[ -z "$SCHEMA" || "$SCHEMA" == "0" ]]; then
  echo "!! assembled DB has user_version=$SCHEMA; expected the pipeline's schema version" >&2
  exit 1
fi

# Re-stamp every row with the COMPOSITE id (members carried their own leaf ids, e.g.
# germany-bayern) so the on-device provenance is the whole-country region. The member files are
# already R*Tree/index-stripped, so nothing to strip; VACUUM to compact the merged result.
DISTDB="$WORK/$REGION_ID.dist.sqlite"
cp "$ASSEMBLED" "$DISTDB"
echo "==> Stamping region_id=$REGION_ID, VACUUM (schema v$SCHEMA)"
sqlite3 "$DISTDB" <<SQL
UPDATE poi SET region_id='$REGION_ID';
DROP TABLE IF EXISTS poi_rtree;
DROP INDEX IF EXISTS idx_poi_category;
VACUUM;
PRAGMA user_version=$SCHEMA;
SQL

POIS="$(sqlite3 "$DISTDB" 'SELECT COUNT(*) FROM poi;')"
INTEGRITY="$(sqlite3 "$DISTDB" 'PRAGMA integrity_check;')"
if [[ "$INTEGRITY" != "ok" ]]; then
  echo "!! integrity_check failed: $INTEGRITY" >&2
  exit 1
fi

OUT="$DIST/$REGION_ID-v$SCHEMA.sqlite.gz"
gzip -9 -c "$DISTDB" > "$OUT"

RAW="$(wc -c < "$DISTDB" | tr -d ' ')"
GZ="$(wc -c < "$OUT" | tr -d ' ')"
SHA="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"

echo ">> Done: $OUT"
echo "   poiCount=$POIS bytesRaw=$RAW bytesGz=$GZ schema=$SCHEMA"
echo "   sha256=$SHA"
