package io.resupply.karoo.extension

import android.content.ComponentName
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ViewConfig
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.ui.field.FieldMessage
import io.resupply.karoo.ui.field.LargeUpcomingField
import io.resupply.karoo.ui.field.SmallUpcomingField

/**
 * "Upcoming POIs" data field. Renders, per enabled category, the next POIs ahead on the
 * route and how far. Size-adaptive:
 *  - large (a slot tall enough for a grid): a 2-column grid of category cards, nearest-first,
 *    paging through extra categories on the rotation tick;
 *  - small: a single category card, auto-rotating through categories so the rider sees each
 *    without interaction (tap-to-cycle isn't reliably available on a Karoo graphical field —
 *    auto-rotate is the robust fallback).
 *
 * All the streaming/progress/message-state plumbing lives in [UpcomingPoisBaseDataType];
 * this class is just the grid/rotate layout.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class UpcomingPoisDataType(
    karooSystem: KarooSystemService,
    repository: ResupplyRepository,
    configStore: ConfigStore,
    extension: String,
) : UpcomingPoisBaseDataType(karooSystem, repository, configStore, extension, TYPE_ID) {

    @androidx.compose.runtime.Composable
    override fun renderResolved(
        r: ResolvedPois,
        config: ViewConfig,
        tick: Long,
        activity: ComponentName,
        interactive: Boolean,
    ) {
        // How many category cards fit on a page is driven by pixel HEIGHT, not grid rows: a
        // short-but-wide slot lies about how many cards fit. capacityFor() turns the height
        // into a card count (2-col grid, as many rows as fit); we lay that out and page
        // through the rest on the rotation tick.
        val capacity = capacityFor(config.viewSize.second)

        // Categories that actually have something ahead, sorted nearest-first (each list is
        // already nearest-first inside upcomingByCategory, so .first() is its nearest). An
        // enabled-but-empty category would just be a blank card, so we drop it.
        val ahead = r.enabled
            .filter { r.upcoming[it]?.isNotEmpty() == true }
            .sortedBy { r.upcoming.getValue(it).first().aheadMeters }

        if (capacity <= 1) {
            // Slot only fits one card: single rotating category. Prefer categories with POIs
            // ahead; fall back to all enabled if none do (so the field still shows *something*).
            val cats = ahead.ifEmpty { r.enabled.toList() }
            val cat = cats[(tick % cats.size).toInt()]
            return SmallUpcomingField(rowFor(cat, r.upcoming[cat].orEmpty(), large = false), activity, interactive)
        }

        if (ahead.isEmpty()) return FieldMessage("No POIs ahead", activity, interactive)

        // Fill the slot: a page is `capacity` cards (a 2-col grid, as many rows as the height
        // fits — computed in capacityFor so cards never clip). If there are FEWER categories
        // ahead than capacity, show just those (no empty rows). More categories than capacity
        // → page through them on the rotation tick.
        val pageSize = capacity.coerceAtMost(ahead.size)
        val pageCount = (ahead.size + pageSize - 1) / pageSize
        // With ≤ one page the tick is irrelevant, so the field stays static (no motion).
        val pageIndex = if (pageCount > 1) (tick % pageCount).toInt() else 0
        val page = ahead.drop(pageIndex * pageSize).take(pageSize)

        val rows = page.map { cat -> rowFor(cat, r.upcoming.getValue(cat), large = true) }
        LargeUpcomingField(rows, activity, interactive)
    }

    companion object {
        const val TYPE_ID = "upcoming-pois"

        // Grid geometry. A 3-line card (label / big distance / follow-ups) needs ~175px per
        // grid ROW to render all three lines WITHOUT clipping — measured on-device: at 152px/row
        // (456px ÷ 3) the follow-up line was chopped. A grid cell needs more than a standalone
        // 1/8 slot (which fits 3 lines in ~148px) because it also carries the grid's outer
        // padding and the profile card's border. Fewer, taller rows beat clipped ones. 2-wide.
        private const val GRID_COLS = 2
        private const val CARD_MIN_HEIGHT_PX = 175
        // Below this height a 2-wide grid is too cramped, so fall back to one rotating card.
        private const val GRID_MIN_HEIGHT_PX = 200
        // Cap grid rows at 3 (→ 6 cards): more rows shrink cards toward clutter, and 6 already
        // covers most of the 8-category set. At 175px/row: 8/8 ≈ 456px → 2 rows; a taller
        // full-screen profile (~525px+) → 3 rows.
        private const val GRID_MAX_ROWS = 3

        /**
         * Max category cards that fit a slot [px] tall: a 2-column grid with as many rows as
         * the height allows (each row needs [CARD_MIN_HEIGHT_PX]), capped at [GRID_MAX_ROWS].
         * Returns 1 when the slot is too short for a grid → the single rotating card is used.
         */
        private fun capacityFor(px: Int): Int {
            if (px < GRID_MIN_HEIGHT_PX) return 1
            val rows = (px / CARD_MIN_HEIGHT_PX).coerceIn(1, GRID_MAX_ROWS)
            return rows * GRID_COLS
        }
    }
}
