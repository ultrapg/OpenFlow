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

import com.voicebubble.openflow.settings.GemmaPromptPreset
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

    private class MiniHttpTestServer(
        private val handler: (method: String, path: String, headers: Map<String, String>, body: String) -> Pair<Int, String>
    ) : AutoCloseable {
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
                        val body = String(bodyBytes, 0, read, StandardCharsets.UTF_8)
                        val (status, respBody) = handler(method, path, headers, body)
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
}
