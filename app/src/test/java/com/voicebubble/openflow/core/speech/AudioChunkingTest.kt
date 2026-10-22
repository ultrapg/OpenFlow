package com.voicebubble.openflow.core.speech

import com.voicebubble.openflow.settings.DictationChunkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChunkingTest {

    private val sr = 16_000

    /** Deterministic +/- square wave with RMS exactly [amplitude]. */
    private fun tone(seconds: Double, amplitude: Float): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { i -> if (i % 2 == 0) amplitude else -amplitude }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    private val speech = 0.2f
    private val silence = 0.0005f

    private val config = DictationChunkSettings(
        liveTranscriptionEnabled = true,
        pauseMs = 600,
        minLiveChunkSeconds = 4f,
        maxChunkSeconds = 20f,
        silenceThreshold = 0.008f
    ).sanitized()

    @Test
    fun `live cut waits until minimum chunk length is reached`() {
        val audio = concat(tone(2.0, speech), tone(0.8, silence), tone(0.5, speech))
        assertNull(findStreamingCut(audio, audio.size, config))
    }

    @Test
    fun `live cut happens inside the first long enough pause after minimum length`() {
        val pauseStart = 6 * sr
        val audio = concat(tone(6.0, speech), tone(0.8, silence), tone(1.0, speech))
        val cut = findStreamingCut(audio, audio.size, config)
        assertNotNull(cut)
        assertTrue(cut!!.hasSpeech)
        assertTrue("cut ${cut.index} must lie inside the pause", cut.index in pauseStart..(pauseStart + (0.8 * sr).toInt()))
    }

    @Test
    fun `short breaths between words do not trigger a cut`() {
        val audio = concat(tone(6.0, speech), tone(0.3, silence), tone(1.0, speech))
        assertNull(findStreamingCut(audio, audio.size, config))
    }

    @Test
    fun `an early pause is skipped and a later one is used once minimum length is exceeded`() {
        // pause at 2.0 s is before the 4 s minimum and must be ignored; the one at 7.0 s qualifies
        val audio = concat(
            tone(2.0, speech), tone(0.8, silence),
            tone(4.2, speech), tone(0.8, silence),
            tone(0.5, speech)
        )
        val cut = findStreamingCut(audio, audio.size, config)
        assertNotNull(cut)
        val secondPauseStart = (7.0 * sr).toInt()
        assertTrue(cut!!.index >= secondPauseStart)
    }

    @Test
    fun `pause at the very end of the buffer is detected while the user is still silent`() {
        val audio = concat(tone(5.0, speech), tone(0.9, silence))
        val cut = findStreamingCut(audio, audio.size, config)
        assertNotNull(cut)
        assertTrue(cut!!.hasSpeech)
        assertTrue(cut.index in (5 * sr)..audio.size)
    }

    @Test
    fun `forced cut at max length lands inside the quietest dip when no real pause exists`() {
        val dipStart = (14.0 * sr).toInt()
        val dipEnd = dipStart + (0.2 * sr).toInt()
        // 200 ms dip is shorter than the configured 600 ms pause, but the buffer already hit the 20 s maximum
        val audio = concat(tone(14.0, speech), tone(0.2, silence), tone(11.0, speech))
        val cut = findStreamingCut(audio, audio.size, config)
        assertNotNull(cut)
        assertTrue(cut!!.hasSpeech)
        assertTrue("forced cut ${cut.index} should be inside [$dipStart..$dipEnd]", cut.index in dipStart..dipEnd)
    }

    @Test
    fun `buffer without any speech is discarded but keeps one second of lead-in`() {
        val audio = tone(6.0, silence)
        val cut = findStreamingCut(audio, audio.size, config)
        assertNotNull(cut)
        assertFalse(cut!!.hasSpeech)
        assertEquals(audio.size - sr, cut.index)
    }

    @Test
    fun `adaptive threshold treats steady background noise as silence`() {
        // 0.02 RMS background noise is far above the 0.008 user threshold, but it is the noise floor here
        val noise = 0.02f
        val audio = concat(tone(6.0, 0.3f), tone(0.9, noise), tone(1.0, 0.3f))
        // surround with noise so the 10th percentile noise floor is realistic
        val withNoiseLead = concat(tone(1.0, noise), audio)
        val cut = findStreamingCut(withNoiseLead, withNoiseLead.size, config)
        assertNotNull(cut)
        assertTrue(cut!!.hasSpeech)
    }

    @Test
    fun `best split point prefers the longest pause and cuts in its centre`() {
        val audio = concat(
            tone(1.0, speech),            // 0..1 s
            tone(0.3, silence),           // short pause
            tone(3.0, speech),
            tone(1.0, silence),           // long pause: 4.3..5.3 s
            tone(2.0, speech)
        )
        val idx = findBestSplitPoint(audio, 0, audio.size, silenceThreshold = 0.008f, minPauseFrames = 3)
        val longStart = (4.3 * sr).toInt()
        val longEnd = (5.3 * sr).toInt()
        assertTrue("split $idx should be inside the long pause", idx in longStart..longEnd)
        val centre = (longStart + longEnd) / 2
        assertTrue("split $idx should be near the pause centre $centre", kotlin.math.abs(idx - centre) < sr / 5)
    }

    @Test
    fun `best split point stays inside the requested range`() {
        val audio = tone(30.0, speech)
        val start = 11 * sr
        val end = 20 * sr
        val idx = findBestSplitPoint(audio, start, end)
        assertTrue(idx in start until end)
    }

    @Test
    fun `settings sanitizing clamps extreme and non finite values`() {
        val s = DictationChunkSettings(
            pauseMs = 10,
            minLiveChunkSeconds = Float.NaN,
            maxChunkSeconds = 500f,
            silenceThreshold = 5f
        ).sanitized()
        assertEquals(DictationChunkSettings.MIN_PAUSE_MS, s.pauseMs)
        assertEquals(DictationChunkSettings.MAX_MAX_CHUNK_SECONDS, s.maxChunkSeconds, 0.0001f)
        assertEquals(DictationChunkSettings.MAX_SILENCE_THRESHOLD, s.silenceThreshold, 0.0001f)
        assertTrue(s.minLiveChunkSeconds in DictationChunkSettings.MIN_LIVE_CHUNK_SECONDS_LOWER..DictationChunkSettings.MIN_LIVE_CHUNK_SECONDS_UPPER)

        val tight = DictationChunkSettings(minLiveChunkSeconds = 15f, maxChunkSeconds = 8f).sanitized()
        assertTrue("min live chunk must stay below 75% of max", tight.minLiveChunkSeconds <= tight.maxChunkSeconds * 0.75f + 0.0001f)
        assertEquals(DictationChunkSettings.DEFAULT_MAX_CHUNK_SECONDS, DictationChunkSettings(maxChunkSeconds = Float.NaN).sanitized().maxChunkSeconds, 0.0001f)
    }

    @Test
    fun `offline min chunk length is derived from max chunk length`() {
        val s = DictationChunkSettings(maxChunkSeconds = 20f).sanitized()
        assertEquals(20 * sr, s.maxChunkSamples)
        assertEquals(11 * sr, s.offlineMinChunkSamples)
    }
}
