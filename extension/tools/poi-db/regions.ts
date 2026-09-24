// Region catalog — the single source of truth for downloadable POI regions.
//
// Maps a stable `regionId` (used in filenames, the manifest, and the app's persisted
// selection) to its Geofabrik extract path(s) plus display metadata. Consumed by:
//   - build-region-file.sh (via `npx tsx regions.ts <id>` helpers) to resolve which
//     Geofabrik extract(s) to build,
//   - build-manifest.ts to emit dist/manifest.json,
//   - the app, as a generated assets/regions.json (label + group only), so the picker
//     renders without a network call.
//
// `geofabrik` is a path under https://download.geofabrik.de/ minus the `-latest.osm.pbf`
// suffix (e.g. "europe/germany/hessen"). A string[] means the region is built by merging
// several extracts (Germany Complete = all 16 Bundesländer), reusing the merge/dedup
// logic shared with build-multi-region.sh.
//
// `group` drives the picker's top-level sections ("Europe", "North America"). Within a
// group the app builds a country tree: a country whose id is a prefix of other ids (e.g.
// `germany` → `germany-hessen`, `usa` → `usa-california`) is an expandable parent whose
// `<id>-*` entries nest under it; other countries are whole-country leaves. The nesting is
// derived from the ids, not hardcoded per country.

export interface Region {
  /** Stable id: `germany`, `germany-bayern`, `usa`, `usa-texas`, … Also the filename stem. */
  id: string;
  /** Display name shown in the picker. */
  label: string;
  /** Picker section. */
  group: "Europe" | "North America";
  /** Geofabrik path(s) under download.geofabrik.de, minus `-latest.osm.pbf`. */
  geofabrik: string | string[];
  /**
   * Monotonic data version, bumped by hand each time this region is rebuilt with fresher
   * OSM data. The app compares the manifest's number against the installed one to offer an
   * "update available" affordance. Orthogonal to `schemaVersion` (which gates schema/tag
   * changes). Defaults to 1 for regions not in [DATA_VERSIONS]; bump there when refreshing.
   */
  dataVersion: number;
}

/**
 * Per-region data-version overrides. A region absent here is version 1. When you rebuild a
 * region with fresh OSM data, bump its entry (add it at 2, then 3, …) so installed riders
 * see the update. Keyed by [Region.id].
 */
const DATA_VERSIONS: Record<string, number> = {
  // e.g. germany: 2,
};

/** The 16 German federal states, in the picker's display order (alphabetical by label). */
const GERMANY_STATES: Array<{ id: string; label: string; slug: string }> = [
  { id: "baden-wuerttemberg", label: "Baden-Württemberg", slug: "baden-wuerttemberg" },
  { id: "bayern", label: "Bayern", slug: "bayern" },
  { id: "berlin", label: "Berlin", slug: "berlin" },
  { id: "brandenburg", label: "Brandenburg", slug: "brandenburg" },
  { id: "bremen", label: "Bremen", slug: "bremen" },
  { id: "hamburg", label: "Hamburg", slug: "hamburg" },
  { id: "hessen", label: "Hessen", slug: "hessen" },
  { id: "mecklenburg-vorpommern", label: "Mecklenburg-Vorpommern", slug: "mecklenburg-vorpommern" },
  { id: "niedersachsen", label: "Niedersachsen", slug: "niedersachsen" },
  { id: "nordrhein-westfalen", label: "Nordrhein-Westfalen", slug: "nordrhein-westfalen" },
  { id: "rheinland-pfalz", label: "Rheinland-Pfalz", slug: "rheinland-pfalz" },
  { id: "saarland", label: "Saarland", slug: "saarland" },
  { id: "sachsen", label: "Sachsen", slug: "sachsen" },
  { id: "sachsen-anhalt", label: "Sachsen-Anhalt", slug: "sachsen-anhalt" },
  { id: "schleswig-holstein", label: "Schleswig-Holstein", slug: "schleswig-holstein" },
  { id: "thueringen", label: "Thüringen", slug: "thueringen" },
];

/**
 * Whole-country regions (no sub-region breakdown), covering Europe so a
 * cross-continental route (e.g. the Transcontinental Race) is resuppliable country by
 * country. Alphabetical by label. `geofabrik` is a Geofabrik path minus `-latest.osm.pbf`;
 * a string[] merges several extracts (the United Kingdom has no single whole-UK extract, so
 * it's assembled from England/Scotland/Wales — Northern Ireland comes with Ireland).
 *
 * Deliberately excluded: Russia, Turkey, Georgia, Belarus, Azores, Faroe Islands — out of
 * practical TCR scope and/or oversized for the on-device (Range-chunked) download path.
 */
