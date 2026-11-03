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

    @Test
    fun `smart pause accepts a shorter pause late in a long stretch but not early`() {
        // 0.45 s gap: shorter than the configured 600 ms pause, accepted once the chunk is already 12 s long
        val late = concat(tone(12.0, speech), tone(0.45, silence), tone(1.0, speech))
        val lateCut = findStreamingCut(late, late.size, config)
        assertNotNull("0.45 s gap after 12 s of speech should be used as a cut point", lateCut)
        assertTrue(lateCut!!.index >= 12 * sr)

        // The same gap right after the minimum length is still just a breath between phrases
        val early = concat(tone(4.2, speech), tone(0.45, silence), tone(1.0, speech))
        assertNull(findStreamingCut(early, early.size, config))

        // With smart pause switched off the long-stretch gap no longer qualifies
        val strict = config.copy(adaptivePause = false)
        assertNull(findStreamingCut(late, late.size, strict))
    }

    @Test
    fun `required pause shrinks monotonically from configured length down to the floor`() {
        val base = config.pauseSamples / CHUNK_FRAME_SAMPLES
        assertEquals(base, requiredPauseFrames(config.minLiveChunkSamples, config))
        assertEquals(base, requiredPauseFrames(0, config))
        var previous = base
        var pending = config.minLiveChunkSamples
        while (pending <= config.maxChunkSamples) {
            val frames = requiredPauseFrames(pending, config)
            assertTrue("required pause must never grow ($frames > $previous)", frames <= previous)
            assertTrue("required pause must stay >= 2 frames and >= half the configured pause", frames >= 2 && frames * 2 >= base - 1)
            previous = frames
            pending += sr / 2
        }
        assertEquals(base, requiredPauseFrames(config.maxChunkSamples, config.copy(adaptivePause = false)))
    }

    @Test
    fun `a click inside a pause does not split the pause but a real word does`() {
        // 0.4 s silence + 20 ms click + 0.4 s silence: each half alone is shorter than the required pause
        val withClick = concat(
            tone(6.0, speech),
            tone(0.4, silence), tone(0.02, 0.5f), tone(0.4, silence),
            tone(1.0, speech)
        )
        val clickCut = findStreamingCut(withClick, withClick.size, config)
        assertNotNull("a lone click must be ignored so the 0.82 s pause is detected", clickCut)
        assertTrue(clickCut!!.index in (6 * sr)..(6 * sr + (0.82 * sr).toInt()))

        // A 100 ms real word between the two short silences is speech and keeps them apart
        val withWord = concat(
            tone(6.0, speech),
            tone(0.4, silence), tone(0.1, speech), tone(0.4, silence),
            tone(1.0, speech)
        )
        assertNull(findStreamingCut(withWord, withWord.size, config))
    }

    @Test
    fun `tuned defaults and master switch behave as documented`() {
        val d = DictationChunkSettings()
        assertTrue(d.chunkingEnabled)
        assertTrue(d.liveActive)
        assertEquals(650, d.pauseMs)
        assertEquals(6f, d.minLiveChunkSeconds, 0.0001f)
        assertEquals(18f, d.maxChunkSeconds, 0.0001f)
        assertTrue(d.adaptivePause)
        assertEquals(d, d.sanitized())

        val off = d.copy(chunkingEnabled = false)
        assertFalse("live transcription needs chunking", off.liveActive)
        assertEquals("one piece up to the 60 s safety ceiling", 60 * sr, off.effectiveMaxChunkSamples)
        assertEquals(18 * sr, off.maxChunkSamples)
        assertEquals(18 * sr, d.effectiveMaxChunkSamples)
    }

    @Test
    fun `disabled chunking keeps a 45 second dictation in one piece but still splits past the safety ceiling`() {
        val off = DictationChunkSettings(chunkingEnabled = false).sanitized()
        val clip45 = FloatArray(45 * sr) { speech }
        val one = splitAudioIntoSmartChunks(
            samples = clip45,
            maxChunkSamples = off.effectiveMaxChunkSamples,
            minChunkSamples = off.effectiveOfflineMinChunkSamples
        )
        assertEquals(1, one.size)

        val clip90 = FloatArray(90 * sr) { speech }
        val split = splitAudioIntoSmartChunks(
            samples = clip90,
            maxChunkSamples = off.effectiveMaxChunkSamples,
            minChunkSamples = off.effectiveOfflineMinChunkSamples
        )
        assertTrue(split.size >= 2)
        assertEquals(clip90.size, split.sumOf { it.size })
        assertTrue(split.all { it.size <= off.effectiveMaxChunkSamples })
    }

    @Test
    fun `live cut provides both-sided silence padding inside the pause without duplicating speech`() {
        val pauseStart = 6 * sr
        val pauseEnd = pauseStart + (0.65 * sr).toInt()
        val audio = concat(tone(6.0, speech), tone(0.65, silence), tone(2.0, speech))
        val cut = findStreamingCut(audio, audio.size, config)
        assertNotNull(cut)
        // Outgoing chunk ends inside the pause with trailing silence padding
        assertTrue(cut!!.index in (pauseStart + 1_600)..pauseEnd)
        // Remaining buffer resumes inside the pause before cut.index so next chunk also has leading silence
        assertTrue(cut.resumeIndex in (pauseStart + CHUNK_FRAME_SAMPLES)..cut.index)
        assertTrue("next chunk must retain at least 160 ms of leading silence", pauseEnd - cut.resumeIndex >= 2_560)
    }

    @Test
    fun `speech hangover keeps soft trailing consonant frames attached to the spoken word`() {
        // 5 frames of strong vowel (0.15) followed by 3 frames of soft consonant tail (0.0055, above 0.55 * 0.008)
        // followed by true silence (0.001)
        val rms = floatArrayOf(
            0.001f, 0.006f, 0.15f, 0.15f, 0.15f, 0.15f, 0.15f,
            0.0055f, 0.0052f, 0.0050f,
            0.001f, 0.001f, 0.001f
        )
        val mask = buildSilenceMask(rms, 0.008f)
        assertTrue("frame 0 is silence", mask[0])
        assertFalse("frame 1 soft consonant onset is protected", mask[1])
        for (f in 2..9) {
            assertFalse("frame $f (word + soft consonant tail) must not be marked silent", mask[f])
        }
        assertTrue("frame 10 is true silence after hangover", mask[10])
    }

    @Test
    fun `hasUsableSpeech rejects silent or button-tap-only tails and accepts real speech`() {
        val silentTail = tone(0.4, silence)
        assertFalse(hasUsableSpeech(silentTail))

        // 40 ms finger-tap click surrounded by silence -> rejected by click suppression
        val tapTail = concat(tone(0.15, silence), tone(0.04, 0.3f), tone(0.2, silence))
        assertFalse(hasUsableSpeech(tapTail))

        // 250 ms spoken syllable -> accepted
        val spokenTail = concat(tone(0.1, silence), tone(0.25, speech), tone(0.1, silence))
        assertTrue(hasUsableSpeech(spokenTail))
    }

    @Test
    fun `stop closure and plosive release burst at the end of a word are bridged and never erased as a click`() {
        // Word vowel (5 frames @ 0.16) + 3-frame stop closure (60 ms @ 0.001) + 1-frame -t/-k release burst (0.04)
        // followed by true silence
        val rms = floatArrayOf(
            0.001f,
            0.16f, 0.16f, 0.16f, 0.16f, 0.16f, // vowel core (frames 1..5)
            0.001f, 0.001f, 0.001f,             // 60 ms stop closure (frames 6..8)
            0.04f,                              // 20 ms plosive release burst -t / -k (frame 9)
            0.0045f, 0.0040f,                   // soft aspirate tail (frames 10..11)
            0.0005f, 0.0005f, 0.0005f           // true pause (frames 12..14)
        )
        val mask = buildSilenceMask(rms, 0.008f)
        assertTrue("frame 0 is leading silence", mask[0])
        for (f in 1..11) {
            assertFalse("frame $f (vowel + stop closure + plosive release + aspirate tail) must be speech", mask[f])
        }
        assertTrue("pause must only begin at frame 12 after the plosive release and aspirate tail", mask[12])
    }

    @Test
    fun `hasAnyTailSpeech accepts a short quiet final word while rejecting pure silence and single tap`() {
        assertFalse(hasAnyTailSpeech(tone(0.4, silence)))
        // 40 ms isolated tap in silence (even straddling frame boundaries at 0.15s) -> rejected
        val tap = concat(tone(0.15, silence), tone(0.04, 0.25f), tone(0.2, silence))
        assertFalse(hasAnyTailSpeech(tap))
        // 90 ms quiet spoken final word ("it", "on", "ok") -> accepted so end of dictation is never dropped
        val quietFinalWord = concat(tone(0.1, silence), tone(0.09, 0.005f), tone(0.15, silence))
        assertTrue(hasAnyTailSpeech(quietFinalWord))
    }

    @Test
    fun `early 300ms ambient noise calibration rescues silence detection during non-stop noisy speech`() {
        val fanNoise = 0.014f
        val loudSpeech = 0.28f
        // Only ~5.5% of the buffer is fan noise (0.32s lead + 0.72s pause vs 16.5s loud speech),
        // which would push a naive percentile dangerously close to speech without early 300ms calibration.
        val audio = concat(
            tone(0.32, fanNoise),
            tone(8.0, loudSpeech),
            tone(0.75, fanNoise),
            tone(8.5, loudSpeech)
        )
        val rms = computeFrameRms(audio, 0, audio.size)
        val calibratedFloor = calibrateAmbientNoiseFloor(rms)
        assertTrue(
            "Expected calibrated noise floor ($calibratedFloor) near 0.014f, well below speech",
            calibratedFloor in 0.010f..0.020f
        )
        val cut = findStreamingCut(audio, audio.size, DictationChunkSettings())
        assertNotNull(cut)
        assertEquals(CutKind.CONFIDENT_PAUSE, cut!!.cutKind)
        val pauseStart = ((0.32 + 8.0) * sr).toInt()
        val pauseEnd = pauseStart + (0.75 * sr).toInt()
        assertTrue("Pause cut ${cut.index} must land inside the 0.75s noisy pause", cut.index in pauseStart..pauseEnd)
    }

    @Test
    fun `rapid syllable cadence bonus protects fast talkers from brief mid-clause catch-breath cuts`() {
        val defaultCfg = DictationChunkSettings() // min=6s, max=18s, pause=650ms
        // Build 9.0s of rapid 6.25-syllables/sec modulated speech (8 frames peak @ 0.24f, 4 frames dip @ 0.06f -> 12 frames = 240ms? Let's use 5 frames peak + 3 frames dip = 160ms = 6.25 Hz!)
        val fastSyllableFrames = 450 // 9.0 s @ 20 ms/frame
        val fastSpeech = FloatArray(fastSyllableFrames * CHUNK_FRAME_SAMPLES)
        for (f in 0 until fastSyllableFrames) {
            val phase = f % 8
            val amp = if (phase in 1..4) 0.24f else 0.05f
            val base = f * CHUNK_FRAME_SAMPLES
            for (i in 0 until CHUNK_FRAME_SAMPLES) {
                fastSpeech[base + i] = if (i % 2 == 0) amp else -amp
            }
        }
        val rmsFast = computeFrameRms(fastSpeech, 0, fastSpeech.size)
        val maskFast = buildSilenceMask(rmsFast, adaptiveSilenceThreshold(rmsFast, defaultCfg.silenceThreshold))
        assertTrue(
            "Fast 6.25 Hz syllable modulation must earn a positive pause-margin bonus",
            estimateRapidCadencePauseBonusFrames(rmsFast, maskFast, rmsFast.size) > 0
        )

        // At 9.0s, requiredPauseFrames is ~27 frames (540 ms), + 3 cadence bonus = 30 frames (600 ms).
        // A brief 540 ms catch-breath must NOT chop the fast speaker mid-clause, while a full 680 ms pause DOES cut.
        val withBriefCatchBreath = concat(fastSpeech, tone(0.54, silence), tone(1.0, speech))
        assertNull(
            "540 ms catch-breath during rapid-cadence speech at 9s should be held intact",
            findStreamingCut(withBriefCatchBreath, withBriefCatchBreath.size, defaultCfg)
        )

        val withFullClausePause = concat(fastSpeech, tone(0.68, silence), tone(1.0, speech))
        val cleanCut = findStreamingCut(withFullClausePause, withFullClausePause.size, defaultCfg)
        assertNotNull(cleanCut)
        assertEquals(CutKind.CONFIDENT_PAUSE, cleanCut!!.cutKind)
    }

    @Test
    fun `valley search prefers smooth inter-word prosodic valley over steep stop-consonant cliff and retains 1_2s overlap`() {
        val cfg = DictationChunkSettings() // max = 18s (288,000 samples)
        val totalFrames = 920 // 18.4 s -> triggers forced VALLEY_OVERLAP cut
        // Base continuous voiced energy (0.24f) everywhere — zero silent frames!
        val frameAmps = FloatArray(totalFrames) { 0.24f }

        // Place a sharp mid-word stop-consonant cliff at 15.6s (frame 780..785: 0.32f wall -> 0.045f -> 0.32f wall)
        for (f in 777..779) frameAmps[f] = 0.32f
        for (f in 780..785) frameAmps[f] = 0.045f
        for (f in 786..788) frameAmps[f] = 0.32f

        // Place a smooth, gradual inter-word prosodic valley at 16.8s (frames 837..848: gentle taper into 0.045f)
        frameAmps[837] = 0.14f
        frameAmps[838] = 0.10f
        frameAmps[839] = 0.07f
        for (f in 840..845) frameAmps[f] = 0.045f
        frameAmps[846] = 0.07f
        frameAmps[847] = 0.10f
        frameAmps[848] = 0.14f

        val samples = FloatArray(totalFrames * CHUNK_FRAME_SAMPLES)
        for (f in 0 until totalFrames) {
            val amp = frameAmps[f]
            val base = f * CHUNK_FRAME_SAMPLES
            for (i in 0 until CHUNK_FRAME_SAMPLES) {
                samples[base + i] = if (i % 2 == 0) amp else -amp
            }
        }

        val cut = findStreamingCut(samples, samples.size, cfg)
        assertNotNull(cut)
        assertEquals(CutKind.VALLEY_OVERLAP, cut!!.cutKind)
        assertEquals(FORCED_CUT_OVERLAP_SAMPLES, cut.index - cut.resumeIndex)
        val valleyStartSample = 839 * CHUNK_FRAME_SAMPLES
        val valleyEndSample = 847 * CHUNK_FRAME_SAMPLES
        assertTrue(
            "Expected valley cut (${cut.index}) inside smooth inter-word valley [$valleyStartSample..$valleyEndSample], avoiding sharp plosive cliff",
            cut.index in valleyStartSample..valleyEndSample
        )
    }

    @Test
    fun `short dictations under 6 seconds stay single-pass and offline valley cuts retain bilateral overlap`() {
        val defaultCfg = DictationChunkSettings()
        val short5s = concat(tone(4.8, speech), tone(0.9, silence))
        assertNull("Short < 6s dictation must never split live", findStreamingCut(short5s, short5s.size, defaultCfg))

        val singlePass17s = tone(17.5, speech)
        val singleRanges = computeSmartChunkRanges(
            samples = singlePass17s,
            maxChunkSamples = defaultCfg.effectiveMaxChunkSamples,
            minChunkSamples = defaultCfg.effectiveOfflineMinChunkSamples,
            overlapSamplesOnValleyCut = FORCED_CUT_OVERLAP_SAMPLES
        )
        assertEquals(1, singleRanges.size)

        // 32s of non-stop speech -> offline computeSmartChunkRanges produces 2 chunks with 1.20s bilateral overlap
        val nonStop32s = tone(32.0, speech)
        val overlapRanges = computeSmartChunkRanges(
            samples = nonStop32s,
            maxChunkSamples = defaultCfg.effectiveMaxChunkSamples,
            minChunkSamples = defaultCfg.effectiveOfflineMinChunkSamples,
            overlapSamplesOnValleyCut = FORCED_CUT_OVERLAP_SAMPLES
        )
        assertEquals(2, overlapRanges.size)
        assertEquals(CutKind.VALLEY_OVERLAP, overlapRanges[1].cutKindBefore)
        val overlapSamples = overlapRanges[0].endExclusive - overlapRanges[1].start
        assertTrue(
            "Offline valley cut must retain >= 0.9s bilateral overlap (got $overlapSamples samples)",
            overlapSamples >= 14_400
        )
        assertTrue(overlapRanges.all { (it.endExclusive - it.start) <= defaultCfg.effectiveMaxChunkSamples })
    }
}
