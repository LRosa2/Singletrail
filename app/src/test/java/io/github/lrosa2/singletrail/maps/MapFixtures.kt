package io.github.lrosa2.singletrail.maps

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A minimal Mapsforge map file: a real header (spec of the binary map format), then zero padding. */
fun mapBytes(
    version: Int = 5,
    createdAt: Long = 1_793_750_400_000,
    minLat: Double = 45.8,
    minLon: Double = 5.9,
    maxLat: Double = 47.9,
    maxLon: Double = 10.5,
    createdBy: String? = "mapsforge-map-writer",
    totalSize: Int = 2048,
    declaredSize: Long = totalSize.toLong(),
    magic: String = "mapsforge binary OSM",
): ByteArray {
    val rest = ByteArrayOutputStream()
    DataOutputStream(rest).apply {
        writeInt(version)
        writeLong(declaredSize)
        writeLong(createdAt)
        writeInt((minLat * 1e6).toInt())
        writeInt((minLon * 1e6).toInt())
        writeInt((maxLat * 1e6).toInt())
        writeInt((maxLon * 1e6).toInt())
        writeShort(256)
        writeVbeString("Mercator")
        writeByte(if (createdBy != null) 0x04 else 0)
        if (createdBy != null) writeVbeString(createdBy)
        // POI tags, way tags, zoom intervals: none, which is enough for the header check.
        write(0)
        write(0)
        write(0)
        write(0)
        write(0)
    }
    val header = rest.toByteArray()
    val out = ByteArrayOutputStream()
    DataOutputStream(out).apply {
        write(magic.toByteArray(Charsets.US_ASCII))
        writeInt(header.size)
        write(header)
    }
    val bytes = out.toByteArray()
    return if (bytes.size >= totalSize) bytes else bytes + ByteArray(totalSize - bytes.size)
}

private fun DataOutputStream.writeVbeString(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    writeVbe(bytes.size.toLong())
    write(bytes)
}

fun DataOutputStream.writeVbe(value: Long) {
    var v = value
    while (v >= 0x80) {
        write(((v and 0x7f) or 0x80).toInt())
        v = v ushr 7
    }
    write(v.toInt())
}

fun zipOf(file: File, vararg entries: Pair<String, ByteArray>): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return file
}
