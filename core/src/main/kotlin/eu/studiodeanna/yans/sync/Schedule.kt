package eu.studiodeanna.yans.sync

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When a folder syncs by itself, in the background. */
sealed interface Schedule {
    /** As the app's own schedule, which the other folders follow too. */
    data object Default : Schedule

    /** Never by itself: only from the app or the tile. */
    data object ByHand : Schedule

    /** [minutes] after the last sync. */
    data class Every(val minutes: Int) : Schedule

    /** At [minute] of the day (0 is midnight, 180 is 3:00), on the [days] of the week chosen. */
    data class At(val minute: Int, val days: Set<DayOfWeek>) : Schedule

    /** As kept in the settings: "default", "hand", "every 60", "at 180 1,5". */
    fun encode(): String = when (this) {
        Default -> "default"
        ByHand -> "hand"
        is Every -> "every $minutes"
        is At -> "at $minute ${days.sorted().joinToString(",") { it.value.toString() }}"
    }

    /**
     * When a folder last synced at [last] (null: never) is next due; null if never. [Default] must be resolved
     * to the app's schedule first. A time missed, the phone being off, is due at once.
     */
    fun next(last: Long?, now: Long, zone: ZoneId): Long? = when (this) {
        Default -> error("The default schedule must be resolved first")
        ByHand -> null
        is Every -> if (last == null) now else last + minutes * 60_000L
        is At -> nextTime(last ?: now, zone)
    }

    companion object {
        fun decode(text: String): Schedule? {
            val fields = text.split(' ')
            return when (fields[0]) {
                "default" -> Default
                "hand" -> ByHand
                "every" -> fields.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let(::Every)
                "at" -> {
                    val minute = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: return null
                    val days = fields.getOrNull(2).orEmpty().split(',').mapNotNull { it.toIntOrNull()?.takeIf { d -> d in 1..7 } }
                    if (days.isEmpty()) null else At(minute, days.map(DayOfWeek::of).toSet())
                }
                else -> null
            }
        }
    }
}

/** The first time of day after [after] that falls on one of the days. */
private fun Schedule.At.nextTime(after: Long, zone: ZoneId): Long? {
    if (days.isEmpty()) return null
    val time = LocalTime.of(minute / 60, minute % 60)
    val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
    for (offset in 0L..7L) {
        val date = start.plusDays(offset)
        if (date.dayOfWeek !in days) continue
        // At a time skipped by the clocks going forward, it comes as soon after as there is.
        val at = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
        if (at > after) return at
    }
    return null
}
