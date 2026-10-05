package com.electrowiz.silentalarm.util

import java.util.TimeZone
import kotlin.math.abs

/** Shared UTC offset formatting used by the dashboard and timezone picker. */
object TimezoneFormatter {

    fun offsetLabel(zone: TimeZone): String {
        val totalMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (totalMinutes >= 0) "+" else "-"
        return "UTC$sign%02d:%02d".format(abs(totalMinutes / 60), abs(totalMinutes % 60))
    }

    /** Compact "id (UTC±HH:MM)" label for a zone id, blank-safe. */
    fun displayLabel(zoneId: String): String {
        val zone = TimeZone.getTimeZone(zoneId.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id)
        return "${zone.id} (${offsetLabel(zone)})"
    }
}
