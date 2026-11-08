package io.resupply.karoo.ui

import io.resupply.karoo.data.OpeningHours
import io.resupply.karoo.data.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source preference for opening hours: OSM is primary, but a *verified* Google result with
 * real hours wins (the rider confirmed it), while an empty/unknown Google result never
 * displaces good OSM data. Drives both the list-row ETA and the detail view.
 */
class HoursSourceTest {

    private fun poi(vararg tags: Pair<String, String>): Poi =
        Poi(id = "p", lat = 0.0, lng = 0.0, type = "FOOD", tags = tags.toMap())

    private val realHours = OpeningHours.Hours.fromOsm("Mo-Fr 08:00-18:00")
    private val emptyHours = OpeningHours.Hours.unknown()
    private val alwaysOpen = OpeningHours.Hours.fromOsm("24/7")
    private val rawOnly = OpeningHours.Hours.fromOsm("by appointment")

    // --- hasHours ---------------------------------------------------------------

    @Test
    fun `hasHours is true for a structured week and for 24-7`() {
        assertTrue(realHours.hasHours)
        assertTrue(alwaysOpen.hasHours)
    }

    @Test
    fun `hasHours is false for unknown and raw-fallback`() {
        assertFalse(emptyHours.hasHours)
        assertFalse(rawOnly.hasHours)
    }

    // --- hoursFor source preference ---------------------------------------------

    @Test
    fun `osm wins when google has no result`() {
        val resolved = hoursFor(poi("opening_hours" to "Mo-Fr 08:00-18:00"), googleHours = null)
        assertEquals(realHours.schedule, resolved?.schedule)
    }

    @Test
    fun `verified google with real hours wins over osm`() {
        val resolved = hoursFor(poi("opening_hours" to "Mo-Fr 08:00-18:00"), googleHours = alwaysOpen)
        assertTrue(resolved!!.is247)
    }

    @Test
    fun `empty google result never displaces osm hours`() {
        val resolved = hoursFor(poi("opening_hours" to "Mo-Fr 08:00-18:00"), googleHours = emptyHours)
        assertEquals(realHours.schedule, resolved?.schedule)
        assertFalse(resolved!!.unknown)
    }

    @Test
    fun `google fills the gap when osm has nothing`() {
        val resolved = hoursFor(poi(), googleHours = realHours)
        assertEquals(realHours.schedule, resolved?.schedule)
    }

    @Test
    fun `no source yields null`() {
        assertNull(hoursFor(poi(), googleHours = null))
    }
}