const COUNTRIES: Array<{ id: string; label: string; geofabrik: string | string[] }> = [
  { id: "albania", label: "Albania", geofabrik: "europe/albania" },
  { id: "andorra", label: "Andorra", geofabrik: "europe/andorra" },
  { id: "austria", label: "Austria", geofabrik: "europe/austria" },
  { id: "belgium", label: "Belgium", geofabrik: "europe/belgium" },
  { id: "bosnia-herzegovina", label: "Bosnia and Herzegovina", geofabrik: "europe/bosnia-herzegovina" },
  { id: "bulgaria", label: "Bulgaria", geofabrik: "europe/bulgaria" },
  { id: "croatia", label: "Croatia", geofabrik: "europe/croatia" },
  { id: "cyprus", label: "Cyprus", geofabrik: "europe/cyprus" },
  { id: "czech-republic", label: "Czech Republic", geofabrik: "europe/czech-republic" },
  { id: "denmark", label: "Denmark", geofabrik: "europe/denmark" },
  { id: "estonia", label: "Estonia", geofabrik: "europe/estonia" },
  { id: "finland", label: "Finland", geofabrik: "europe/finland" },
  { id: "france", label: "France", geofabrik: "europe/france" },
  { id: "greece", label: "Greece", geofabrik: "europe/greece" },
  { id: "hungary", label: "Hungary", geofabrik: "europe/hungary" },
  { id: "iceland", label: "Iceland", geofabrik: "europe/iceland" },
  { id: "ireland", label: "Ireland", geofabrik: "europe/ireland-and-northern-ireland" },
  { id: "italy", label: "Italy", geofabrik: "europe/italy" },
  { id: "kosovo", label: "Kosovo", geofabrik: "europe/kosovo" },
  { id: "latvia", label: "Latvia", geofabrik: "europe/latvia" },
  { id: "liechtenstein", label: "Liechtenstein", geofabrik: "europe/liechtenstein" },
  { id: "lithuania", label: "Lithuania", geofabrik: "europe/lithuania" },
  { id: "luxembourg", label: "Luxembourg", geofabrik: "europe/luxembourg" },
  { id: "malta", label: "Malta", geofabrik: "europe/malta" },
  { id: "moldova", label: "Moldova", geofabrik: "europe/moldova" },
  { id: "monaco", label: "Monaco", geofabrik: "europe/monaco" },
  { id: "montenegro", label: "Montenegro", geofabrik: "europe/montenegro" },
  { id: "netherlands", label: "Netherlands", geofabrik: "europe/netherlands" },
  { id: "north-macedonia", label: "North Macedonia", geofabrik: "europe/macedonia" },
  { id: "norway", label: "Norway", geofabrik: "europe/norway" },
  { id: "poland", label: "Poland", geofabrik: "europe/poland" },
  { id: "portugal", label: "Portugal", geofabrik: "europe/portugal" },
  { id: "romania", label: "Romania", geofabrik: "europe/romania" },
  { id: "serbia", label: "Serbia", geofabrik: "europe/serbia" },
  { id: "slovakia", label: "Slovakia", geofabrik: "europe/slovakia" },
  { id: "slovenia", label: "Slovenia", geofabrik: "europe/slovenia" },
  { id: "spain", label: "Spain", geofabrik: "europe/spain" },
  { id: "sweden", label: "Sweden", geofabrik: "europe/sweden" },
  { id: "switzerland", label: "Switzerland", geofabrik: "europe/switzerland" },
  { id: "ukraine", label: "Ukraine", geofabrik: "europe/ukraine" },
  {
    id: "united-kingdom",
    label: "United Kingdom",
    geofabrik: [
      "europe/united-kingdom/england",
      "europe/united-kingdom/scotland",
      "europe/united-kingdom/wales",
    ],
  },
];

