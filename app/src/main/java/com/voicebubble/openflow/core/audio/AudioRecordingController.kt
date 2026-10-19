package com.voicebubble.openflow.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.voicebubble.openflow.core.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.sqrt

class AudioRecordingController {
    companion object {
        private const val SAMPLE_RATE = 16000
        // Initial capacity: 15 seconds (240,000 floats = ~960 KB) — avoids repeated small allocations
        private const val INITIAL_CAPACITY_SAMPLES = SAMPLE_RATE * 15
        // Hard safety ceiling: 5 minutes at 16 kHz (4,800,000 floats = ~19.2 MB) to prevent unbounded RAM growth
        private const val MAX_RECORDING_SAMPLES = SAMPLE_RATE * 300
    }

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var recordingJob: Job? = null

    private val bufferLock = Any()
    private var pcmBuffer: FloatArray? = null
    private var pcmSize: Int = 0

    private val _rmsLevel = MutableStateFlow(0.0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val controllerMutex = Mutex()

    suspend fun release() {
        controllerMutex.withLock {
            releaseNow()
        }
    }

    fun releaseNow() {
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
    suspend fun startRecording(coroutineScope: CoroutineScope): Boolean {
        return controllerMutex.withLock {
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
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize * 2, 4096) else 8192

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e: Exception) {
                DebugLogManager.logWarning("AudioRecordingController", "Failed to create AudioRecord: ${e.message}", e)
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
            synchronized(bufferLock) {
                pcmBuffer = FloatArray(INITIAL_CAPACITY_SAMPLES)
                pcmSize = 0
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

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(bufferSize / 2)

                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readCount > 0) {
                        var sumSquares = 0.0
                        synchronized(bufferLock) {
                            val currentBuf = pcmBuffer ?: FloatArray(INITIAL_CAPACITY_SAMPLES).also { pcmBuffer = it }
                            val availableSpace = MAX_RECORDING_SAMPLES - pcmSize
                            val toCopy = min(readCount, availableSpace)
                            if (toCopy > 0) {
                                val requiredSize = pcmSize + toCopy
                                val targetBuf = if (requiredSize > currentBuf.size) {
                                    // Grow by 1.5x (or +30s) up to MAX_RECORDING_SAMPLES to avoid 2x heap spikes
                                    val grownCapacity = min(
                                        MAX_RECORDING_SAMPLES,
                                        maxOf(requiredSize, currentBuf.size + (SAMPLE_RATE * 20))
                                    )
                                    try {
                                        currentBuf.copyOf(grownCapacity).also { pcmBuffer = it }
                                    } catch (oom: OutOfMemoryError) {
                                        DebugLogManager.logWarning(
                                            "AudioRecordingController",
                                            "JVM heap full while growing audio buffer at $pcmSize samples; capping recording buffer"
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

                        val rms = sqrt(sumSquares / readCount).toFloat()
                        _rmsLevel.value = (rms * 5.0f).coerceIn(0.0f, 1.0f)
                    }
                }
            }
            true
        }
    }

    suspend fun stopRecording(): FloatArray {
        return controllerMutex.withLock {
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
