package io.roadbook.karoo.ui.field

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider as dayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.roadbook.karoo.data.Category

/**
 * One upcoming POI cell in a category row.
 *
 * @param distance display distance ahead, e.g. `1.2km` (nearest) or `4.8` (follow-ups
 *   drop the unit to stay narrow — the DataType formats these).
 * @param arrow prefix marking position: "" nearest, "↑" 2nd, "↑↑" 3rd.
 * @param detour badge like `+200m`, or null when negligible/unknown.
 * @param detourMeters raw detour, drives the color ramp (green→amber→red).
 */
data class PoiCell(
    val distance: String,
    val arrow: String = "",
    val detour: String? = null,
    val detourMeters: Int = 0,
)

/**
 * View-model for one category's row: the category plus up to three upcoming POIs. The
 * DataType builds these off [io.roadbook.karoo.data.upcomingByCategory].
 */
data class CategoryRow(
    val category: Category,
    val name: String?,
    val cells: List<PoiCell>,
)

// The Karoo field background follows the ride theme (light OR dark), so every color
// is day/night adaptive: dark ink on the light theme, light ink on the dark theme.
// (Hardcoding white made the primary distance invisible in light mode.)
private val Primary = dayNightColorProvider(day = Color(0xFF111111), night = Color.White)
private val Dim = dayNightColorProvider(day = Color(0xFF5A5A5A), night = Color(0xFFB8B8B8))

// Detour color ramp: how far off-route you'd deviate. Green = trivial, amber = worth a
// thought, coral = a real detour. Each has a darker day variant so it stays legible on
// the light theme (pale amber/green wash out on white).
private val DetourNear = dayNightColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF66BB6A)) // green
private val DetourMid = dayNightColorProvider(day = Color(0xFFB8860B), night = Color(0xFFFFC107))  // amber
private val DetourFar = dayNightColorProvider(day = Color(0xFFD84315), night = Color(0xFFFF7043))  // coral

private fun detourColor(meters: Int): ColorProvider = when {
    meters <= 250 -> DetourNear
    meters <= 1000 -> DetourMid
    else -> DetourFar
}

/**
 * Full-size field: a 2-column grid of category cards. Each cell is the SAME [CategoryCard]
 * as the single (1/8) field — the grid is literally a set of those cards — so there's one
 * card design, not two. Rows and columns share the field's width and height evenly
 * (`defaultWeight`) so the cards grow to fill the slot. The DataType decides how many cards
 * to pass (capacity ∝ slot height) and sorts them nearest-first; extra categories page
 * through on the rotation tick.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun LargeUpcomingField(rows: List<CategoryRow>, activity: ComponentName, interactive: Boolean) {
    // Chunk into pairs → one Column child per grid row, each a Row of two weighted cells.
    // With ≤8 categories that's ≤4 grid rows, well under the Glance 10-child Column limit
    // (and each inner Row holds exactly 2 children). No dividers — the cards' own padding
    // and the accent bar carry the separation.
    val gridRows = rows.chunked(2)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp)
            .tapToOpen(interactive, openAppIntent(activity)),
    ) {
        gridRows.forEach { pair ->
            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                // Left cell.
                Box(GlanceModifier.defaultWeight().fillMaxHeight()) { CategoryCard(pair[0]) }
                // Right cell, or an empty weighted spacer so a lone left cell stays at
                // half width (grid alignment) rather than stretching across.
                if (pair.size > 1) {
                    Box(GlanceModifier.defaultWeight().fillMaxHeight()) { CategoryCard(pair[1]) }
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/**
 * Small field (e.g. 1/8): a single category card, rotating through categories (the DataType
 * picks which via its tick; tapping opens the app). It's the exact same [CategoryCard] the
 * grid uses — the single field and every grid cell share one design.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun SmallUpcomingField(row: CategoryRow, activity: ComponentName, interactive: Boolean) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .tapToOpen(interactive, openAppIntent(activity)),
    ) {
        CategoryCard(row)
    }
}

/**
 * The one shared category card, used both stand-alone (1/8 field) and as each grid cell.
 * Three horizontally-centered lines:
 *   1: color-coded accent bar + glyph + neutral high-contrast label
 *   2: nearest POI — big bold distance + smaller `km` unit + severity-colored detour
 *   3: 2nd & 3rd POI as a small dim follow-up line (`›`/`»`)
 * Fonts are sized to stay legible at arm's length; the card fills whatever box it's given
 * (the whole 1/8 slot, or one grid cell), so nothing needs a size flag. The hero number is
 * capped at 20sp — the largest that still fits all THREE lines in the shortest slot
 * (1/8 ≈ 148px); 22sp+ clipped the follow-ups line off the bottom on-device. Don't raise it
 * without re-checking the 1/8 slot.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
private fun CategoryCard(row: CategoryRow) {
    val style = styleFor(row.category)
    val nearest = row.cells.getOrNull(0)
    val followUps = row.cells.drop(1).filterNotNull()
    Box(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
        // The three text lines, top-aligned and horizontally centered so each line sits in
        // the middle of the cell. Packed to content so line 3 stays in-bounds at 1/8.
        Column(
            modifier = GlanceModifier.fillMaxWidth().wrapContentHeight(),
            verticalAlignment = Alignment.Vertical.Top,
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            // Line 1: color-coded accent bar + glyph + neutral high-contrast label, centered
            // as a group (no defaultWeight — that would fill the width and defeat centering).
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Box(
                    GlanceModifier
                        .width(5.dp)
                        .height(18.dp)
                        .cornerRadius(2.dp)
                        .background(ColorProvider(style.color)),
                ) {}
                Spacer(GlanceModifier.width(6.dp))
                Text(style.glyph, style = TextStyle(fontSize = 16.sp))
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    style.label,
                    style = TextStyle(color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(2.dp))
            // Line 2: nearest distance, big and bold, with its detour badge.
            HeroDistance(nearest)
            // Line 3: 2nd & 3rd upcoming, small and dim.
            if (followUps.isNotEmpty()) {
                Spacer(GlanceModifier.height(1.dp))
                FollowUps(followUps)
            }
        }
    }
}

/**
 * The card's hero: nearest distance in a large bold number with a smaller-font `km` unit
 * (Glance can't vary font size within one [Text], so number and unit are separate Texts),
 * plus the detour badge in its own severity color. Neutral-colored — a distance is a
 * distance; only the detour carries the green/amber/coral ramp.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
private fun HeroDistance(cell: PoiCell?) {
    val number = cell?.distance?.removeSuffix("km") ?: "–"
    val hasKm = cell?.distance?.endsWith("km") == true
    Row(verticalAlignment = Alignment.Vertical.Bottom) {
        Text(
            number,
            maxLines = 1,
            style = TextStyle(color = Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold),
        )
        if (hasKm) {
            Text(
                "km",
                maxLines = 1,
                style = TextStyle(color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
        cell?.detour?.let {
            Spacer(GlanceModifier.width(5.dp))
            Text(
                it,
                maxLines = 1,
                style = TextStyle(color = detourColor(cell.detourMeters), fontSize = 13.sp),
            )
        }
    }
}

/**
 * The 2nd/3rd upcoming POIs as a small dim group, e.g. `› 3.0  » 5.5`. Visibly secondary
 * to the hero distance; the `km` unit is dropped to stay narrow (the chevrons `›`/`»` mark
 * "further along the route", set by the DataType).
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
private fun FollowUps(cells: List<PoiCell>) {
    val text = cells.joinToString("  ") { "${it.arrow} ${it.distance.removeSuffix("km")}".trim() }
    Text(
        text,
        maxLines = 1,
        style = TextStyle(color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )
}

/**
 * A single centered message (empty / prompt states). Tapping still opens the app — the
 * field is a shortcut into Roadbook regardless of what it's currently showing.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun FieldMessage(text: String, activity: ComponentName, interactive: Boolean) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(6.dp)
            .tapToOpen(interactive, openAppIntent(activity)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
        )
    }
}

/** Intent-extra key: when MainActivity sees this, it kicks off a build on launch. */
const val EXTRA_ACTION = "roadbook.field.action"
const val ACTION_BUILD = "build"

