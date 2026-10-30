package eu.studiodeanna.yans.sync

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun time(text: String) = LocalDateTime.parse(text).atZone(rome).toInstant().toEpochMilli()

    @Test
    fun `schedules are kept as text and read back`() {
        for (schedule in listOf(Schedule.Default, Schedule.ByHand, Schedule.Every(90), Schedule.At(180, setOf(FRIDAY, MONDAY)))) {
            assertEquals(schedule, Schedule.decode(schedule.encode()))
        }
        assertEquals("at 180 1,5", Schedule.At(180, setOf(FRIDAY, MONDAY)).encode())
        assertNull(Schedule.decode("at 180 "))
        assertNull(Schedule.decode("every 0"))
        assertNull(Schedule.decode("sometimes"))
    }

    @Test
    fun `every so often counts from the last sync`() {
        val last = time("2026-10-29T10:00")
        assertEquals(time("2026-10-29T11:30"), Schedule.Every(90).next(last, last, rome))
        assertEquals(last, Schedule.Every(90).next(null, last, rome))
        assertNull(Schedule.ByHand.next(last, last, rome))
    }

    @Test
    fun `a time of day comes on the days chosen`() {
        // Thursday 29 October 2026.
        val everyDay = Schedule.At(180, java.time.DayOfWeek.entries.toSet())
        assertEquals(time("2026-10-30T03:00"), everyDay.next(time("2026-10-29T10:00"), 0, rome))
        assertEquals(time("2026-10-29T03:00"), everyDay.next(time("2026-10-29T02:59"), 0, rome))
        val fridays = Schedule.At(180, setOf(FRIDAY))
        assertEquals(time("2026-10-30T03:00"), fridays.next(time("2026-10-29T10:00"), 0, rome))
        assertEquals(time("2026-11-06T03:00"), fridays.next(time("2026-10-30T03:00"), 0, rome))
        assertEquals(time("2026-11-06T03:00"), fridays.next(time("2026-10-30T07:00"), 0, rome))
    }

    @Test
    fun `a time missed is due at once`() {
        val fridays = Schedule.At(180, setOf(FRIDAY))
        val due = fridays.next(time("2026-10-29T10:00"), 0, rome)!!
        assertTrue(due <= time("2026-10-30T08:00"))
    }

    @Test
    fun `a time the clocks skip comes just after`() {
        // On 29 March 2026 the clocks in Rome go from 2:00 to 3:00.
        val half = Schedule.At(150, setOf(java.time.DayOfWeek.SUNDAY))
        assertEquals(time("2026-03-29T03:30"), half.next(time("2026-03-28T12:00"), 0, rome))
    }
}
