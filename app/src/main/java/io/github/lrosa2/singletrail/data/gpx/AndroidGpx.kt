package io.github.lrosa2.singletrail.data.gpx

import android.util.Xml
import io.github.lrosa2.singletrail.gpx.GpxLimits
import io.github.lrosa2.singletrail.gpx.GpxReader

/** GPX reader on Android's built-in pull parser, so the APK carries no XML library of its own. */
fun androidGpxReader(limits: GpxLimits = GpxLimits.DEFAULT): GpxReader =
    GpxReader({ Xml.newPullParser() }, limits)
