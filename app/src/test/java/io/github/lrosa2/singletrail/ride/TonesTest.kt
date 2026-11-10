package io.github.lrosa2.singletrail.ride

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TonesTest {

    @Test
    fun `there are eight tones`() {
        assertEquals(8, Tone.entries.size)
    }

    @Test
    fun `every tone sounds different`() {
        val patterns = Tone.entries.map { it.notes }
        assertEquals(patterns.size, patterns.toSet().size)
    }

    @Test
    fun `each tone lasts as long as its notes, at most 1_2 s`() {
        for (tone in Tone.entries) {
            val pcm = ToneSynth.render(tone)
            val expected = tone.notes.sumOf { it.millis } * ToneSynth.SAMPLE_RATE / 1000
            assertTrue(abs(pcm.size - expected) <= tone.notes.size, "$tone: ${pcm.size} samples, expected $expected")
            assertTrue(tone.durationMillis <= 1200, "$tone lasts ${tone.durationMillis} ms")
        }
    }

    @Test
    fun `tones are loud but never clip`() {
        for (tone in Tone.entries) {
            val peak = ToneSynth.render(tone).maxOf { abs(it.toInt()) }
            assertTrue(peak in 16_000..31_500, "$tone peak $peak")
        }
    }

    @Test
    fun `notes fade in and out so they don't click`() {
        for (tone in Tone.entries) {
            val pcm = ToneSynth.render(tone)
            assertTrue(abs(pcm.first().toInt()) < 400, "$tone starts at ${pcm.first()}")
            assertTrue(abs(pcm.last().toInt()) < 400, "$tone ends at ${pcm.last()}")
        }
    }

    @Test
    fun `rests are silent`() {
        // The approaching tone is beep, rest, beep: the middle of the rest is zero.
        val pcm = ToneSynth.render(Tone.APPROACHING)
        val (first, rest) = Tone.APPROACHING.notes
        val mid = ((first.millis + rest.millis / 2) * ToneSynth.SAMPLE_RATE / 1000)
        assertEquals(0, pcm[mid].toInt())
    }
}
