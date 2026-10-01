package com.voicebubble.openflow.core.speech

import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SherpaSpeechEngine(
    private val modelType: String,
    private val modelPath: String,
    private val tokensPath: String,
    private val hotwordsPath: String = "",
    private val lidPath: String? = null,
    private var defaultLang: String = "de"
) {
    private val decodeMutex = Mutex()
    
    private var activeRecognizer: OfflineRecognizer? = null
    private var lidModel: SpokenLanguageIdentification? = null
    private var isReleased = false
    
    private var currentLang: String = ""

    fun initialize() {
        isReleased = false
        
        if (lidPath != null) {
            val config = SpokenLanguageIdentificationConfig(
                whisper = SpokenLanguageIdentificationWhisperConfig(
                    encoder = "$lidPath/tiny-encoder.int8.onnx",
                    decoder = "$lidPath/tiny-decoder.int8.onnx"
                )
            )
            lidModel = SpokenLanguageIdentification(null, config)
        }
        
        initializeRecognizer(defaultLang)
    }
    
    private fun initializeRecognizer(lang: String) {
        activeRecognizer?.release()
        
        val baseModelConfig = OfflineModelConfig(
            tokens = tokensPath,
            numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
            debug = false,
            provider = "cpu"
        )
        if (modelType == "nemo-canary-180m") {
            baseModelConfig.canary = OfflineCanaryModelConfig(
                encoder = "$modelPath/encoder.int8.onnx",
                decoder = "$modelPath/decoder.int8.onnx",
                srcLang = lang,
                tgtLang = lang,
                usePnc = true
            )
            baseModelConfig.modelType = "canary"
        } else {
            baseModelConfig.transducer = OfflineTransducerModelConfig(
                encoder = "$modelPath/encoder.int8.onnx",
                decoder = "$modelPath/decoder.int8.onnx",
                joiner = "$modelPath/joiner.int8.onnx"
            )
            baseModelConfig.modelType = "nemo_transducer"
        }

        val configG = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = baseModelConfig,
            decodingMethod = "greedy_search"
        )
        activeRecognizer = OfflineRecognizer(null, configG)
        currentLang = lang
    }

    suspend fun transcribe(samples: FloatArray, onProgress: ((Int, Int) -> Unit)? = null): String = withContext(Dispatchers.Default) {
        decodeMutex.withLock {
            if (isReleased) return@withLock ""
            if (samples.isEmpty()) return@withLock ""
            
            val sampleRate = 16000
            
            if (lidModel != null) {
                val stream = lidModel!!.createStream()
                val maxLidSamples = sampleRate * 30
                val lidSamples = if (samples.size > maxLidSamples) samples.copyOfRange(0, maxLidSamples) else samples
                stream.acceptWaveform(lidSamples, sampleRate)
                val detected = lidModel!!.compute(stream).trim().lowercase()
                stream.release()
                
                var targetLang = "de"
                if (detected == "en") targetLang = "en"
                
                if (targetLang != currentLang) {
                    initializeRecognizer(targetLang)
                }
            } else if (defaultLang != currentLang) {
                initializeRecognizer(defaultLang)
            }
            
            val rec = activeRecognizer ?: return@withLock ""

            val minChunkSize = 5 * sampleRate
            val maxChunkSize = 12 * sampleRate
            val silenceWindow = (0.2 * sampleRate).toInt()

            val chunks = mutableListOf<FloatArray>()
            var currentIndex = 0
            while (currentIndex < samples.size) {
                val remaining = samples.size - currentIndex
                if (remaining <= maxChunkSize) {
                    chunks.add(samples.copyOfRange(currentIndex, samples.size))
                    break
                }
                
                var splitPoint = currentIndex + maxChunkSize
                var bestSilencePoint = -1
                var lowestEnergy = Float.MAX_VALUE
                
                val searchStart = currentIndex + minChunkSize
                val searchEnd = currentIndex + maxChunkSize - silenceWindow
                for (i in searchStart..searchEnd step 1000) {
                    var energy = 0f
                    for (j in 0 until silenceWindow) {
                        val s = samples[i + j]
                        energy += s * s
                    }
                    if (energy < lowestEnergy) {
                        lowestEnergy = energy
                        bestSilencePoint = i + silenceWindow / 2
                    }
                }
                
                if (bestSilencePoint != -1) {
                    splitPoint = bestSilencePoint
                }
                
                chunks.add(samples.copyOfRange(currentIndex, splitPoint))
                currentIndex = splitPoint
            }

            val stringBuilder = java.lang.StringBuilder()
            for ((index, chunk) in chunks.withIndex()) {
                if (isReleased || !coroutineContext.isActive) break
                onProgress?.invoke(index + 1, chunks.size)
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(chunk, sampleRate)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text.isNotEmpty()) {
                        if (stringBuilder.isNotEmpty()) stringBuilder.append(" ")
                        stringBuilder.append(text)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    stream.release()
                }
            }

            stringBuilder.toString()

        }
    }

    fun release() {
        isReleased = true
        GlobalScope.launch(Dispatchers.Default) {
            decodeMutex.withLock {
                activeRecognizer?.release()
                activeRecognizer = null
                lidModel?.release()
                lidModel = null
            }
        }
    }
}
