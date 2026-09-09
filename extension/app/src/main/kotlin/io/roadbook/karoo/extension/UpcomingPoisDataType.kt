package io.roadbook.karoo.extension

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.roadbook.karoo.data.Category
import io.roadbook.karoo.data.ConfigStore
import io.roadbook.karoo.data.RoadbookRepository
import io.roadbook.karoo.data.formatDetour
import io.roadbook.karoo.data.formatKm
import io.roadbook.karoo.data.elideName
import io.roadbook.karoo.data.upcomingByCategory
import io.roadbook.karoo.ui.field.BuildPromptField
import io.roadbook.karoo.ui.field.CategoryRow
import io.roadbook.karoo.ui.field.PoiCell
import io.roadbook.karoo.ui.field.FieldMessage
import io.roadbook.karoo.ui.field.LargeUpcomingField
import io.roadbook.karoo.ui.field.OffRouteMessage
import io.roadbook.karoo.ui.field.SmallUpcomingField
import io.roadbook.karoo.util.streamDataFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "Upcoming POIs" data field. Renders, per enabled category, the next POIs ahead on
 * the route and how far. Size-adaptive:
 *  - large (≥2 grid rows): one row per category, three POIs each;
 *  - small: a single category, condensed. Auto-rotates through categories so the
 *    rider sees each without interaction (tap-to-cycle isn't reliably available on a
 *    Karoo graphical field — see plan; auto-rotate is the robust fallback).
 *
 * Everything is on-device: POIs and route length come from [RoadbookRepository],
 * live route progress from the `DISTANCE_TO_DESTINATION` stream.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class UpcomingPoisDataType(
    private val karooSystem: KarooSystemService,
    private val repository: RoadbookRepository,
    private val configStore: ConfigStore,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("startView upcoming-pois grid=${config.gridSize} size=${config.viewSize}")
        // How many category cards fit on a page is driven by pixel HEIGHT, not grid rows: a
        // short-but-wide slot lies about how many cards fit. capacityFor() turns the height
        // into a card count (2-col grid, as many rows as fit); render() lays that out and
        // pages through the rest on the rotation tick.
        val capacity = capacityFor(config.viewSize.second)

        // In the ride-profile EDITOR the field renders with preview=true. If it stays
        // tap-to-open there, tapping to select/move/delete the field launches Roadbook and
        // the field can't be removed. So make the field interactive only in a live ride.
        val interactive = !config.preview

        // Custom graphical field — don't let Karoo overlay a numeric header.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val scope = CoroutineScope(Dispatchers.IO)

        // Any size can page (a page is `capacity` cards), so the tick always runs. render()
        // ignores it when there's ≤ one page of content, so this never causes pointless
        // motion; the ticker just has to be running for when there IS more than one page.
        val rotation = MutableStateFlow(0L)
        scope.launch {
            var tick = 0L
            while (isActive) {
                kotlinx.coroutines.delay(ROTATE_MS)
                rotation.value = ++tick
            }
        }

        val progressFlow = karooSystem.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
            .map { it as? StreamState.Streaming }

        // MainActivity, launched when the rider taps the "Tap to build" prompt.
        val mainActivity = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS)

        val job = scope.launch {
            combine(
                repository.pois,
                repository.routeLengthMeters,
                configStore.config.map { it.enabledCategories },
                progressFlow,
                rotation,
            ) { pois, routeLen, enabled, stream, tick ->
                Frame(pois, routeLen, enabled, stream, tick)
            }.collect { f ->
                val remoteViews = glance.compose(context, DpSize.Unspecified) {
                    render(f, capacity, mainActivity, interactive)
                }.remoteViews
                emitter.updateView(remoteViews)
            }
        }

        emitter.setCancellable {
            Timber.d("stopView upcoming-pois")
            job.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private data class Frame(
        val pois: List<io.roadbook.karoo.data.Poi>,
        val routeLenMeters: Double,
        val enabled: Set<Category>,
        val stream: StreamState.Streaming?,
        val rotationTick: Long,
    )

    @androidx.compose.runtime.Composable
    private fun render(f: Frame, capacity: Int, mainActivity: ComponentName, interactive: Boolean) {
        if (f.enabled.isEmpty()) return FieldMessage("Enable a category", mainActivity, interactive)
        // "Tap to build" is ONLY for the genuine no-roadbook case. Once POIs exist we
        // always show them — even without a live route stream (we just measure from km 0).
        if (f.pois.isEmpty()) return BuildPromptField(mainActivity, interactive)

        // Live route progress, when available: route length − distance-to-destination.
        // Missing stream / no route length ⇒ progress 0 (show POIs from the start) rather
        // than falling back to the build prompt, which hid an already-built roadbook.
        val values = f.stream?.dataPoint?.values
        val toDest = values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
        val progress = if (f.routeLenMeters > 0.0 && toDest != null) {
            (f.routeLenMeters - toDest).coerceIn(0.0, f.routeLenMeters)
        } else {
            0.0
        }

        // Flag a genuine mid-ride deviation (ON_ROUTE=false after real progress); before
        // the start ON_ROUTE=false just means "not joined yet" and we show POIs anyway.
        val onRoute = values?.get(DataType.Field.ON_ROUTE)?.let { it >= 0.5 } ?: true
        if (!onRoute && progress > START_GRACE_METERS) return OffRouteMessage(mainActivity, interactive)

        val upcoming = upcomingByCategory(f.pois, f.enabled, progress)

        // Categories that actually have something ahead, sorted nearest-first (each list is
        // already nearest-first inside upcomingByCategory, so .first() is its nearest). An
        // enabled-but-empty category would just be a blank card, so we drop it.
        val ahead = f.enabled
            .filter { upcoming[it]?.isNotEmpty() == true }
            .sortedBy { upcoming.getValue(it).first().aheadMeters }

        if (capacity <= 1) {
            // Slot only fits one card: single rotating category. Prefer categories with POIs
            // ahead; fall back to all enabled if none do (so the field still shows *something*).
            val cats = ahead.ifEmpty { f.enabled.toList() }
            val cat = cats[(f.rotationTick % cats.size).toInt()]
            return SmallUpcomingField(rowFor(cat, upcoming[cat].orEmpty(), large = false), mainActivity, interactive)
        }

        if (ahead.isEmpty()) return FieldMessage("No POIs ahead", mainActivity, interactive)

        // Fill the slot: a page is `capacity` cards (a 2-col grid, as many rows as the height
        // fits — computed in capacityFor so cards never clip). If there are FEWER categories
        // ahead than capacity, show just those (no empty rows) — but never fewer than the
        // whole set, so the full screen isn't half-empty when only a few categories have POIs.
        // More categories than capacity → page through them on the rotation tick.
        val pageSize = capacity.coerceAtMost(ahead.size)
        val pageCount = (ahead.size + pageSize - 1) / pageSize
        // With ≤ one page the tick is irrelevant, so the field stays static (no motion).
        val pageIndex = if (pageCount > 1) (f.rotationTick % pageCount).toInt() else 0
        val page = ahead.drop(pageIndex * pageSize).take(pageSize)

        val rows = page.map { cat -> rowFor(cat, upcoming.getValue(cat), large = true) }
        LargeUpcomingField(rows, mainActivity, interactive)
    }

    /**
     * Build a [CategoryRow] of up to three [PoiCell]s. All keep the `km` unit; the UI
     * renders the unit in a smaller font on follow-ups so it fits the ~255dp width.
     */
    private fun rowFor(
        category: Category,
        pois: List<io.roadbook.karoo.data.UpcomingPoi>,
        large: Boolean,
    ): CategoryRow {
        val cells = pois.mapIndexed { i, p ->
            PoiCell(
                distance = formatKm(p.aheadMeters),
                // Chevrons mark "further along the route": nearest bare, 2nd ›, 3rd ».
                arrow = when (i) {
                    0 -> ""
                    1 -> "›"
                    else -> "»"
                },
                detour = formatDetour(p.detourMeters).removePrefix("·").takeIf { it.isNotEmpty() },
                detourMeters = p.detourMeters,
            )
        }
        val nameMax = if (large) NAME_MAX_LARGE else NAME_MAX_SMALL
        return CategoryRow(
            category = category,
            name = elideName(pois.firstOrNull()?.name, nameMax),
            cells = cells,
        )
    }

    companion object {
        const val TYPE_ID = "upcoming-pois"
        private const val MAIN_ACTIVITY_CLASS = "io.roadbook.karoo.MainActivity"

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
         * Returns 1 when the slot is too short for a grid → render() uses the single rotating
         * card. Fewer categories than capacity stretch to fill (defaultWeight), so the slot is
         * never half-empty; more page through on the tick.
         */
        private fun capacityFor(px: Int): Int {
            if (px < GRID_MIN_HEIGHT_PX) return 1
            val rows = (px / CARD_MIN_HEIGHT_PX).coerceIn(1, GRID_MAX_ROWS)
            return rows * GRID_COLS
        }

        private const val ROTATE_MS = 5_000L
        private const val NAME_MAX_LARGE = 18
        private const val NAME_MAX_SMALL = 12
        // Below this progress, treat ON_ROUTE=false as "not started yet" (rider is
        // approaching the route start) rather than a mid-ride deviation.
        private const val START_GRACE_METERS = 500.0
    }
}
