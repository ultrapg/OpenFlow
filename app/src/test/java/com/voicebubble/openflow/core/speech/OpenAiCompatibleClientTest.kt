/*
 * Copyright 2026 OpenFlow Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.voicebubble.openflow.core.speech

import ai.desertant.voz.Voz
import com.voicebubble.openflow.settings.GemmaPromptPreset
import com.voicebubble.openflow.settings.LlmThinkingEffort
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class OpenAiCompatibleClientTest {

    private class MiniHttpTestServer private constructor(
        private val rawHandler: (method: String, path: String, headers: Map<String, String>, bodyBytes: ByteArray) -> Pair<Int, String>,
        @Suppress("UNUSED_PARAMETER") rawMarker: Boolean
    ) : AutoCloseable {
        constructor(
            textHandler: (method: String, path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>
        ) : this({ method, path, headers, bytes ->
            textHandler(method, path, headers, String(bytes, StandardCharsets.UTF_8))
        }, true)

        companion object {
            fun binary(
                handler: (method: String, path: String, headers: Map<String, String>, bodyBytes: ByteArray) -> Pair<Int, String>
            ): MiniHttpTestServer = MiniHttpTestServer(handler, true)
        }
        private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        @Volatile
        private var running = true

        private val worker = Thread {
            while (running) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    break
                }
                try {
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val headerBytes = ArrayList<Byte>(512)
                    var prev3 = 0
                    var prev2 = 0
                    var prev1 = 0
                    while (true) {
                        val b = input.read()
                        if (b == -1) break
                        headerBytes.add(b.toByte())
                        if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                            break
                        }
                        prev3 = prev2
                        prev2 = prev1
                        prev1 = b
                    }
                    val headerText = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1)
                    val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        val parts = lines.first().split(' ')
                        val method = parts.getOrElse(0) { "GET" }
                        val path = parts.getOrElse(1) { "/" }
                        val headers = linkedMapOf<String, String>()
                        for (i in 1 until lines.size) {
                            val colon = lines[i].indexOf(':')
                            if (colon > 0) {
                                headers[lines[i].substring(0, colon).trim().lowercase(Locale.ROOT)] =
                                    lines[i].substring(colon + 1).trim()
                            }
                        }
                        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                        val bodyBytes = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(bodyBytes, read, contentLength - read)
                            if (n <= 0) break
                            read += n
                        }
                        val capturedBytes = bodyBytes.copyOf(read)
                        val (status, respBody) = rawHandler(method, path, headers, capturedBytes)
                        val respBytes = respBody.toByteArray(StandardCharsets.UTF_8)
                        val statusText = when (status) {
                            200 -> "OK"
                            400 -> "Bad Request"
                            401 -> "Unauthorized"
                            else -> "Error"
                        }
                        val respHeaders = (
                            "HTTP/1.1 $status $statusText\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${respBytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.ISO_8859_1)
                        socket.getOutputStream().apply {
                            write(respHeaders)
                            write(respBytes)
                            flush()
                        }
                    }
                } finally {
                    runCatching { socket.close() }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            running = false
            runCatching { serverSocket.close() }
            runCatching { worker.join(500) }
        }
    }

    @Test
    fun `resolveChatCompletionsUrl and resolveModelsUrl normalize common endpoint formats`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiCompatibleClient.resolveChatCompletionsUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            OpenAiCompatibleClient.resolveModelsUrl("https://api.openai.com/v1/")
        )

        // Bare LAN host + port defaults to /v1/...
        assertEquals(
            "http://192.168.1.42:11434/v1/chat/completions",
            OpenAiCompatibleClient.resolveChatCompletionsUrl("http://192.168.1.42:11434")
        )
        assertEquals(
            "http://192.168.1.42:11434/v1/models",
            OpenAiCompatibleClient.resolveModelsUrl("http://192.168.1.42:11434")
        )

        // Subpath version prefix (e.g., Gemini OpenAI compatibility proxy)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenAiCompatibleClient.resolveChatCompletionsUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            OpenAiCompatibleClient.resolveModelsUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        )

        // Explicit full chat completions URL stays verbatim
        assertEquals(
            "https://proxy.example.com/custom/v1/chat/completions",
            OpenAiCompatibleClient.resolveChatCompletionsUrl("https://proxy.example.com/custom/v1/chat/completions")
        )
        assertEquals(
            "https://proxy.example.com/custom/v1/models",
            OpenAiCompatibleClient.resolveModelsUrl("https://proxy.example.com/custom/v1/chat/completions")
        )

        assertTrue(OpenAiCompatibleClient.isValidHttpUrl("http://127.0.0.1:11434/v1"))
        assertTrue(OpenAiCompatibleClient.isValidHttpUrl("https://api.openai.com/v1"))
        assertFalse(OpenAiCompatibleClient.isValidHttpUrl(""))
        assertFalse(OpenAiCompatibleClient.isValidHttpUrl("ftp://example.com"))
        assertFalse(OpenAiCompatibleClient.isValidHttpUrl("not a url"))
    }

    @Test
    fun `extractAssistantContent and parseModelIdsFromResponse handle standard and multimodal shapes`() {
        val standard = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put("content", "Hello, world!")
                )
            )
        ).toString()
        assertEquals("Hello, world!", OpenAiCompatibleClient.extractAssistantContent(standard))

        val arrayParts = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", "First part "))
                            .put(JSONObject().put("type", "text").put("text", "second part."))
                    )
                )
            )
        ).toString()
        assertEquals("First part second part.", OpenAiCompatibleClient.extractAssistantContent(arrayParts))

        val modelsJson = JSONObject().put(
            "data",
            JSONArray()
                .put(JSONObject().put("id", "gpt-4o-mini"))
                .put(JSONObject().put("id", "text-embedding-3-small"))
                .put(JSONObject().put("id", "whisper-1"))
                .put(JSONObject().put("id", "claude-3-5-haiku"))
                .put(JSONObject().put("id", "tts-1"))
        ).toString()
        assertEquals(
            listOf("claude-3-5-haiku", "gpt-4o-mini"),
            OpenAiCompatibleClient.parseModelIdsFromResponse(modelsJson)
        )
    }

    @Test
    fun `mock HTTP server supports fetchModels, testConnection, processTranscript, temperature retry, and safe fallback`() = runBlocking {
        val requestCount = AtomicInteger(0)
        var lastAuthHeader: String? = null
        var lastRequestJson: JSONObject? = null
        var forceSamplingErrorOnFirstTry = false
        var forceStatusCode = 200
        var customAssistantReply: String? = null

        MiniHttpTestServer { _, path, headers, body ->
            lastAuthHeader = headers["authorization"]
            when (path) {
                "/v1/models" -> {
                    val resp = JSONObject().put(
                        "data",
                        JSONArray()
                            .put(JSONObject().put("id", "qwen3:4b"))
                            .put(JSONObject().put("id", "gpt-4o-mini"))
                            .put(JSONObject().put("id", "text-embedding-ada-002"))
                    ).toString()
                    200 to resp
                }

                "/v1/chat/completions" -> {
                    val attempt = requestCount.incrementAndGet()
                    val reqJson = JSONObject(body)
                    lastRequestJson = reqJson

                    if (forceStatusCode != 200) {
                        val err = JSONObject()
                            .put("error", JSONObject().put("message", "Invalid API key"))
                            .toString()
                        forceStatusCode to err
                    } else if (forceSamplingErrorOnFirstTry && attempt == 1 && reqJson.has("temperature")) {
                        val err = JSONObject()
                            .put(
                                "error",
                                JSONObject().put("message", "Unsupported parameter: 'temperature' is not supported for this model")
                            )
                            .toString()
                        400 to err
                    } else {
                        val replyContent = customAssistantReply
                            ?: "Okay, let's deploy PostgreSQL with OpenFlow at 14:30."
                        val ok = JSONObject().put(
                            "choices",
                            JSONArray().put(
                                JSONObject().put(
                                    "message",
                                    JSONObject().put("role", "assistant").put("content", replyContent)
                                )
                            )
                        ).toString()
                        200 to ok
                    }
                }

                else -> 404 to "{}"
            }
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1"

            // 1. Fetch models
            val modelsResult = OpenAiCompatibleClient.fetchAvailableModels(
                baseUrl = baseUrl,
                apiKey = "sk-test-123",
                timeoutSeconds = 10
            )
            assertTrue(modelsResult.isSuccess)
            assertEquals(listOf("gpt-4o-mini", "qwen3:4b"), modelsResult.getOrThrow())
            assertEquals("Bearer sk-test-123", lastAuthHeader)

            // 2. Test connection
            val testResult = OpenAiCompatibleClient.testConnection(
                baseUrl = baseUrl,
                apiKey = "sk-test-123",
                model = "gpt-4o-mini",
                timeoutSeconds = 10
            )
            assertTrue("Expected testConnection success, got: ${testResult.message}", testResult.success)
            assertEquals(200, testResult.httpStatus)
            assertTrue(testResult.latencyMs >= 1L)

            // 3. Process transcript with chatter prefix + custom vocabulary + thinking tag stripping
            customAssistantReply = "<think>fixing grammar</think>\nSure, here is the cleaned text:\nWe use postgresql inside openflow on Monday and Tuesday."
            val spoken = "uh we use postgresql inside openflow um on monday and tuesday"
            val outcome = OpenAiCompatibleClient.processTranscript(
                text = spoken,
                baseUrl = baseUrl,
                apiKey = "sk-test-123",
                model = "gpt-4o-mini",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                customPrompt = "",
                languageCode = "en",
                customVocabulary = listOf("PostgreSQL", "OpenFlow"),
                timeoutSeconds = 10
            )
            assertTrue(outcome.succeeded)
            assertTrue(outcome.text.contains("PostgreSQL"))
            assertTrue(outcome.text.contains("OpenFlow"))
            assertTrue(outcome.text.contains("Monday and Tuesday"))
            assertFalse(outcome.text.contains("<think>"))
            assertFalse(outcome.text.contains("Sure, here is"))
            assertEquals("gpt-4o-mini", lastRequestJson?.getString("model"))

            // 3b. Unspoken hallucinated answer to a dictated question is rejected cleanly
            val questionInput = "um why is the database timing out every afternoon"
            customAssistantReply = "Database timeouts usually happen because connection pools exhaust available sockets under heavy load."
            val guardOutcome = OpenAiCompatibleClient.processTranscript(
                text = questionInput,
                baseUrl = baseUrl,
                apiKey = "sk-test-123",
                model = "gpt-4o-mini",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                customPrompt = "",
                languageCode = "en",
                customVocabulary = emptyList(),
                timeoutSeconds = 10
            )
            assertTrue(guardOutcome.succeeded)
            assertFalse(guardOutcome.text.contains("sockets"))
            assertTrue(guardOutcome.text.contains("database timing out"))

            // 4. Retry without temperature/max_tokens when server rejects 'temperature' with HTTP 400
            requestCount.set(0)
            forceSamplingErrorOnFirstTry = true
            customAssistantReply = "Let's deploy OpenFlow tomorrow morning because everything is ready now."
            val retryOutcome = OpenAiCompatibleClient.processTranscript(
                text = "um let's deploy openflow tomorrow morning because everything is ready now",
                baseUrl = baseUrl,
                apiKey = "sk-test-123",
                model = "o3-mini",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                customPrompt = "",
                languageCode = "en",
                customVocabulary = listOf("OpenFlow"),
                timeoutSeconds = 10
            )
            assertTrue(retryOutcome.succeeded)
            assertEquals(2, requestCount.get())
            assertFalse(lastRequestJson!!.has("temperature"))
            assertFalse(lastRequestJson!!.has("max_tokens"))
            forceSamplingErrorOnFirstTry = false

            // 5. HTTP 401/error safely falls back to original spoken transcript without data loss
            forceStatusCode = 401
            val failedOutcome = OpenAiCompatibleClient.processTranscript(
                text = "uh remember to check the staging deployment at 3 pm",
                baseUrl = baseUrl,
                apiKey = "bad-key",
                model = "gpt-4o-mini",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                customPrompt = "",
                languageCode = "en",
                customVocabulary = emptyList(),
                timeoutSeconds = 10
            )
            assertFalse(failedOutcome.succeeded)
            assertEquals("uh remember to check the staging deployment at 3 pm", failedOutcome.text)
            assertTrue(failedOutcome.errorSummary.orEmpty().contains("401"))
            assertTrue(failedOutcome.errorSummary.orEmpty().contains("Invalid API key"))
        }
    }

    @Test
    fun `extractAssistantContent and sanitizer completely strip all thinking block variants`() {
        val thinkingPartsJson = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject()
                        .put("reasoning_content", "SECRET INTERNAL COT SHOULD NEVER APPEAR")
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "thinking").put("text", "Hidden step 1."))
                                .put(JSONObject().put("type", "redacted_thinking").put("text", "Encrypted thought."))
                                .put(JSONObject().put("type", "text").put("thought", true).put("text", "Flagged thought."))
                                .put(JSONObject().put("type", "text").put("text", "Final cleaned answer."))
                        )
                )
            )
        ).toString()
        assertEquals("Final cleaned answer.", OpenAiCompatibleClient.extractAssistantContent(thinkingPartsJson))

        val inputs = listOf(
            "<thinking>Let me rewrite this cleanly.</thinking>\nHello there.",
            "<reasoning>Analyze grammar...</reasoning>Hello there.",
            "<scratchpad>draft</scratchpad>Hello there.",
            "<|begin_of_thought|>Step 1<|end_of_thought|>\nHello there.",
            "[THINK]Check spelling[/THINK] Hello there.",
            "Hidden prefix before closing tag</thought>\nHello there.",
            "Hello there.<thinking>Trailing unclosed reasoning"
        )
        for (raw in inputs) {
            val cleaned = GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = raw,
                fallbackInput = "hello there",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH
            )
            assertEquals("Failed for raw='$raw'", "Hello there.", cleaned)
        }

        // If a model outputs only thinking with no visible answer, fall back safely to spoken transcript
        val onlyThought = GemmaTextProcessor.sanitizeGemmaOutput(
            rawOutput = "<thinking>Analyzing the user's request carefully without answering yet...</thinking>",
            fallbackInput = "call mom at five",
            preset = GemmaPromptPreset.CLEAN_AND_POLISH
        )
        assertEquals("call mom at five", onlyThought)
    }

    @Test
    fun `thinking effort sends reasoning_effort with HTTP 400 fallback and single-pass cloud mode avoids chunking`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val seenReasoningEfforts = ArrayList<String?>()
        var rejectReasoningEffortOnce = false

        MiniHttpTestServer { _, path, _, body ->
            if (path == "/v1/chat/completions") {
                val count = requestCount.incrementAndGet()
                val reqJson = JSONObject(body)
                val effort = if (reqJson.has("reasoning_effort")) reqJson.getString("reasoning_effort") else null
                seenReasoningEfforts.add(effort)

                if (rejectReasoningEffortOnce && count == 1 && effort != null) {
                    400 to JSONObject()
                        .put("error", JSONObject().put("message", "Unsupported parameter: 'reasoning_effort'"))
                        .toString()
                } else {
                    val userContent = reqJson.getJSONArray("messages").getJSONObject(1).getString("content")
                    val unwrapped = userContent.substringAfter("<transcript>").substringBefore("</transcript>").trim()
                    200 to JSONObject().put(
                        "choices",
                        JSONArray().put(
                            JSONObject().put(
                                "message",
                                JSONObject().put("role", "assistant").put("content", "<thinking>internal</thinking>$unwrapped")
                            )
                        )
                    ).toString()
                }
            } else {
                404 to "{}"
            }
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/v1"
            val longDictation = List(65) { i ->
                "Sentence number $i discussing cloud distributed OpenFlow post processing telemetry."
            }.joinToString(" ")
            assertTrue("Test input must exceed 1,800 chars", longDictation.length > 3_500)

            // 1. Cloud default (chunkingEnabled = false) processes >3,500 chars in ONE single request & passes effort="high"
            requestCount.set(0)
            seenReasoningEfforts.clear()
            val singlePass = OpenAiCompatibleClient.processTranscript(
                text = longDictation,
                baseUrl = baseUrl,
                apiKey = "sk-test",
                model = "o3",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                customPrompt = "",
                languageCode = "en",
                customVocabulary = emptyList(),
                thinkingEffort = LlmThinkingEffort.HIGH,
                chunkingEnabled = false
            )
            assertTrue(singlePass.succeeded)
            assertEquals(1, requestCount.get())
            assertEquals(listOf("high"), seenReasoningEfforts)
            assertFalse(singlePass.text.contains("<thinking>"))

            // 2. When chunkingEnabled = true, long transcript splits across >= 2 HTTP requests
            requestCount.set(0)
            seenReasoningEfforts.clear()
            val chunked = OpenAiCompatibleClient.processTranscript(
                text = longDictation,
                baseUrl = baseUrl,
                apiKey = "sk-test",
                model = "qwen-local",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                customPrompt = "",
                languageCode = "en",
                customVocabulary = emptyList(),
                thinkingEffort = LlmThinkingEffort.LOW,
                chunkingEnabled = true
            )
            assertTrue(chunked.succeeded)
            assertTrue("Expected >= 2 chunks, got ${requestCount.get()}", requestCount.get() >= 2)
            assertTrue(seenReasoningEfforts.all { it == "low" })

            // 3. HTTP 400 rejecting reasoning_effort retries cleanly without reasoning_effort
            requestCount.set(0)
            seenReasoningEfforts.clear()
            rejectReasoningEffortOnce = true
            val fallbackEffort = OpenAiCompatibleClient.processTranscript(
                text = "um schedule a meeting with Alex on friday afternoon",
                baseUrl = baseUrl,
                apiKey = "sk-test",
                model = "legacy-chat",
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                customPrompt = "",
                languageCode = "en",
                customVocabulary = emptyList(),
                thinkingEffort = LlmThinkingEffort.OFF,
                chunkingEnabled = false
            )
            assertTrue(fallbackEffort.succeeded)
            assertEquals(2, requestCount.get())
            assertEquals(listOf("none", null), seenReasoningEfforts)
        }
    }

    @Test
    fun `audio URL resolution, WAV encoding, language normalization, and model discovery with audio models work`() {
        assertEquals(
            "http://192.168.1.50:8000/v1/audio/transcriptions",
            OpenAiCompatibleClient.resolveAudioTranscriptionsUrl("http://192.168.1.50:8000")
        )
        assertEquals(
            "https://api.groq.com/openai/v1/audio/transcriptions",
            OpenAiCompatibleClient.resolveAudioTranscriptionsUrl("https://api.groq.com/openai/v1/")
        )
        assertEquals(
            "https://host.example.com/custom/audio/transcriptions",
            OpenAiCompatibleClient.resolveAudioTranscriptionsUrl("https://host.example.com/custom/audio/transcriptions")
        )
        assertEquals(
            "https://host.example.com/custom/models",
            OpenAiCompatibleClient.resolveModelsUrl("https://host.example.com/custom/audio/transcriptions")
        )

        val wav = OpenAiCompatibleClient.encodePcm16Wav(floatArrayOf(0f, 0.5f, -0.5f, 2.0f, Float.NaN))
        assertEquals("RIFF", String(wav, 0, 4, StandardCharsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, StandardCharsets.US_ASCII))
        assertEquals(44 + 10, wav.size)

        assertEquals("de", OpenAiCompatibleClient.normalizeLanguageCode("German"))
        assertEquals("en", OpenAiCompatibleClient.normalizeLanguageCode("en-US"))
        assertEquals("fr", OpenAiCompatibleClient.normalizeLanguageCode("fra"))
        assertEquals(null, OpenAiCompatibleClient.normalizeLanguageCode("auto"))

        val modelsJson = JSONObject().put(
            "data",
            JSONArray()
                .put(JSONObject().put("id", "voz"))
                .put(JSONObject().put("id", "ear"))
                .put(JSONObject().put("id", "whisper-1"))
                .put(JSONObject().put("id", "text-embedding-3-small"))
        ).toString()
        assertEquals(
            listOf("ear", "voz"),
            OpenAiCompatibleClient.parseModelIdsFromResponse(modelsJson, includeAudioModels = false)
        )
        assertEquals(
            listOf("ear", "voz", "whisper-1"),
            OpenAiCompatibleClient.parseModelIdsFromResponse(modelsJson, includeAudioModels = true)
        )
    }

    @Test
    fun `remote Voz and remote Ear support two separate endpoints, WAV upload, word timestamps, and fallbacks`() = runBlocking {
        val vozRequests = AtomicInteger(0)
        var vozAuth: String? = null
        var vozHasRiffHeader = false
        var vozSawLanguageHint = false
        var vozSawVocabPrompt = false
        var rejectVerboseJsonOnce = true

        val earRequests = AtomicInteger(0)
        var earAuth: String? = null
        var earModelSeen: String? = null

        MiniHttpTestServer.binary { _, path, headers, bytes ->
            when (path) {
                "/v1/audio/transcriptions" -> {
                    vozAuth = headers["authorization"]
                    val attempt = vozRequests.incrementAndGet()
                    val bodyLatin = String(bytes, StandardCharsets.ISO_8859_1)
                    vozHasRiffHeader = bodyLatin.contains("RIFF") && bodyLatin.contains("WAVEfmt ")
                    vozSawLanguageHint = bodyLatin.contains("name=\"language\"\r\n\r\nde")
                    vozSawVocabPrompt = bodyLatin.contains("OpenFlow")

                    if (rejectVerboseJsonOnce && attempt == 1 && bodyLatin.contains("verbose_json")) {
                        400 to JSONObject()
                            .put("error", JSONObject().put("message", "timestamp_granularities is not supported"))
                            .toString()
                    } else if (bodyLatin.contains("whisper-words")) {
                        // Return nested segments[].words[] shape
                        200 to JSONObject()
                            .put("text", "Hallo OpenFlow.")
                            .put("duration", 1.25)
                            .put("language", "de")
                            .put(
                                "segments",
                                JSONArray().put(
                                    JSONObject().put(
                                        "words",
                                        JSONArray()
                                            .put(JSONObject().put("word", "Hallo").put("start", 0.1).put("end", 0.5))
                                            .put(JSONObject().put("word", "OpenFlow.").put("start", 0.55).put("end", 1.1))
                                    )
                                )
                            )
                            .toString()
                    } else {
                        200 to JSONObject().put("text", "Guten Tag OpenFlow").toString()
                    }
                }
                "/v1/models" -> {
                    200 to JSONObject().put(
                        "data",
                        JSONArray()
                            .put(JSONObject().put("id", "voz"))
                            .put(JSONObject().put("id", "whisper-large-v3"))
                    ).toString()
                }
                else -> 404 to "{}"
            }
        }.use { vozServer ->
            MiniHttpTestServer.binary { _, path, headers, bytes ->
                if (path == "/v1/audio/transcriptions") {
                    earAuth = headers["authorization"]
                    earRequests.incrementAndGet()
                    val bodyLatin = String(bytes, StandardCharsets.ISO_8859_1)
                    earModelSeen = if (bodyLatin.contains("\r\n\r\near-v1\r\n")) "ear-v1" else "other"
                    200 to JSONObject().put("language", "German").put("text", "").toString()
                } else {
                    404 to "{}"
                }
            }.use { earServer ->
                val vozUrl = "http://127.0.0.1:${vozServer.port}/v1"
                val earUrl = "http://127.0.0.1:${earServer.port}/v1"

                // Fetch audio models keeps whisper-* model IDs
                val audioModels = OpenAiCompatibleClient.fetchAvailableModels(
                    baseUrl = vozUrl,
                    apiKey = "voz-secret",
                    includeAudioModels = true
                )
                assertTrue(audioModels.isSuccess)
                assertEquals(listOf("voz", "whisper-large-v3"), audioModels.getOrThrow())

                // Remote Ear on Endpoint #2 identifies German
                val samples = FloatArray(16_000) { i -> kotlin.math.sin(i * 0.05f) * 0.25f }
                val earOutcome = OpenAiCompatibleClient.identifyLanguageRemote(
                    samples = samples,
                    baseUrl = earUrl,
                    apiKey = "ear-secret",
                    model = "ear-v1"
                )
                assertEquals("de", earOutcome.languageCode)
                assertEquals("Bearer ear-secret", earAuth)
                assertEquals("ear-v1", earModelSeen)
                assertEquals(1, earRequests.get())

                // Remote Voz on Endpoint #1 retries verbose_json HTTP 400 -> json cleanly
                val vozFallbackOutcome = OpenAiCompatibleClient.transcribeAudioRemote(
                    samples = samples,
                    baseUrl = vozUrl,
                    apiKey = "voz-secret",
                    model = "voz",
                    language = "de",
                    customVocabulary = listOf("OpenFlow")
                )
                assertEquals("Bearer voz-secret", vozAuth)
                assertEquals(2, vozRequests.get())
                assertTrue(vozHasRiffHeader)
                assertTrue(vozSawLanguageHint)
                assertTrue(vozSawVocabPrompt)
                assertEquals("Guten Tag OpenFlow", vozFallbackOutcome.transcription?.text)
                assertTrue(Voz.supportedLanguages.contains(earOutcome.languageCode))

                // Remote Voz nested segments[].words[] parsing + calibration clip smoke test
                rejectVerboseJsonOnce = false
                val vozWordsOutcome = OpenAiCompatibleClient.transcribeAudioRemote(
                    samples = samples,
                    baseUrl = vozUrl,
                    apiKey = "voz-secret",
                    model = "whisper-words",
                    language = "de"
                )
                assertEquals("Hallo OpenFlow.", vozWordsOutcome.transcription?.text)
                assertEquals(2, vozWordsOutcome.transcription?.words?.size)
                assertEquals(1.25, vozWordsOutcome.transcription?.duration ?: 0.0, 1e-4)

                val testVozConn = OpenAiCompatibleClient.testAudioConnection(
                    baseUrl = vozUrl,
                    apiKey = "voz-secret",
                    model = "voz",
                    isEarMode = false
                )
                assertTrue(testVozConn.success)
                val testEarConn = OpenAiCompatibleClient.testAudioConnection(
                    baseUrl = earUrl,
                    apiKey = "ear-secret",
                    model = "ear-v1",
                    isEarMode = true
                )
                assertTrue(testEarConn.success)
                assertTrue(testEarConn.message.contains("lang=\"de\""))
            }
        }
    }
}
