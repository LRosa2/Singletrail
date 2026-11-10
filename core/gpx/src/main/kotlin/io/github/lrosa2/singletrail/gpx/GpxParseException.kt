package io.github.lrosa2.singletrail.gpx

class GpxParseException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        NOT_GPX,
        MALFORMED_XML,
        DOCTYPE_NOT_ALLOWED,
        FILE_TOO_LARGE,
        TOO_MANY_POINTS,
        TOO_MANY_WAYPOINTS,
        TOO_MANY_TRACKS,
    }
}
