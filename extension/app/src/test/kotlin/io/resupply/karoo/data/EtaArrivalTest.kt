package io.resupply.karoo.data

import io.resupply.karoo.data.OpeningHours.ArrivalStatus
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ETA math and "open on arrival" classification for the Waybook list cue. */
class EtaArrivalTest {

    // A fixed Monday reference clock so the day-of-week and time are deterministic.
    // 2024-01-01 was a Monday.
    private fun monday(hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    // --- ETA math ---------------------------------------------------------------

    @Test
    fun `eta uses live speed when fast enough`() {
        // 10 km at 10 m/s (36 km/h) = 1000 s = ~16.7 min.
        assertEquals(1000.0 / 60.0, etaMinutes(10_000.0, 10.0)!!, 1e-6)
    }

    @Test
    fun `no eta without a trustworthy moving average`() {
        // Stopped / no sample / below the floor → no ETA at all, never a guessed pace. This is
        // the "route loaded but not started yet" case: we don't fabricate an arrival time.
        assertNull(etaMinutes(10_000.0, 0.0))    // stopped
        assertNull(etaMinutes(10_000.0, null))   // no sample
        assertNull(etaMinutes(10_000.0, 0.5))    // below floor
    }

    @Test
    fun `eta is zero for a POI underfoot`() {
        assertEquals(0.0, etaMinutes(0.0, 5.0)!!, 1e-6)
    }

    @Test
    fun `arrival clock advances by the ride time`() {
        // 18 km at 5 m/s = 3600 s = 1 h → 12:00 + 1h = 13:00.
        val arrival = etaArrival(18_000.0, 5.0, monday(12, 0))!!
        assertEquals("13:00", formatEtaClock(arrival))
    }

    @Test
    fun `no arrival without a trustworthy moving average`() {
        assertNull(etaArrival(18_000.0, null, monday(12, 0)))
        assertNull(etaArrival(18_000.0, 0.0, monday(12, 0)))
    }

    // --- arrival-status classification -----------------------------------------

    private fun osm(spec: String) = OpeningHours.Hours.fromOsm(spec)

    @Test
    fun `open well inside hours is OPEN`() {
        val hours = osm("Mo-Fr 08:00-18:00")
        assertEquals(ArrivalStatus.OPEN, hours.arrivalStatus(monday(12, 0)))
    }

    @Test
    fun `arriving just before close is a CLOSE_CALL`() {
        val hours = osm("Mo-Fr 08:00-18:00")
        // 17:45 — within the 30-min margin of the 18:00 close.
        assertEquals(ArrivalStatus.CLOSE_CALL, hours.arrivalStatus(monday(17, 45)))
    }

    @Test
    fun `arriving just before open is a CLOSE_CALL`() {
        val hours = osm("Mo-Fr 08:00-18:00")
        // 07:45 — shut, but opens within the margin; the rider might just make it.
        assertEquals(ArrivalStatus.CLOSE_CALL, hours.arrivalStatus(monday(7, 45)))
    }

    @Test
    fun `arriving after close is CLOSED`() {
        val hours = osm("Mo-Fr 08:00-18:00")
        assertEquals(ArrivalStatus.CLOSED, hours.arrivalStatus(monday(19, 0)))
    }

    @Test
    fun `arriving long before open is CLOSED`() {
        val hours = osm("Mo-Fr 08:00-18:00")
        assertEquals(ArrivalStatus.CLOSED, hours.arrivalStatus(monday(6, 0)))
    }

    @Test
    fun `24-7 is always OPEN on arrival`() {
        assertEquals(ArrivalStatus.OPEN, osm("24/7").arrivalStatus(monday(3, 0)))
    }

    @Test
    fun `unparseable hours are UNKNOWN`() {
        // A raw fallback we can't structure → never a false open/closed claim.
        assertEquals(ArrivalStatus.UNKNOWN, osm("by appointment").arrivalStatus(monday(12, 0)))
    }

    @Test
    fun `closed day is CLOSED`() {
        // Sunday closed (no Su rule) — arriving Monday is fine, but check a closed weekday
        // via a Saturday-only spec against our Monday clock.
        val hours = osm("Sa 09:00-13:00")
        assertEquals(ArrivalStatus.CLOSED, hours.arrivalStatus(monday(10, 0)))
    }

    @Test
    fun `right at opening minute is OPEN not close-call`() {
        // Exactly 08:00 is inside the range; the "opens soon" near-miss only fires while shut.
        val hours = osm("Mo-Fr 08:00-18:00")
        assertEquals(ArrivalStatus.OPEN, hours.arrivalStatus(monday(8, 0)))
    }
}
