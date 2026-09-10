# Resupply brand assets

The complete set of Resupply icon/wordmark SVGs, kept under version control so the artwork
has a source of record. C2PA metadata has been stripped from every file.

**These are reference/source only** — they are not read at build time. The assets the app
actually ships are the VectorDrawables under `../src/main/res/drawable/` and the store icon
that CI rasterizes from `../resupply-icon.svg`.

## Source-of-truth files (edit these, then re-derive the drawables)

Two live next to the app (one dir up), because build tooling / CI references them:

- `../resupply-icon.svg` → the squircle; CI rasterizes it to `resupply.png` (library store
  icon) and it's the source for `res/drawable/ic_resupply.xml`.
- `../resupply-adaptive-foreground.svg` / `../resupply-adaptive-background.svg` → sources for
  `res/drawable/ic_launcher_foreground.xml` / `ic_launcher_background.xml`.

Copies of those three also live here so the full set is in one place.

## Variants (this folder, reference only)

| File | Use |
|---|---|
| `resupply-icon-squircle.svg` | Primary app/extension icon (rounded-square mask). |
| `resupply-icon-round.svg` | Circular mask variant. |
| `resupply-icon-square.svg` | Hard-square / full-bleed variant. |
| `resupply-icon-small.svg` | Simplified mark for very small sizes (thicker strokes, fewer dots). |
| `resupply-icon-mono-dark.svg` | Monochrome on dark. |
| `resupply-icon-mono-light.svg` | Monochrome on light. |
| `resupply-wordmark.svg` | Mark + "RESUPPLY" lockup (dark text; for light backgrounds — the app header renders the wordmark as themed Compose text instead). |
| `resupply-adaptive-foreground.svg` | Android adaptive-icon foreground (R + route + dots, no tile). |
| `resupply-adaptive-background.svg` | Android adaptive-icon background (dark base + contours). |

## Rasters (`png/`)

`png/resupply-icon.png` is a 256×256 raster of the squircle, used by the top-level
`README.md` header (GitHub's markdown sanitizer can strip SVG `<clipPath>`, so a PNG renders
reliably). Regenerate it from the SVG if the artwork changes.

## Palette

- Tile / casing / outline: `#0F1A18`
- R letterform: `#F2EDE3` (cream)
- Route: `#E8873B` (orange)
- Waypoint dots: `#5FC7E3` (cyan) · `#F2C14E` (yellow) · `#C79BE8` (purple)
- Faint contour lines: `#2A3A36`
