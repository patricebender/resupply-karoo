package io.resupply.karoo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResupplyConfigTest {

    private fun water(tags: Map<String, String>) =
        Poi(id = "w", lat = 0.0, lng = 0.0, type = "REST_STOP", tags = tags)

    @Test
    fun `isSafeWaterSource accepts taps, graveyards and explicitly-potable`() {
        assertTrue(ResupplyConfig.isSafeWaterSource(mapOf("water_subtype" to "tap")))
        assertTrue(ResupplyConfig.isSafeWaterSource(mapOf("water_subtype" to "graveyard")))
        assertTrue(
            ResupplyConfig.isSafeWaterSource(
                mapOf("water_subtype" to "spring", "drinking_water" to "yes"),
            ),
        )
    }

    @Test
    fun `isSafeWaterSource rejects unknown-quality sources`() {
        assertFalse(
            ResupplyConfig.isSafeWaterSource(
                mapOf("water_subtype" to "fountain", "drinking_water" to "unknown"),
            ),
        )
        assertFalse(ResupplyConfig.isSafeWaterSource(mapOf("water_subtype" to "well")))
        assertFalse(ResupplyConfig.isSafeWaterSource(emptyMap()))
    }

    @Test
    fun `showsPoi gates on the enabled category`() {
        val cfg = ResupplyConfig(enabledCategories = setOf(Category.FUEL))
        val fuel = Poi(id = "f", lat = 0.0, lng = 0.0, type = "GAS_STATION")
        assertTrue(cfg.showsPoi(fuel))
        // Water category off → hidden regardless of safety.
        assertFalse(cfg.showsPoi(water(mapOf("water_subtype" to "tap"))))
    }

    @Test
    fun `showsPoi narrows water only when safeWaterOnly is on`() {
        val strict = ResupplyConfig(enabledCategories = setOf(Category.WATER), safeWaterOnly = true)
        assertTrue(strict.showsPoi(water(mapOf("water_subtype" to "tap"))))
        assertFalse(strict.showsPoi(water(mapOf("water_subtype" to "fountain"))))

        val lax = strict.copy(safeWaterOnly = false)
        assertTrue(lax.showsPoi(water(mapOf("water_subtype" to "fountain"))))
    }

    @Test
    fun `showsPoi leaves non-water categories untouched by the water gate`() {
        val cfg = ResupplyConfig(enabledCategories = setOf(Category.FUEL), safeWaterOnly = true)
        val fuel = Poi(id = "f", lat = 0.0, lng = 0.0, type = "GAS_STATION")
        assertTrue(cfg.showsPoi(fuel))
    }

    @Test
    fun `showsPoi narrows to favorites only when favoritesOnly is on`() {
        val fuelA = Poi(id = "a", lat = 0.0, lng = 0.0, type = "GAS_STATION")
        val fuelB = Poi(id = "b", lat = 0.0, lng = 0.0, type = "GAS_STATION")
        val cfg = ResupplyConfig(
            enabledCategories = setOf(Category.FUEL),
            favoritePoiIds = setOf("a"),
            favoritesOnly = true,
        )
        assertTrue(cfg.showsPoi(fuelA))   // starred → shown
        assertFalse(cfg.showsPoi(fuelB))  // not starred → hidden

        // Filter off → both show regardless of the favorites set.
        val off = cfg.copy(favoritesOnly = false)
        assertTrue(off.showsPoi(fuelA))
        assertTrue(off.showsPoi(fuelB))
    }

    @Test
    fun `isFavorite reflects membership in the favorites set`() {
        val cfg = ResupplyConfig(favoritePoiIds = setOf("a"))
        assertTrue(cfg.isFavorite(Poi(id = "a", lat = 0.0, lng = 0.0, type = "GAS_STATION")))
        assertFalse(cfg.isFavorite(Poi(id = "b", lat = 0.0, lng = 0.0, type = "GAS_STATION")))
    }
}
