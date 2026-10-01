package com.voicebubble.openflow.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class AudioRecordingController {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val audioChunks = mutableListOf<FloatArray>()

    private val _rmsLevel = MutableStateFlow(0.0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()
    
    private val controllerMutex = Mutex()

    suspend fun release() {
        controllerMutex.withLock {
            try {
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            recordingJob?.cancelAndJoin()
            recordingJob = null
            releaseInternal()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startRecording(coroutineScope: CoroutineScope) {
        controllerMutex.withLock {
            try {
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            recordingJob?.cancelAndJoin()
            recordingJob = null
            releaseInternal()

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize * 2, 4096)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioChunks.clear()
            
            audioRecord?.let { record ->
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
            }
            
            try {
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withLock
            }

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize / 2)

                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        var sumSquares = 0.0
                        val floatChunk = FloatArray(readCount)
                        for (i in 0 until readCount) {
                            val sample = buffer[i]
                            val normalized = sample / 32768.0f
                            sumSquares += normalized * normalized
                            floatChunk[i] = normalized
                        }
                        audioChunks.add(floatChunk)

                        val rms = sqrt(sumSquares / readCount).toFloat()
                        _rmsLevel.value = (rms * 5.0f).coerceIn(0.0f, 1.0f)
                    }
                }
            }
        }
    }

    suspend fun stopRecording(): FloatArray {
        return controllerMutex.withLock {
            try {
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
            
            recordingJob?.cancelAndJoin()
            recordingJob = null
            
            releaseInternal()

            _rmsLevel.value = 0.0f

            val totalSize = audioChunks.sumOf { it.size }
            val floatArray = FloatArray(totalSize)
            var offset = 0
            for (chunk in audioChunks) {
                chunk.copyInto(floatArray, offset)
                offset += chunk.size
            }
            audioChunks.clear()

            floatArray
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
