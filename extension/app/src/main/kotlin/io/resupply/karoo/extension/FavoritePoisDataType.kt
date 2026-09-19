package io.resupply.karoo.extension

import android.content.ComponentName
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ViewConfig
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.PoiSource
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.ui.field.FavoritesEmptyField
import io.resupply.karoo.ui.field.FieldMessage
import io.resupply.karoo.ui.field.LargeUpcomingField
import io.resupply.karoo.ui.field.SmallUpcomingField

/**
 * The "Favorites" data field: the rider's starred POIs ahead on the route, across all
 * categories. It's the same grid/rotate layout as the all-categories [UpcomingPoisDataType],
 * but the pool is narrowed to favorites ([favoritesField] = true makes the base select via
 * `favoritesUpcoming`).
 *
 * Favorites are a route concept (cleared on every build), so on a route with nothing starred it
 * shows an explicit "star places" prompt rather than a bare "–" card. With no route loaded it
 * falls back to nearby POIs — same as the all-POIs field — so it stays useful in live mode (this
 * fallback is noted in the field's profile description).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class FavoritePoisDataType(
    karooSystem: KarooSystemService,
    repository: ResupplyRepository,
    configStore: ConfigStore,
    extension: String,
) : UpcomingPoisBaseDataType(karooSystem, repository, configStore, extension, TYPE_ID) {

    override val favoritesField = true

    @androidx.compose.runtime.Composable
    override fun renderResolved(
        r: ResolvedPois,
        config: ViewConfig,
        tick: Long,
        activity: ComponentName,
        interactive: Boolean,
    ) {
        // Categories with something to show, nearest-first (each bucket is already nearest-first).
        // On a route these are the starred POIs; with no route this is the nearby fallback set
        // (see UpcomingPoisBaseDataType), so the field stays useful in live mode.
        val ahead = r.enabled
            .filter { r.upcoming[it]?.isNotEmpty() == true }
            .sortedBy { r.upcoming.getValue(it).first().aheadMeters }

        if (ahead.isEmpty()) {
            return if (r.source == PoiSource.NEARBY) {
                // Live mode, nothing around → same plain note as the all-POIs field.
                FieldMessage("No places nearby", activity, interactive)
            } else {
                // On a route but nothing starred → a prettier prompt (star glyph + copy) inviting
                // the rider to build their shortlist.
                FavoritesEmptyField(activity, interactive)
            }
        }

        // Same slot geometry as the all-categories field: one rotating card on a small slot,
        // a paged 2-column grid on a taller one.
        val capacity = capacityFor(config.viewSize.second)
        if (capacity <= 1) {
            val cat = ahead[(tick % ahead.size).toInt()]
            return SmallUpcomingField(rowFor(cat, r.upcoming.getValue(cat), large = false), activity, interactive)
        }

        val pageSize = capacity.coerceAtMost(ahead.size)
        val pageCount = (ahead.size + pageSize - 1) / pageSize
        val pageIndex = if (pageCount > 1) (tick % pageCount).toInt() else 0
        val page = ahead.drop(pageIndex * pageSize).take(pageSize)

        val rows = page.map { cat -> rowFor(cat, r.upcoming.getValue(cat), large = true) }
        LargeUpcomingField(rows, activity, interactive)
    }

    companion object {
        const val TYPE_ID = "favorite-pois"
    }
}
