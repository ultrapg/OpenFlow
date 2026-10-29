package com.voicebubble.openflow.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.core.speech.findStreamingCut
import com.voicebubble.openflow.settings.DictationChunkSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sqrt

class AudioRecordingController {
    companion object {
        private const val SAMPLE_RATE = 16000
        // 40 ms read blocks (640 samples) keep pcmBuffer fresh without large blocking-read latency
        private const val READ_CHUNK_SHORTS = 640
        // 75 ms post-tap grace window captures the final syllable still in flight when the user taps Stop mid-word,
        // and is skipped entirely (0 ms) when the trailing 80 ms of audio is already silent.
        private const val POST_STOP_TAIL_GRACE_MS = 75L
        private const val TRAILING_SILENCE_CHECK_SAMPLES = 1_280 // 80 ms @ 16 kHz
        private const val TRAILING_SPEECH_RMS_THRESHOLD = 0.008f
        // Initial capacity: 15 seconds (240,000 floats = ~960 KB) — avoids repeated small allocations
        private const val INITIAL_CAPACITY_SAMPLES = SAMPLE_RATE * 15
        // Hard safety ceiling: 5 minutes at 16 kHz (4,800,000 floats = ~19.2 MB) to prevent unbounded RAM growth
        private const val MAX_RECORDING_SAMPLES = SAMPLE_RATE * 300

        /**
         * Returns `true` if the trailing [windowSamples] of [samples] (up to [size]) still contain
         * active speech energy (`RMS >= threshold`), indicating we should wait [POST_STOP_TAIL_GRACE_MS]
         * before stopping `AudioRecord`. Returns `false` if the user already paused before tapping Stop.
         */
        internal fun hasTrailingSpeechEnergy(
            samples: FloatArray?,
            size: Int,
            windowSamples: Int = TRAILING_SILENCE_CHECK_SAMPLES,
            threshold: Float = TRAILING_SPEECH_RMS_THRESHOLD
        ): Boolean {
            if (samples == null || size <= 0) return false
            val validSize = min(size, samples.size)
            val start = (validSize - windowSamples).coerceAtLeast(0)
            val count = validSize - start
            if (count <= 0) return false
            var sumSq = 0.0
            for (i in start until validSize) {
                val v = samples[i].toDouble()
                sumSq += v * v
            }
            val rms = sqrt(sumSq / count).toFloat()
            return rms >= threshold
        }
    }

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var recordingJob: Job? = null

    private val bufferLock = Any()
    private var pcmBuffer: FloatArray? = null
    @Volatile
    private var pcmSize: Int = 0

    /**
     * Lock-free snapshot of the number of unread PCM samples currently buffered in [pcmBuffer].
     * Used by [com.voicebubble.openflow.core.speech.StreamingDictationSession] to sleep until enough
     * audio has accumulated to form a valid chunk without waking a background thread every 150 ms.
     */
    fun bufferedSampleCount(): Int = pcmSize

    /**
     * Non-destructively copies up to [maxSamples] leading samples from the live capture buffer without
     * advancing or modifying [pcmBuffer]. Used to run early `Ear` spoken-language identification while
     * the user is still speaking their first sentence before any pause cut occurs.
     */
    fun peekLeadingSamples(maxSamples: Int): FloatArray? {
        if (maxSamples <= 0) return null
        synchronized(bufferLock) {
            val buf = pcmBuffer ?: return null
            val copyLen = min(pcmSize, min(maxSamples, buf.size))
            if (copyLen <= 0) return null
            if (jvmFreeHeadroomBytes() < copyLen.toLong() * 4L + (12L * 1024L * 1024L)) {
                return null
            }
            return try {
                buf.copyOfRange(0, copyLen)
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }

    private val _rmsLevel = MutableStateFlow(0.0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    @Volatile
    var isRecording: Boolean = false
        private set

    private val controllerMutex = Mutex()

    suspend fun release() = withContext(Dispatchers.IO) {
        controllerMutex.withLock {
            releaseNow()
        }
    }

    fun releaseNow() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                audioRecord?.stop()
            }
        } catch (_: Throwable) {
        }
        synchronized(bufferLock) {
            pcmBuffer = null
            pcmSize = 0
        }
        releaseInternal()
    }

