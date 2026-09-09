package io.resupply.karoo.extension

import android.content.ComponentName
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ViewConfig
import io.resupply.karoo.data.Category
import io.resupply.karoo.data.ConfigStore
import io.resupply.karoo.data.ResupplyRepository
import io.resupply.karoo.ui.field.CategoryDisabledField
import io.resupply.karoo.ui.field.SmallUpcomingField

/**
 * A single-category "upcoming POIs" field, pinned to one [category]. It's the same card the
 * rotating [UpcomingPoisDataType] shows in a small slot, but it never rotates — the rider
 * added *this* field because they want this one amenity in view.
 *
 * Respects the enabled-categories setting: if [category] isn't enabled, the field shows a
 * "Enable <Category>" state (both when nothing is enabled and when other categories are but
 * this one isn't). Streaming, progress, and the no-roadbook/off-route states are inherited
 * from [UpcomingPoisBaseDataType].
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class CategoryPoiDataType(
    private val category: Category,
    karooSystem: KarooSystemService,
    repository: ResupplyRepository,
    configStore: ConfigStore,
    extension: String,
) : UpcomingPoisBaseDataType(karooSystem, repository, configStore, extension, typeIdFor(category)) {

    @androidx.compose.runtime.Composable
    override fun renderNoCategories(activity: ComponentName, interactive: Boolean) =
        CategoryDisabledField(category, activity, interactive)

    @androidx.compose.runtime.Composable
    override fun renderResolved(
        r: ResolvedPois,
        config: ViewConfig,
        tick: Long,
        activity: ComponentName,
        interactive: Boolean,
    ) {
        if (category !in r.enabled) return CategoryDisabledField(category, activity, interactive)
        // Always one card for this category, whatever the slot size. An empty list just draws
        // the card's "–" hero (same as the rotating field's small slot for a category with
        // nothing ahead) — the rider still sees which amenity the field is for.
        SmallUpcomingField(rowFor(category, r.upcoming[category].orEmpty(), large = false), activity, interactive)
    }

    companion object {
        /** Stable per-category type id, e.g. `upcoming-pois-water`. Must match extension_info.xml. */
        fun typeIdFor(category: Category): String = "upcoming-pois-${category.id}"
    }
}
