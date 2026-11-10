package io.github.lrosa2.singletrail.ride

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** A beep at [hz] for [millis]; 0 Hz is a rest. */
data class Note(val hz: Int, val millis: Int)

/**
 * The ride's beeps (spec §4.3): six for guidance and GPS, two for the reverse prompt and the finish.
 * Each is a short, distinct pattern so it can be told apart without looking at the phone.
 */
enum class Tone(vararg notes: Note) {
    /** Two medium beeps. */
    APPROACHING(Note(880, 150), Note(0, 100), Note(880, 150)),

    /** Three quick high beeps. */
    NOW(Note(1320, 90), Note(0, 60), Note(1320, 90), Note(0, 60), Note(1320, 90)),

    /** A low warble. */
    OFF_ROUTE(Note(440, 250), Note(330, 250), Note(440, 250), Note(330, 250)),

    /** Low then high. */
    BACK_ON_ROUTE(Note(660, 150), Note(0, 40), Note(990, 200)),

    /** Falling. */
    SIGNAL_LOST(Note(990, 130), Note(660, 130), Note(440, 200)),

    /** Rising, quick. */
    SIGNAL_BACK(Note(440, 100), Note(660, 100), Note(990, 130)),

    /** Two-tone alternation. */
    REVERSE_PROMPT(Note(740, 110), Note(990, 110), Note(740, 110), Note(990, 110)),

    /** A rising arpeggio. */
    FINISH(Note(523, 130), Note(659, 130), Note(784, 130), Note(1047, 300)),
    ;

    val notes: List<Note> = notes.toList()
    val durationMillis: Int get() = notes.sumOf { it.millis }
}

/** Renders tones as 16-bit mono PCM. */
object ToneSynth {
    const val SAMPLE_RATE = 22_050
    private const val FADE_MS = 6
    private const val PEAK = 0.85

    fun render(tone: Tone): ShortArray {
        val lengths = tone.notes.map { it.millis * SAMPLE_RATE / 1000 }
        val wave = DoubleArray(lengths.sum())
        val fade = FADE_MS * SAMPLE_RATE / 1000
        var offset = 0
        tone.notes.forEachIndexed { k, note ->
            val n = lengths[k]
            if (note.hz > 0) {
                for (i in 0 until n) {
                    val w = 2 * PI * note.hz * i / SAMPLE_RATE
                    val envelope = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                    // A touch of the third harmonic makes a phone speaker sound brighter and carry further.
                    wave[offset + i] = envelope * (sin(w) + 0.3 * sin(3 * w))
                }
            }
            offset += n
        }
        val max = wave.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(wave.size) { (wave[it] / max * PEAK * Short.MAX_VALUE).toInt().toShort() }
    }
}
