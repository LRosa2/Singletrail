package io.github.lrosa2.singletrail.ui

import java.util.Locale
import kotlin.math.roundToLong

/** Metric display formats (spec D23). The UI is English, so number formatting is fixed to US style. */
object Formats {
    /** "850 m", "12.3 km", "150 km". */
    fun distance(meters: Double): String = when {
        meters < 999.5 -> "${meters.roundToLong()} m"
        meters < 99_950.0 -> String.format(Locale.US, "%.1f km", meters / 1000)
        else -> String.format(Locale.US, "%,d km", (meters / 1000).roundToLong())
    }

    /** "1,234 m". */
    fun elevation(meters: Double): String = String.format(Locale.US, "%,d m", meters.roundToLong())
}