/** Intent that just opens the Roadbook app (no build), used for tap-to-open. */
private fun openAppIntent(activity: ComponentName): Intent =
    Intent()
        .setComponent(activity)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Make the field launch [intent] on tap — but ONLY when [interactive]. In the ride-profile
 * editor the field is rendered with `ViewConfig.preview == true`; if it's clickable there,
 * tapping to select/move/DELETE the field launches Roadbook instead, so the field can't be
 * removed. Gating the clickable on `!preview` makes the field inert in the editor (taps hit
 * the editor's own selection) while staying tap-to-open during an actual ride.
 */
private fun GlanceModifier.tapToOpen(interactive: Boolean, intent: Intent): GlanceModifier =
    if (interactive) this.clickable(actionStartActivity(intent)) else this

/**
 * "Build" prompt (no roadbook yet). Tapping opens the Roadbook app straight into a
 * build via [MainActivity] — no manual navigate-to-extension-then-tap dance. The
 * package/class is passed in so this stays free of a hard app dependency.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun BuildPromptField(activity: ComponentName, interactive: Boolean) {
    val intent = Intent()
        .setComponent(activity)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_ACTION, ACTION_BUILD)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(6.dp)
            .tapToOpen(interactive, intent),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text("🗺", style = TextStyle(fontSize = 22.sp))
        Spacer(GlanceModifier.height(2.dp))
        Text(
            "Tap to build",
            style = TextStyle(color = Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(
            "roadbook",
            style = TextStyle(color = Dim, fontSize = 11.sp),
            maxLines = 1,
        )
    }
}

private val Amber = ColorProvider(Color(0xFFFFB300))

/**
 * Mid-ride deviation state: glyph + amber "Off route" — attention, not an error. Kept
 * visually distinct from the dim prompt states so a glance reads it instantly. Still
 * tappable: off route is exactly when a rider wants to open the app and see what's around.
 */
@androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
@androidx.compose.runtime.Composable
fun OffRouteMessage(activity: ComponentName, interactive: Boolean) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(6.dp)
            .tapToOpen(interactive, openAppIntent(activity)),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text("↝", style = TextStyle(color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Text(
            "Off route",
            style = TextStyle(color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}
