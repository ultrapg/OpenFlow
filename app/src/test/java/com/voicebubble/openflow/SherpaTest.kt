package com.voicebubble.openflow

import org.junit.Test
import com.k2fsa.sherpa.onnx.*

class SherpaTest {
    @Test
    fun testInitialization() {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                tokens = "tokens.txt",
                numThreads = 4,
                debug = false,
                provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )
        
        config.modelConfig.canary = OfflineCanaryModelConfig(
            encoder = "encoder.int8.onnx",
            decoder = "decoder.int8.onnx",
            srcLang = "de",
            tgtLang = "de",
            usePnc = true
        )
        config.modelConfig.modelType = "canary"

        println("Config created: ${config.modelConfig.modelType}")
        
        try {
            val recognizer = OfflineRecognizer(null, config)
            println("Loaded successfully")
        } catch (e: Exception) {
            println("Exception: ${e.message}")
        } catch (e: Error) {
            println("Error: ${e.message}")
        }
    }
}
