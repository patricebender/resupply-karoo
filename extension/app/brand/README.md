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
| `resupply-wordmark.svg` | Mark + "RESUPPLY" lockup, original 1120-wide artwork (dark text). |
| `resupply-wordmark-light.svg` | Wordmark for **light** backgrounds (dark text), viewBox trimmed to 960. Used by the top-level `README.md` header. |
| `resupply-wordmark-dark.svg` | Wordmark for **dark** backgrounds (cream text). The README `<picture>` swaps to this via `prefers-color-scheme: dark`. |
| `resupply-adaptive-foreground.svg` | Android adaptive-icon foreground (R + route + dots, no tile). |
| `resupply-adaptive-background.svg` | Android adaptive-icon background (dark base + contours). |

The app header does **not** use these wordmark SVGs — it renders "RESUPPLY" as themed
Compose text next to the squircle so it flips with the Karoo light/dark theme.

## Palette

- Tile / casing / outline: `#0F1A18`
- R letterform: `#F2EDE3` (cream)
- Route: `#E8873B` (orange)
- Waypoint dots: `#5FC7E3` (cyan) · `#F2C14E` (yellow) · `#C79BE8` (purple)
- Faint contour lines: `#2A3A36`