// US states + DC (mainland set; territories like Puerto Rico / US Virgin Islands are
// omitted). Alphabetical by label; slug == id (Geofabrik's own path segment).
const US_STATES: Array<{ id: string; label: string; slug: string }> = [
  "alabama", "alaska", "arizona", "arkansas", "california", "colorado", "connecticut",
  "delaware", "district-of-columbia", "florida", "georgia", "hawaii", "idaho", "illinois",
  "indiana", "iowa", "kansas", "kentucky", "louisiana", "maine", "maryland", "massachusetts",
  "michigan", "minnesota", "mississippi", "missouri", "montana", "nebraska", "nevada",
  "new-hampshire", "new-jersey", "new-mexico", "new-york", "north-carolina", "north-dakota",
  "ohio", "oklahoma", "oregon", "pennsylvania", "rhode-island", "south-carolina",
  "south-dakota", "tennessee", "texas", "utah", "vermont", "virginia", "washington",
  "west-virginia", "wisconsin", "wyoming",
].map((slug) => ({
  id: slug,
  slug,
  // Title-case the slug for display: "new-york" → "New York". Keep small joining words
  // lowercase ("district-of-columbia" → "District of Columbia").
  label: slug
    .split("-")
    .map((w, i) =>
      i > 0 && (w === "of" || w === "and")
        ? w
        : w[0]!.toUpperCase() + w.slice(1),
    )
    .join(" "),
}));

// Canada's 10 provinces + 3 territories. Alphabetical by label; slug == id.
const CANADA_PROVINCES: Array<{ id: string; label: string; slug: string }> = [
  { id: "alberta", label: "Alberta", slug: "alberta" },
  { id: "british-columbia", label: "British Columbia", slug: "british-columbia" },
  { id: "manitoba", label: "Manitoba", slug: "manitoba" },
  { id: "new-brunswick", label: "New Brunswick", slug: "new-brunswick" },
  { id: "newfoundland-and-labrador", label: "Newfoundland and Labrador", slug: "newfoundland-and-labrador" },
  { id: "northwest-territories", label: "Northwest Territories", slug: "northwest-territories" },
  { id: "nova-scotia", label: "Nova Scotia", slug: "nova-scotia" },
  { id: "nunavut", label: "Nunavut", slug: "nunavut" },
  { id: "ontario", label: "Ontario", slug: "ontario" },
  { id: "prince-edward-island", label: "Prince Edward Island", slug: "prince-edward-island" },
  { id: "quebec", label: "Quebec", slug: "quebec" },
  { id: "saskatchewan", label: "Saskatchewan", slug: "saskatchewan" },
  { id: "yukon", label: "Yukon", slug: "yukon" },
];

// Raw catalog without data versions; [REGIONS] stamps each from [DATA_VERSIONS] below so
// the version knob stays in one place instead of being sprinkled through the templates.
const RAW_REGIONS: Array<Omit<Region, "dataVersion">> = [
  // Germany — whole country, merged from all 16 Bundesland extracts. Shown in the picker
  // as the expandable "Germany" node (getting it installs the whole country); the states
  // below are the finer-grained alternatives.
  {
    id: "germany",
    label: "Germany",
    group: "Europe",
    geofabrik: GERMANY_STATES.map((s) => `europe/germany/${s.slug}`),
  },
  // One entry per Bundesland.
  ...GERMANY_STATES.map((s) => ({
    id: `germany-${s.id}`,
    label: s.label,
    group: "Europe" as const,
    geofabrik: `europe/germany/${s.slug}`,
  })),
  // Whole countries.
  ...COUNTRIES.map((c) => ({
    id: c.id,
    label: c.label,
    group: "Europe" as const,
    geofabrik: c.geofabrik,
  })),

  // USA — whole country merged from all 51 state/DC extracts; the expandable "USA" node,
  // with per-state downloads nested under it (same shape as Germany). "Complete" is a very
  // large on-device download; states are the practical unit.
  {
    id: "usa",
    label: "USA",
    group: "North America",
    geofabrik: US_STATES.map((s) => `north-america/us/${s.slug}`),
  },
  ...US_STATES.map((s) => ({
    id: `usa-${s.id}`,
    label: s.label,
    group: "North America" as const,
    geofabrik: `north-america/us/${s.slug}`,
  })),

  // Canada — same pattern, merged from all province/territory extracts.
  {
    id: "canada",
    label: "Canada",
    group: "North America",
    geofabrik: CANADA_PROVINCES.map((p) => `north-america/canada/${p.slug}`),
  },
  ...CANADA_PROVINCES.map((p) => ({
    id: `canada-${p.id}`,
    label: p.label,
    group: "North America" as const,
    geofabrik: `north-america/canada/${p.slug}`,
  })),
];

export const REGIONS: Region[] = RAW_REGIONS.map((r) => ({
  ...r,
  dataVersion: DATA_VERSIONS[r.id] ?? 1,
}));

