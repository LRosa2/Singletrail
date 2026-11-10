package io.github.lrosa2.singletrail.ride

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumMap

/**
 * Plays [Tone]s on the alarm stream (spec §4.3, D32), so they sound in silent and vibrate mode, at
 * the app's own [volume]. Tones queue up and play one after another.
 */
class BeepPlayer(scope: CoroutineScope, @Volatile var volume: Float = 0.8f) {
    private val queue = Channel<Tone>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pcm = EnumMap<Tone, ShortArray>(Tone::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(ToneSynth.SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    init {
        scope.launch(Dispatchers.Default) {
            for (tone in queue) play(tone)
        }
    }

    fun beep(tone: Tone) {
        queue.trySend(tone)
    }

    fun close() {
        queue.close()
    }

    private suspend fun play(tone: Tone) {
        val samples = pcm.getOrPut(tone) { ToneSynth.render(tone) }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "No audio track for $tone", e)
            return
        }
        try {
            track.write(samples, 0, samples.size)
            track.setVolume(volume)
            track.play()
            delay(tone.durationMillis + GAP_MS)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not play $tone", e)
        } finally {
            track.release()
        }
    }

    private companion object {
        const val TAG = "BeepPlayer"
        const val GAP_MS = 80L
    }
}

/** Reads the phone settings behind [RideWarning]s. */
object DeviceChecks {

    fun audio(context: Context): Set<RideWarning> {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val dnd = when (notifications.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndMode.TOTAL_SILENCE
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndMode.ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndMode.PRIORITY
            else -> DndMode.OFF
        }
        val alarmsInPriority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                notifications.notificationPolicy.priorityCategories and
                    NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
            } catch (_: SecurityException) {
                null
            }
        } else {
            null
        }
        val bluetooth = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in bluetoothTypes }
        return RideWarnings.audio(AudioReadings(audio.getStreamVolume(AudioManager.STREAM_ALARM), dnd, alarmsInPriority, bluetooth))
    }

    fun batterySaver(context: Context): Set<RideWarning> {
        val power = context.getSystemService(PowerManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            when (power.locationPowerSaveMode) {
                PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF -> SaverLocationMode.GPS_OFF_SCREEN_OFF
                PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF -> SaverLocationMode.ALL_OFF_SCREEN_OFF
                PowerManager.LOCATION_MODE_FOREGROUND_ONLY -> SaverLocationMode.FOREGROUND_ONLY
                PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF -> SaverLocationMode.THROTTLED_SCREEN_OFF
                else -> SaverLocationMode.NO_CHANGE
            }
        } else {
            // Android 8 has no per-mode setting; assume the worst while Battery Saver is on.
            SaverLocationMode.GPS_OFF_SCREEN_OFF
        }
        return RideWarnings.batterySaver(power.isPowerSaveMode, mode)
    }

    private val bluetoothTypes: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }
}
