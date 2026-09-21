# Feature ideas

A running list of features that would add rider value, ordered roughly by impact given the
app's offline / no-signal premise. Not a commitment — a place to capture and refine ideas
before they become work.

## In progress

### 1. "Open on arrival" — ETA + time-aware opening status ⏳
Compute each POI's estimated arrival time from the rider's along-route distance and average
speed, then judge its opening hours *against that arrival time* rather than "now". Surface it
in the Waybook list:
- **ETA** shown per row.
- **Green** — reached within opening hours.
- **Yellow** — a close call (arriving near open/close, within a margin).
- **Gray** — no hours available to judge.

Reuses `OpeningHours` + `distancesAlongRoute`; needs a live speed stream. Fully offline for OSM
hours; the Google fallback fills gaps. Turns "here are POIs" into "here are the stops that will
actually help you". *(implementing now)*

## Backlog — high value

### 2. Resupply-gap warnings
Scan the corridor ahead and flag long stretches with no water or no food ("No water for the
next 48 km") as a data-field alert or map annotation. Safety-relevant for bikepacking/gravel;
exactly what an offline roadbook should surface.

### 3. Resupply-interval planning
Let the rider set a target interval (every 30 km / every 2 h) and greedily suggest a chain of
stops that keeps them within it at minimal detour. Turns the POI cloud into an actual plan.

## Backlog — secondary

### 4. Elevation-aware detour cost
Weight detour cost by the climb back to the route, not just cross-track distance — a downhill
detour that forces a climb back is far worse than a flat one.

### 5. Richer offline OSM tags
Surface high-signal tags already in the data: `payment:cash`/`payment:cards` (cash-only fuel),
`toilets=yes` on a café, `drinking_water=yes` on non-water POIs, `wheelchair`, `self_service`.

### 6. Reachability vs. daylight/battery
Tie ETA (#1) to sunset: "Bike shop closes before sunset, 12 km ahead" in the ride-view field.

## Backlog — polish

- Note / checked-off state on favorites ("filled bottles here").
- Export the whole roadbook to the phone (per-POI QR exists; a full summary / GPX waypoints
  would let riders pre-plan).
- More categories: pharmacies, ATMs, campsites/shelters, water-refill stations, viewpoints.
