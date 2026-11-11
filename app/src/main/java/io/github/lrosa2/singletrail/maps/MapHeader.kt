package io.github.lrosa2.singletrail.maps

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/** Why a map couldn't be installed (spec §7.2). */
enum class MapProblem {
    NOT_A_ZIP,
    NO_MAP_IN_ZIP,
    TOO_MANY_ENTRIES,
    TOO_LARGE,
    NO_SPACE,
    NOT_A_MAP,
    UNSUPPORTED_VERSION,
    INCOMPLETE,
    DAMAGED,
}

class MapFileException(
    val problem: MapProblem,
    message: String,
    cause: Throwable? = null,
    /** For [MapProblem.NO_SPACE]: how much free space the install needs. */
    val bytesNeeded: Long? = null,
) : IOException(message, cause)

/**
 * The start of a Mapsforge binary map file: enough to tell a real, complete map and the area it
 * covers (format specification "Specification-Binary-Map-File", versions 3–5).
 */
data class MapHeader(
    val version: Int,
    /** Milliseconds since 1970. */
    val createdAt: Long,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val createdBy: String?,
) {
    companion object {
        private const val MAGIC = "mapsforge binary OSM"
        private val VERSIONS = 3..5
        private const val MAX_HEADER = 1_000_000
        private const val MAX_STRING = 64 * 1024

        /** @throws MapFileException when the file isn't a complete map in a version we can read */
        fun read(file: File): MapHeader {
            val size = file.length()
            return DataInputStream(BufferedInputStream(file.inputStream())).use { input -> read(input, size) }
        }

        private fun read(input: DataInputStream, actualSize: Long): MapHeader {
            val magic = ByteArray(MAGIC.length)
            try {
                input.readFully(magic)
            } catch (e: EOFException) {
                throw MapFileException(MapProblem.NOT_A_MAP, "Too short for a map file", e)
            }
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw MapFileException(MapProblem.NOT_A_MAP, "No map file signature")
            try {
                val headerSize = input.readInt()
                if (headerSize !in 1..MAX_HEADER) damaged("Header size $headerSize")
                val version = input.readInt()
                if (version !in VERSIONS) throw MapFileException(MapProblem.UNSUPPORTED_VERSION, "Map file version $version")
                val declaredSize = input.readLong()
                if (actualSize < declaredSize) throw MapFileException(MapProblem.INCOMPLETE, "$actualSize of $declaredSize bytes")
                if (actualSize > declaredSize) damaged("$actualSize bytes, header says $declaredSize")
                val createdAt = input.readLong()
                val minLat = input.readInt() / 1e6
                val minLon = input.readInt() / 1e6
                val maxLat = input.readInt() / 1e6
                val maxLon = input.readInt() / 1e6
                if (minLat < -90 || maxLat > 90 || minLon < -180 || maxLon > 180 || minLat > maxLat || minLon > maxLon) {
                    damaged("Impossible bounding box")
                }
                if (input.readShort() <= 0) damaged("Tile size")
                input.string(MAX_STRING) // projection
                val flags = input.readUnsignedByte()
                if (flags and 0x40 != 0) input.skipFully(8) // start position
                if (flags and 0x20 != 0) input.skipFully(1) // start zoom
                if (flags and 0x10 != 0) input.string(MAX_STRING) // preferred languages
                if (flags and 0x08 != 0) input.string(MAX_STRING) // comment
                val createdBy = if (flags and 0x04 != 0) input.string(MAX_STRING) else null
                return MapHeader(version, createdAt, minLat, minLon, maxLat, maxLon, createdBy)
            } catch (e: EOFException) {
                throw MapFileException(MapProblem.INCOMPLETE, "The header is cut off", e)
            }
        }

        private fun damaged(message: String): Nothing = throw MapFileException(MapProblem.DAMAGED, message)

        /** A VBE-U length, then that many UTF-8 bytes. */
        private fun DataInputStream.string(max: Int): String {
            val length = vbe()
            if (length > max) damaged("String of $length bytes")
            val bytes = ByteArray(length.toInt())
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        /** Seven bits per byte, least significant first; the high bit means another byte follows. */
        private fun DataInputStream.vbe(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = readUnsignedByte()
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                if (shift > 28) damaged("Number too long")
            }
        }

        private fun DataInputStream.skipFully(n: Int) {
            repeat(n) { readUnsignedByte() }
        }
    }
}