/** Look up a region by id, or throw a helpful error listing valid ids. */
export function regionById(id: string): Region {
  const r = REGIONS.find((x) => x.id === id);
  if (!r) {
    throw new Error(
      `unknown regionId "${id}". Valid ids: ${REGIONS.map((x) => x.id).join(", ")}`,
    );
  }
  return r;
}

/** Resolve a region's Geofabrik path(s) as an array (single-path regions → length 1). */
export function geofabrikPaths(r: Region): string[] {
  return Array.isArray(r.geofabrik) ? r.geofabrik : [r.geofabrik];
}

// Composite vs leaf, for the parallel build. A COMPOSITE is a whole-country node whose
// extracts are ALSO published as their own regions (germany→germany-*, usa→usa-*,
// canada→canada-*): it can be assembled by merging those already-built member files instead
// of re-downloading the extracts. A LEAF is everything else — every single-extract region,
// plus multi-extract regions whose parts are NOT separate regions (e.g. the United Kingdom,
// merged from england/scotland/wales which aren't standalone ids). Leaves each download +
// build independently; composites are merged from leaves after.
//
// Derived, not hardcoded: a region is a composite iff every one of its extract paths is the
// sole extract of some OTHER region. That map from extract-path → owning leaf id also tells
// the merge job which leaf files to combine.

/** Extract path → the leaf region id whose single extract it is. */
function singleExtractOwners(): Map<string, string> {
  const owners = new Map<string, string>();
  for (const r of REGIONS) {
    const paths = geofabrikPaths(r);
    if (paths.length === 1) owners.set(paths[0]!, r.id);
  }
  return owners;
}

/** True if [r] is a composite: multi-extract, and every extract is some leaf's sole extract. */
export function isComposite(r: Region): boolean {
  const paths = geofabrikPaths(r);
  if (paths.length < 2) return false;
  const owners = singleExtractOwners();
  return paths.every((p) => owners.has(p));
}

/** The leaf region ids a composite is assembled from, in catalog order. */
export function memberLeafIds(r: Region): string[] {
  const owners = singleExtractOwners();
  return geofabrikPaths(r).map((p) => {
    const id = owners.get(p);
    if (!id) throw new Error(`region "${r.id}" is not a composite: extract ${p} has no leaf`);
    return id;
  });
}

export const LEAF_REGIONS = REGIONS.filter((r) => !isComposite(r));
export const COMPOSITE_REGIONS = REGIONS.filter(isComposite);

// CLI: `npx tsx regions.ts <command> [id]`
//   ids                 → print all region ids, one per line
//   paths <id>          → print the region's Geofabrik path(s), space-separated
//   app-json            → print the app-facing regions.json ({id,label,group}[])
//   leaves              → leaf region ids (built independently, in parallel), one per line
//   composites          → composite region ids (merged from leaves), one per line
//   members <id>        → the leaf ids a composite is assembled from, space-separated
//   shards <n>          → JSON [[id,…],…]: leaf ids split into <n> round-robin shards, for
//                         the build matrix (round-robin so a big region doesn't stack a shard)
// Kept tiny so the bash builder can shell out for exactly what it needs.
if (import.meta.url === `file://${process.argv[1]}`) {
  const [cmd, arg] = process.argv.slice(2);
  switch (cmd) {
    case "ids":
      console.log(REGIONS.map((r) => r.id).join("\n"));
      break;
    case "paths":
      if (!arg) throw new Error("usage: regions.ts paths <id>");
      console.log(geofabrikPaths(regionById(arg)).join(" "));
      break;
    case "app-json":
      console.log(
        JSON.stringify(
          REGIONS.map(({ id, label, group }) => ({ id, label, group })),
          null,
          2,
        ),
      );
      break;
    case "leaves":
      console.log(LEAF_REGIONS.map((r) => r.id).join("\n"));
      break;
    case "composites":
      console.log(COMPOSITE_REGIONS.map((r) => r.id).join("\n"));
      break;
    case "members":
      if (!arg) throw new Error("usage: regions.ts members <compositeId>");
      console.log(memberLeafIds(regionById(arg)).join(" "));
      break;
    case "shards": {
      const n = Number(arg);
      if (!Number.isInteger(n) || n < 1) throw new Error("usage: regions.ts shards <n>");
      const shards: string[][] = Array.from({ length: n }, () => []);
      LEAF_REGIONS.forEach((r, i) => shards[i % n]!.push(r.id));
      console.log(JSON.stringify(shards.filter((s) => s.length > 0)));
      break;
    }
    default:
      throw new Error(
        "usage: regions.ts <ids|paths <id>|app-json|leaves|composites|members <id>|shards <n>>",
      );
  }
}
