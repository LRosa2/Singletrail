package io.github.lrosa2.singletrail.data.rides

import io.github.lrosa2.singletrail.nav.engine.NavCheckpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One recorded fix. [timeMs] is GPS UTC time (for export); [elapsedMs] the elapsed-realtime clock. */
data class TrackPoint(
    val timeMs: Long,
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    /** 1 for the first stretch; each resume after a crash or reboot starts the next one. */
    val segment: Int,
)

/**
 * Appends fixes to a ride's track file (spec §6, §8.3): one CSV line per fix,
 * `timeMs,elapsedMs,lat,lon,ele,acc,speed,bearing,segment`, written out at least every
 * [flushEvery] points or [flushMillis]. When storage runs low or a write fails it stops for good
 * and says so through [stopped]; navigation goes on without recording.
 */
class TrackWriter(
    private val file: File,
    private val segment: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    /** On Android the ride service passes `StorageManager.getAllocatableBytes`; this default is for tests. */
    @Suppress("UsableSpace")
    private val freeBytes: () -> Long = { file.parentFile?.usableSpace ?: Long.MAX_VALUE },
    private val flushEvery: Int = 10,
    private val flushMillis: Long = 10_000,
) : Closeable {
    private val pending = StringBuilder()
    private var pendingCount = 0
    private var lastFlush = clock()
    private var out: FileOutputStream? = null

    /** True once recording has stopped because of low storage or a write error. */
    var stopped = false
        private set

    /** The write error that stopped recording, if that is why it stopped. */
    var error: IOException? = null
        private set

    fun append(point: TrackPoint) {
        if (stopped) return
        with(point) {
            pending.append(timeMs).append(',').append(elapsedMs).append(',')
                .append(lat).append(',').append(lon).append(',')
                .append(ele ?: "").append(',').append(accuracy ?: "").append(',')
                .append(speed ?: "").append(',').append(bearing ?: "").append(',')
                .append(segment).append('\n')
        }
        pendingCount++
        if (pendingCount >= flushEvery || clock() - lastFlush >= flushMillis) flush()
    }

    fun flush() {
        if (stopped || pendingCount == 0) return
        lastFlush = clock()
        // The folder must exist before asking how much space it has (a missing one reports 0).
        file.parentFile?.mkdirs()
        if (freeBytes() < MIN_FREE_BYTES) {
            stop(null)
            return
        }
        try {
            val stream = out ?: FileOutputStream(file, true).also { out = it }
            stream.write(pending.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            pending.clear()
            pendingCount = 0
        } catch (e: IOException) {
            stop(e)
        }
    }

    override fun close() {
        flush()
        try {
            out?.close()
        } catch (_: IOException) {
            // Nothing more can be saved.
        }
        out = null
    }

    private fun stop(cause: IOException?) {
        stopped = true
        error = cause
        pending.clear()
        pendingCount = 0
    }

    companion object {
        /** Below this much free space, recording stops rather than fill the phone. */
        const val MIN_FREE_BYTES = 10L * 1024 * 1024
    }
}

object TrackReader {
    /** All complete, well-formed lines; a partial last line (a crash mid-write) is ignored. */
    fun read(file: File): List<TrackPoint> {
        if (!file.isFile) return emptyList()
        val lines = file.readText(Charsets.UTF_8).split('\n')
        // The last piece is empty when the file ends with a newline, or else an unfinished line.
        return lines.dropLast(1).mapNotNull(::parse)
    }

    private fun parse(line: String): TrackPoint? {
        val f = line.split(',')
        if (f.size != 9) return null
        return try {
            TrackPoint(
                timeMs = f[0].toLong(),
                elapsedMs = f[1].toLong(),
                lat = f[2].toDouble(),
                lon = f[3].toDouble(),
                ele = f[4].takeIf { it.isNotEmpty() }?.toDouble(),
                accuracy = f[5].takeIf { it.isNotEmpty() }?.toFloat(),
                speed = f[6].takeIf { it.isNotEmpty() }?.toFloat(),
                bearing = f[7].takeIf { it.isNotEmpty() }?.toFloat(),
                segment = f[8].toInt(),
            )
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/** What a ride needs to carry on after the app was killed (D28), saved every few seconds. */
@Serializable
data class RideCheckpoint(
    val rideId: String,
    val routeId: String,
    /** The track segment being recorded; a resume records the next one. */
    val segment: Int,
    /** A debug ride fed by the simulator rather than GPS. */
    val simulated: Boolean = false,
    val nav: NavCheckpoint,
)

/** Checkpoints under `rides/` in [filesDir], replaced atomically so a crash never leaves half a file. */
class CheckpointStore(private val filesDir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(checkpoint: RideCheckpoint) {
        val target = file(checkpoint.rideId)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.encodeToString(RideCheckpoint.serializer(), checkpoint), Charsets.UTF_8)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The saved checkpoint, or null when there is none or it can't be read. */
    fun load(rideId: String): RideCheckpoint? = try {
        json.decodeFromString(RideCheckpoint.serializer(), file(rideId).readText(Charsets.UTF_8))
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun delete(rideId: String) {
        file(rideId).delete()
    }

    private fun file(rideId: String) = File(filesDir, "rides/$rideId.checkpoint.json")
}
