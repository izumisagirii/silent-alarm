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
}
