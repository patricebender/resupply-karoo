#!/usr/bin/env bash
# Assert two region SQLite files are SEMANTICALLY equal: same set of POIs, ignoring the internal
# `id` rowid and physical row order (the device rebuilds the rtree/rowids on install anyway).
#
#   ./verify-semantic-equal.sh A.sqlite B.sqlite
#
# Compares the sorted projection of every semantic column. `region_id` is compared too, so a
# composite assembled from leaves must have been re-stamped to the composite id to match a
# from-scratch build. Exits non-zero (with a diff) on any mismatch.
set -euo pipefail

A="${1:?usage: verify-semantic-equal.sh A.sqlite B.sqlite}"
B="${2:?usage: verify-semantic-equal.sh A.sqlite B.sqlite}"

dump() {
  # Order by osm_id so physical/id order can't matter; emit every semantic column.
  sqlite3 "$1" \
    "SELECT osm_id, lat, lng, type, category, IFNULL(name,''), IFNULL(tags,''), region_id
     FROM poi ORDER BY osm_id, lat, lng, type;"
}

da="$(mktemp)"; db="$(mktemp)"
trap 'rm -f "$da" "$db"' EXIT
dump "$A" > "$da"
dump "$B" > "$db"

ca="$(wc -l < "$da" | tr -d ' ')"
cb="$(wc -l < "$db" | tr -d ' ')"
echo "A: $ca POIs   B: $cb POIs"

if diff -q "$da" "$db" >/dev/null; then
  echo "SEMANTICALLY EQUAL ✓"
else
  echo "!! NOT semantically equal — first differences:" >&2
  diff "$da" "$db" | head -20 >&2
  exit 1
fi