    @SuppressLint("MissingPermission")
    suspend fun startRecording(coroutineScope: CoroutineScope): Boolean = withContext(Dispatchers.IO) {
        controllerMutex.withLock {
            isRecording = false
            try {
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                    audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    audioRecord?.stop()
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            recordingJob?.cancelAndJoin()
            recordingJob = null
            releaseInternal()

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize * 3, 16384) else 16384

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (t: Throwable) {
                DebugLogManager.logWarning("AudioRecordingController", "Failed to create AudioRecord: ${t.message}", t)
                return@withLock false
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                DebugLogManager.logWarning("AudioRecordingController", "AudioRecord failed to initialize (state=${record.state})")
                try {
                    record.release()
                } catch (_: Throwable) {
                }
                return@withLock false
            }

            audioRecord = record
            val bufferAllocated = synchronized(bufferLock) {
                pcmSize = 0
                pcmBuffer = try {
                    FloatArray(INITIAL_CAPACITY_SAMPLES)
                } catch (_: OutOfMemoryError) {
                    System.gc()
                    try {
                        FloatArray(SAMPLE_RATE * 5)
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                }
                pcmBuffer != null
            }
            if (!bufferAllocated) {
                DebugLogManager.logWarning("AudioRecordingController", "Insufficient JVM heap to allocate PCM recording buffer")
                releaseInternal()
                return@withLock false
            }

            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
                    noiseSuppressor?.enabled = true
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(record.audioSessionId)
                    agc?.enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    DebugLogManager.logWarning("AudioRecordingController", "AudioRecord did not enter RECORDSTATE_RECORDING")
                    synchronized(bufferLock) {
                        pcmBuffer = null
                        pcmSize = 0
                    }
                    releaseInternal()
                    return@withLock false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                synchronized(bufferLock) {
                    pcmBuffer = null
                    pcmSize = 0
                }
                releaseInternal()
                return@withLock false
            }

            isRecording = true
            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(READ_CHUNK_SHORTS)

                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readCount < 0) {
                        // ERROR_DEAD_OBJECT / ERROR_INVALID_OPERATION / ERROR_BAD_VALUE: the record object is no
                        // longer usable (e.g. mic taken over by another app). Bail out instead of busy-spinning
                        // a CPU core on a permanently failing read().
                        isRecording = false
                        DebugLogManager.logWarning(
                            "AudioRecordingController",
                            "AudioRecord.read() failed with code $readCount; ending capture loop"
                        )
                        break
                    }
                    if (readCount > 0) {
                        val sumSquares = appendSamplesToBuffer(shortBuffer, readCount)
                        val rms = sqrt(sumSquares / readCount).toFloat()
                        _rmsLevel.value = (rms * 5.0f).coerceIn(0.0f, 1.0f)
                    }
                }
            }
            true
        }
    }

    private fun jvmFreeHeadroomBytes(): Long {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
        return (rt.maxMemory() - used).coerceAtLeast(0L)
    }

    private fun appendSamplesToBuffer(shortBuffer: ShortArray, readCount: Int): Double {
        var sumSquares = 0.0
        synchronized(bufferLock) {
            val currentBuf = pcmBuffer ?: FloatArray(INITIAL_CAPACITY_SAMPLES).also { pcmBuffer = it }
            val availableSpace = MAX_RECORDING_SAMPLES - pcmSize
            val toCopy = min(readCount, availableSpace)
            if (toCopy > 0) {
                val requiredSize = pcmSize + toCopy
                val targetBuf = if (requiredSize > currentBuf.size) {
                    // Grow by +20s up to MAX_RECORDING_SAMPLES, checking JVM heap headroom first to avoid 2x copyOf OOM spikes
                    val desiredCapacity = min(
                        MAX_RECORDING_SAMPLES,
                        maxOf(requiredSize, currentBuf.size + (SAMPLE_RATE * 20))
                    )
                    val freeBytes = jvmFreeHeadroomBytes()
                    val neededBytes = desiredCapacity.toLong() * 4L + (20L * 1024L * 1024L)
                    val safeCapacity = if (freeBytes >= neededBytes) {
                        desiredCapacity
                    } else if (freeBytes >= requiredSize.toLong() * 4L + (12L * 1024L * 1024L)) {
                        min(MAX_RECORDING_SAMPLES, maxOf(requiredSize, currentBuf.size + (SAMPLE_RATE * 5)))
                    } else {
                        -1
                    }

                    if (safeCapacity > currentBuf.size) {
                        try {
                            currentBuf.copyOf(safeCapacity).also { pcmBuffer = it }
                        } catch (_: OutOfMemoryError) {
                            DebugLogManager.logWarning(
                                "AudioRecordingController",
                                "JVM heap full while growing audio buffer at $pcmSize samples; capping recording buffer"
                            )
                            currentBuf
                        }
                    } else {
                        DebugLogManager.logWarning(
                            "AudioRecordingController",
                            "Low JVM heap headroom (${freeBytes / (1024L * 1024L)} MB) at $pcmSize samples; capping recording buffer before OOM"
                        )
                        currentBuf
                    }
                } else {
                    currentBuf
                }

                val actualCopy = min(toCopy, targetBuf.size - pcmSize)
                for (i in 0 until actualCopy) {
                    val normalized = shortBuffer[i] / 32768.0f
                    sumSquares += normalized * normalized
                    targetBuf[pcmSize + i] = normalized
                }
                pcmSize += actualCopy
            } else {
                for (i in 0 until readCount) {
                    val normalized = shortBuffer[i] / 32768.0f
                    sumSquares += normalized * normalized
                }
            }
        }
        return sumSquares
    }

    /**
     * Live "transcribe while recording" support: asks the pause detector whether the audio captured so far
     * contains a finished chunk (see [findStreamingCut]). If so, the chunk `[0, cut.index)` is returned and
     * the live buffer advances to `cut.resumeIndex` (which keeps natural leading silence inside the pause for
     * the next chunk without duplicating any speech samples). Chunks without usable speech (long leading
     * silence) are discarded and `null` is returned.
     */
    fun extractStreamingChunk(config: DictationChunkSettings): FloatArray? {
        synchronized(bufferLock) {
            val buf = pcmBuffer ?: return null
            val size = pcmSize
            val cut = findStreamingCut(buf, size, config) ?: return null
            val index = cut.index.coerceIn(0, size)
            if (index <= 0) return null
            val resumeAt = cut.resumeIndex.coerceIn(1, index)
            val chunk = if (cut.hasSpeech) {
                if (jvmFreeHeadroomBytes() < index.toLong() * 4L + (12L * 1024L * 1024L)) {
                    // Leave audio in the single contiguous live buffer until JVM heap headroom recovers
                    return null
                }
                try {
                    buf.copyOfRange(0, index)
                } catch (_: OutOfMemoryError) {
                    // Leave the audio in the live buffer; the tail/offline chunker still handles it at stop
                    return null
                }
            } else {
                null
            }
            val remaining = size - resumeAt
            // If an earlier long stretch grew pcmBuffer well past INITIAL_CAPACITY_SAMPLES, compact back down once drained
            if (buf.size > INITIAL_CAPACITY_SAMPLES * 2 &&
                remaining <= INITIAL_CAPACITY_SAMPLES / 2 &&
                jvmFreeHeadroomBytes() >= INITIAL_CAPACITY_SAMPLES.toLong() * 4L + (16L * 1024L * 1024L)
            ) {
                try {
                    val compacted = FloatArray(INITIAL_CAPACITY_SAMPLES)
                    if (remaining > 0) {
                        System.arraycopy(buf, resumeAt, compacted, 0, remaining)
                    }
                    pcmBuffer = compacted
                    pcmSize = remaining
                    return chunk
                } catch (_: OutOfMemoryError) {
                }
            }
            System.arraycopy(buf, resumeAt, buf, 0, remaining)
            pcmSize = remaining
            return chunk
        }
    }

    suspend fun stopRecording(): FloatArray = withContext(Dispatchers.IO) {
        controllerMutex.withLock {
            isRecording = false
            val activeRecord = audioRecord
            if (activeRecord?.state == AudioRecord.STATE_INITIALIZED &&
                activeRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                val stillSpeakingAtStop = synchronized(bufferLock) {
                    hasTrailingSpeechEnergy(pcmBuffer, pcmSize)
                }
                if (stillSpeakingAtStop) {
                    delay(POST_STOP_TAIL_GRACE_MS)
                }
                try {
                    activeRecord.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
            }

            recordingJob?.cancelAndJoin()
            recordingJob = null

            // Drain any remaining unread frames still sitting in AudioRecord's hardware buffer after stop()
            if (activeRecord?.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    val drainBuf = ShortArray(READ_CHUNK_SHORTS)
                    var drainLoops = 0
                    while (drainLoops < 16) {
                        val drained = activeRecord.read(drainBuf, 0, drainBuf.size, AudioRecord.READ_NON_BLOCKING)
                        if (drained <= 0) break
                        appendSamplesToBuffer(drainBuf, drained)
                        drainLoops++
                    }
                } catch (_: Throwable) {
                }
            }

            releaseInternal()
            _rmsLevel.value = 0.0f

            synchronized(bufferLock) {
                val buf = pcmBuffer
                val size = pcmSize
                pcmBuffer = null
                pcmSize = 0
                if (buf == null || size <= 0) {
                    FloatArray(0)
                } else if (size == buf.size) {
                    buf
                } else {
                    try {
                        buf.copyOf(size)
                    } catch (_: OutOfMemoryError) {
                        // Under extreme JVM heap pressure, return the existing buffer directly
                        buf
                    }
                }
            }
        }
    }

    private fun releaseInternal() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        noiseSuppressor = null
        try {
            agc?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        agc = null
        audioRecord = null
    }
}
