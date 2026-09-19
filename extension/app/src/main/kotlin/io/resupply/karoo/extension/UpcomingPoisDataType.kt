package io.resupply.karoo.extension

import android.content.ComponentName
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ViewConfig
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.PoiSource
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

        // Categories that actually have something to show, sorted nearest-first (each list is
        // already nearest-first inside upcomingByCategory, so .first() is its nearest). An
        // enabled-but-empty category would just be a blank card, so we drop it.
        val ahead = r.enabled
            .filter { r.upcoming[it]?.isNotEmpty() == true }
            .sortedBy { r.upcoming.getValue(it).first().aheadMeters }

        // Nothing to show → a message, not a card. Do this BEFORE the single-card path so a
        // small slot doesn't cycle through empty categories rendering a bare "–" hero (which
        // reads as broken). Copy depends on the build: route "ahead" vs nearby "around here".
        if (ahead.isEmpty()) {
            val emptyText = if (r.source == PoiSource.NEARBY) "No places nearby" else "No POIs ahead"
            return FieldMessage(emptyText, activity, interactive)
        }

        if (capacity <= 1) {
            // Slot only fits one card: rotate through the categories that HAVE a POI (ahead is
            // non-empty here), so the rider never sees a blank "–" card.
            val cat = ahead[(tick % ahead.size).toInt()]
            return SmallUpcomingField(rowFor(cat, r.upcoming.getValue(cat), large = false), activity, interactive)
        }

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
    }
}
