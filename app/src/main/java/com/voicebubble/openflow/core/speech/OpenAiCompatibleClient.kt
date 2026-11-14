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

import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.GemmaPromptPreset
import com.voicebubble.openflow.settings.LlmModelChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Curated quick-fill presets for common OpenAI-compatible inference endpoints
 * (cloud APIs and local/LAN servers such as Ollama, LM Studio, or vLLM).
 */
data class OpenAiEndpointPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean
)

/**
 * Result of testing an OpenAI-compatible API endpoint + model configuration from Settings.
 */
data class OpenAiConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val httpStatus: Int? = null,
    val previewOutput: String? = null,
    val message: String
)

/**
 * Result of a transcript post-processing request against an OpenAI-compatible `/chat/completions` API.
 */
data class OpenAiCompletionOutcome(
    val text: String,
    val succeeded: Boolean,
    val latencyMs: Long,
    val errorSummary: String? = null
)

/**
 * Zero-dependency OpenAI-compatible HTTP client built on Android/JDK `HttpURLConnection` + `org.json`.
 *
 * Reuses OpenFlow's carefully tuned [GemmaTextProcessor] dictation cleanup system prompts, spoken-construct
 * pre-normalization, custom vocabulary biasing, anti-chatter/unspoken-content guards, and active input-field
 * context hints while supporting any OpenAI-compatible cloud or local endpoint (OpenAI, OpenRouter, Groq,
 * Gemini OpenAI endpoint, Anthropic/Mistral proxies, Ollama, LM Studio, vLLM, llama.cpp server).
 */
object OpenAiCompatibleClient {
    private const val TAG = "OpenAiCompatibleClient"

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val DEFAULT_TIMEOUT_SECONDS = 20

    val PRESETS: List<OpenAiEndpointPreset> = listOf(
        OpenAiEndpointPreset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            requiresApiKey = true
        ),
        OpenAiEndpointPreset(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openai/gpt-4o-mini",
            requiresApiKey = true
        ),
        OpenAiEndpointPreset(
            id = "groq",
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            requiresApiKey = true
        ),
        OpenAiEndpointPreset(
            id = "gemini",
            label = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-2.5-flash",
            requiresApiKey = true
        ),
        OpenAiEndpointPreset(
            id = "ollama",
            label = "Ollama / Local",
            baseUrl = "http://127.0.0.1:11434/v1",
            defaultModel = "qwen3:4b",
            requiresApiKey = false
        )
    )

    /**
     * Normalizes a user-entered Base URL into the full `/chat/completions` URL.
     * Supports:
     * - Full endpoint URL (`.../v1/chat/completions`) -> kept verbatim
     * - Versioned/API base URL (`https://api.openai.com/v1`, `.../v1beta/openai`) -> `.../chat/completions`
     * - Bare host/port (`http://192.168.1.50:11434`) -> `http://192.168.1.50:11434/v1/chat/completions`
     */
    fun resolveChatCompletionsUrl(rawBaseUrl: String): String {
        val cleaned = rawBaseUrl.trim().trimEnd('/')
        if (cleaned.isEmpty()) return ""
        val lower = cleaned.lowercase(Locale.ROOT)
        if (lower.endsWith("/chat/completions") || lower.endsWith("/completions")) {
            return cleaned
        }
        val path = runCatching { URI(cleaned).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return if (path.isEmpty()) {
            "$cleaned/v1/chat/completions"
        } else {
            "$cleaned/chat/completions"
        }
    }

    /**
     * Normalizes a user-entered Base URL into the `/models` discovery endpoint.
     */
    fun resolveModelsUrl(rawBaseUrl: String): String {
        val cleaned = rawBaseUrl.trim().trimEnd('/')
        if (cleaned.isEmpty()) return ""
        val lower = cleaned.lowercase(Locale.ROOT)
        val withoutCompletions = when {
            lower.endsWith("/chat/completions") -> cleaned.dropLast("/chat/completions".length)
            lower.endsWith("/completions") -> cleaned.dropLast("/completions".length)
            else -> cleaned
        }.trimEnd('/')
        if (withoutCompletions.lowercase(Locale.ROOT).endsWith("/models")) {
            return withoutCompletions
        }
        val path = runCatching { URI(withoutCompletions).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return if (path.isEmpty()) {
            "$withoutCompletions/v1/models"
        } else {
            "$withoutCompletions/models"
        }
    }

    fun isValidHttpUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    /**
     * Builds the OpenAI Chat Completions JSON request body.
     */
    fun buildChatCompletionPayload(
        model: String,
        systemInstruction: String,
        userPrompt: String,
        includeSamplingParams: Boolean = true,
        maxTokens: Int? = null
    ): JSONObject {
        val messages = JSONArray().apply {
            if (systemInstruction.isNotBlank()) {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", systemInstruction)
                    }
                )
            }
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                }
            )
        }
        return JSONObject().apply {
            put("model", model.trim())
            put("messages", messages)
            put("stream", false)
            if (includeSamplingParams) {
                put("temperature", 0.0)
            }
            if (maxTokens != null && maxTokens > 0) {
                put("max_tokens", maxTokens)
            }
        }
    }

    /**
     * Extracts assistant text from an OpenAI `/chat/completions` JSON response, supporting
     * both scalar `message.content` strings and structured content-part arrays.
     */
    fun extractAssistantContent(responseBody: String): String? {
        if (responseBody.isBlank()) return null
        return runCatching {
            val root = JSONObject(responseBody)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            val message = firstChoice.optJSONObject("message")
            if (message != null) {
                val rawContent = message.opt("content")
                when (rawContent) {
                    is String -> rawContent
                    is JSONArray -> buildString {
                        for (i in 0 until rawContent.length()) {
                            val part = rawContent.opt(i)
                            when (part) {
                                is String -> append(part)
                                is JSONObject -> {
                                    val text = part.optString("text", "")
                                    if (text.isNotEmpty()) append(text)
                                }
                            }
                        }
                    }
                    else -> null
                }?.takeIf { it.isNotBlank() }
            } else {
                firstChoice.optString("text", "").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * Extracts available model IDs from OpenAI `/v1/models` (`data[].id`) or Ollama `/api/tags` (`models[].name`).
     */
    fun parseModelIdsFromResponse(responseBody: String): List<String> {
        if (responseBody.isBlank()) return emptyList()
        return runCatching {
            val trimmed = responseBody.trim()
            val ids = LinkedHashSet<String>()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val id = item?.optString("id")?.takeIf { it.isNotBlank() }
                        ?: item?.optString("name")?.takeIf { it.isNotBlank() }
                    if (id != null) ids.add(id)
                }
            } else {
                val root = JSONObject(trimmed)
                val data = root.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val obj = data.optJSONObject(i)
                        val id = obj?.optString("id")?.takeIf { it.isNotBlank() }
                        if (id != null && !isLikelyNonChatModel(id)) {
                            ids.add(id)
                        }
                    }
                }
                val ollamaModels = root.optJSONArray("models")
                if (ollamaModels != null) {
                    for (i in 0 until ollamaModels.length()) {
                        val obj = ollamaModels.optJSONObject(i)
                        val name = obj?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: obj?.optString("id")?.takeIf { it.isNotBlank() }
                        if (name != null) ids.add(name)
                    }
                }
            }
            ids.toList().sorted()
        }.getOrDefault(emptyList())
    }

    private fun isLikelyNonChatModel(modelId: String): Boolean {
        val lower = modelId.lowercase(Locale.ROOT)
        return lower.contains("embedding") ||
            lower.startsWith("text-embedding") ||
            lower.startsWith("dall-e") ||
            lower.startsWith("tts-") ||
            lower.startsWith("whisper") ||
            lower.contains("moderation")
    }

    /**
     * Fetches the list of models exposed by the configured OpenAI-compatible endpoint.
     */
    suspend fun fetchAvailableModels(
        baseUrl: String,
        apiKey: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (!isValidHttpUrl(baseUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid http:// or https:// base URL."))
        }
        val modelsUrl = resolveModelsUrl(baseUrl)
        val timeoutMs = timeoutSeconds.coerceIn(5, 60) * 1_000
        try {
            val response = executeHttpRequest(
                urlString = modelsUrl,
                method = "GET",
                apiKey = apiKey,
                body = null,
                connectTimeoutMs = minOf(timeoutMs, 10_000),
                readTimeoutMs = timeoutMs
            )
            if (response.statusCode in 200..299) {
                val models = parseModelIdsFromResponse(response.body)
                if (models.isEmpty()) {
                    Result.failure(IOException("Endpoint responded (HTTP ${response.statusCode}), but no models were listed."))
                } else {
                    Result.success(models)
                }
            } else {
                Result.failure(IOException(formatHttpErrorMessage(response.statusCode, response.body, modelsUrl)))
            }
        } catch (t: Throwable) {
            Result.failure(IOException(formatExceptionMessage(t, modelsUrl), t))
        }
    }

    /**
     * Runs a fast live completion smoke test against the configured endpoint + model and reports latency.
     */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
    ): OpenAiConnectionTestResult = withContext(Dispatchers.IO) {
        if (!isValidHttpUrl(baseUrl)) {
            return@withContext OpenAiConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "Invalid Base URL. Use http:// or https:// (e.g. https://api.openai.com/v1)."
            )
        }
        val cleanModel = model.trim()
        if (cleanModel.isEmpty()) {
            return@withContext OpenAiConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "Enter a model ID (e.g. gpt-4o-mini or Fetch Models)."
            )
        }

        val chatUrl = resolveChatCompletionsUrl(baseUrl)
        val timeoutMs = timeoutSeconds.coerceIn(5, 90) * 1_000
        val startNs = System.nanoTime()

        try {
            val systemPrompt = GemmaTextProcessor.buildSystemInstruction(
                preset = GemmaPromptPreset.CLEAN_AND_POLISH,
                languageCode = "en",
                disableThinking = true,
                llmChoice = LlmModelChoice.GEMMA_4_E2B
            )
            val sampleInput = "um hey sarah lets meet at three thirty pm"
            val userPrompt = GemmaTextProcessor.buildUserPrompt(sampleInput, "en")
            val payload = buildChatCompletionPayload(
                model = cleanModel,
                systemInstruction = systemPrompt,
                userPrompt = userPrompt,
                includeSamplingParams = true,
                maxTokens = 64
            )
            var response = executeHttpRequest(
                urlString = chatUrl,
                method = "POST",
                apiKey = apiKey,
                body = payload.toString(),
                connectTimeoutMs = minOf(timeoutMs, 10_000),
                readTimeoutMs = timeoutMs
            )
            if (response.statusCode == 400 && shouldRetryWithoutSamplingParams(response.body)) {
                val minimalPayload = buildChatCompletionPayload(
                    model = cleanModel,
                    systemInstruction = systemPrompt,
                    userPrompt = userPrompt,
                    includeSamplingParams = false,
                    maxTokens = null
                )
                response = executeHttpRequest(
                    urlString = chatUrl,
                    method = "POST",
                    apiKey = apiKey,
                    body = minimalPayload.toString(),
                    connectTimeoutMs = minOf(timeoutMs, 10_000),
                    readTimeoutMs = timeoutMs
                )
            }
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            if (response.statusCode in 200..299) {
                val rawText = extractAssistantContent(response.body)
                if (rawText.isNullOrBlank()) {
                    return@withContext OpenAiConnectionTestResult(
                        success = false,
                        latencyMs = elapsedMs,
                        httpStatus = response.statusCode,
                        message = "Connected (HTTP ${response.statusCode}), but response had empty choices[0].message.content."
                    )
                }
                val cleaned = GemmaTextProcessor.sanitizeGemmaOutput(
                    rawOutput = rawText,
                    fallbackInput = sampleInput,
                    preset = GemmaPromptPreset.CLEAN_AND_POLISH
                )
                OpenAiConnectionTestResult(
                    success = true,
                    latencyMs = elapsedMs,
                    httpStatus = response.statusCode,
                    previewOutput = cleaned,
                    message = "Connected in ${elapsedMs} ms · Preview: \"$cleaned\""
                )
            } else {
                OpenAiConnectionTestResult(
                    success = false,
                    latencyMs = elapsedMs,
                    httpStatus = response.statusCode,
                    message = formatHttpErrorMessage(response.statusCode, response.body, chatUrl)
                )
            }
        } catch (t: Throwable) {
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            OpenAiConnectionTestResult(
                success = false,
                latencyMs = elapsedMs,
                message = formatExceptionMessage(t, chatUrl)
            )
        }
    }

    /**
     * Cleans and formats a dictated transcript via the configured OpenAI-compatible endpoint.
     *
     * Automatically skips the network call in `<1 ms` via [GemmaTextProcessor.canBypassLlmFastPath] when
     * the utterance is already clean and `engineResolved`/clean triggers pass, applies spoken construct
     * pre-normalization, custom vocabulary biasing, anti-chatter/unspoken-content/anti-truncation guards,
     * and gracefully falls back on any network or API error without losing dictated text.
     */
    suspend fun processTranscript(
        text: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        preset: GemmaPromptPreset,
        customPrompt: String,
        languageCode: String?,
        customVocabulary: List<String>,
        fieldContext: FlowFieldContext = FlowFieldContext.NONE,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        engineResolved: Boolean = false
    ): OpenAiCompletionOutcome = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext OpenAiCompletionOutcome(text, succeeded = true, latencyMs = 0L)
        }

        if (GemmaTextProcessor.canBypassLlmFastPath(trimmed, preset, fieldContext, engineResolved)) {
            val fast = GemmaTextProcessor.applyDeterministicFastPathCleanup(
                text = trimmed,
                preset = preset,
                languageCode = languageCode,
                customVocabulary = customVocabulary,
                fieldContext = fieldContext
            )
            return@withContext OpenAiCompletionOutcome(fast, succeeded = true, latencyMs = 0L)
        }

        val cleanModel = model.trim()
        if (!isValidHttpUrl(baseUrl) || cleanModel.isEmpty()) {
            return@withContext OpenAiCompletionOutcome(
                text = trimmed,
                succeeded = false,
                latencyMs = 0L,
                errorSummary = "OpenAI-compatible URL or model is blank"
            )
        }

        val chatUrl = resolveChatCompletionsUrl(baseUrl)
        val timeoutMs = timeoutSeconds.coerceIn(5, 90) * 1_000
        val preNormalized = GemmaTextProcessor.preNormalizeSpokenConstructs(trimmed, preset)
        val inputForPrompt = preNormalized.ifBlank { trimmed }

        val systemInstruction = GemmaTextProcessor.buildSystemInstruction(
            preset = preset,
            customPrompt = customPrompt,
            languageCode = languageCode,
            customVocabulary = customVocabulary,
            fieldContext = fieldContext,
            disableThinking = true,
            llmChoice = LlmModelChoice.GEMMA_4_E2B
        )
        val userPrompt = GemmaTextProcessor.buildUserPrompt(inputForPrompt, languageCode)
        val approxWords = inputForPrompt.split(Regex("\\s+")).count { it.isNotEmpty() }
        val maxOutputTokens = (approxWords * 4 + 192).coerceIn(192, 4_096)

        val startNs = System.nanoTime()
        try {
            val payload = buildChatCompletionPayload(
                model = cleanModel,
                systemInstruction = systemInstruction,
                userPrompt = userPrompt,
                includeSamplingParams = true,
                maxTokens = maxOutputTokens
            )
            var response = executeHttpRequest(
                urlString = chatUrl,
                method = "POST",
                apiKey = apiKey,
                body = payload.toString(),
                connectTimeoutMs = minOf(timeoutMs, 10_000),
                readTimeoutMs = timeoutMs
            )
            if (response.statusCode == 400 && shouldRetryWithoutSamplingParams(response.body)) {
                val minimalPayload = buildChatCompletionPayload(
                    model = cleanModel,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    includeSamplingParams = false,
                    maxTokens = null
                )
                response = executeHttpRequest(
                    urlString = chatUrl,
                    method = "POST",
                    apiKey = apiKey,
                    body = minimalPayload.toString(),
                    connectTimeoutMs = minOf(timeoutMs, 10_000),
                    readTimeoutMs = timeoutMs
                )
            }
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)

            if (response.statusCode !in 200..299) {
                val msg = formatHttpErrorMessage(response.statusCode, response.body, chatUrl)
                DebugLogManager.logWarning(TAG, "API request failed ($elapsedMs ms): $msg")
                return@withContext OpenAiCompletionOutcome(
                    text = trimmed,
                    succeeded = false,
                    latencyMs = elapsedMs,
                    errorSummary = msg
                )
            }

            val rawAssistant = extractAssistantContent(response.body)
            if (rawAssistant.isNullOrBlank()) {
                DebugLogManager.logWarning(TAG, "API returned empty message content ($elapsedMs ms)")
                return@withContext OpenAiCompletionOutcome(
                    text = trimmed,
                    succeeded = false,
                    latencyMs = elapsedMs,
                    errorSummary = "Empty response from API"
                )
            }

            var cleaned = GemmaTextProcessor.sanitizeGemmaOutput(
                rawOutput = rawAssistant,
                fallbackInput = inputForPrompt,
                preset = preset,
                customPrompt = customPrompt
            )
            if (preset == GemmaPromptPreset.CLEAN_AND_POLISH || preset == GemmaPromptPreset.STRICT_CLEANUP) {
                cleaned = GemmaTextProcessor.expandInlineSequentialLists(cleaned)
            }
            if (customVocabulary.isNotEmpty() && preset != GemmaPromptPreset.CUSTOM) {
                cleaned = applyCustomVocabulary(cleaned, customVocabulary)
            }
            if (preset != GemmaPromptPreset.CUSTOM && fieldContext.isMidSentenceContinuation) {
                cleaned = GemmaTextProcessor.applyCursorContinuationCasing(
                    text = cleaned,
                    languageCode = languageCode,
                    customVocabulary = customVocabulary
                )
            }
            OpenAiCompletionOutcome(
                text = cleaned.ifBlank { trimmed },
                succeeded = true,
                latencyMs = elapsedMs
            )
        } catch (t: Throwable) {
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            val msg = formatExceptionMessage(t, chatUrl)
            DebugLogManager.logWarning(TAG, "API exception ($elapsedMs ms): $msg", t)
            OpenAiCompletionOutcome(
                text = trimmed,
                succeeded = false,
                latencyMs = elapsedMs,
                errorSummary = msg
            )
        }
    }

    private data class HttpResult(val statusCode: Int, val body: String)

    private suspend fun executeHttpRequest(
        urlString: String,
        method: String,
        apiKey: String,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResult = suspendCancellableCoroutine { cont ->
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        cont.invokeOnCancellation {
            runCatching { conn.disconnect() }
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                conn.outputStream.use { os ->
                    os.write(bytes)
                    os.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (cont.isActive) {
                cont.resume(HttpResult(code, responseText))
            }
        } catch (t: Throwable) {
            if (cont.isActive) {
                cont.resumeWith(Result.failure(t))
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun shouldRetryWithoutSamplingParams(errorBody: String): Boolean {
        val lower = errorBody.lowercase(Locale.ROOT)
        return lower.contains("temperature") ||
            lower.contains("max_tokens") ||
            lower.contains("max_completion_tokens") ||
            lower.contains("unsupported parameter")
    }

    internal fun formatHttpErrorMessage(statusCode: Int, responseBody: String, url: String): String {
        val serverMsg = runCatching {
            val json = JSONObject(responseBody)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message", "").takeIf { it.isNotBlank() }
                ?: json.optString("error", "").takeIf { it.isNotBlank() && !it.startsWith("{") }
        }.getOrNull()

        val hint = when (statusCode) {
            401, 403 -> "Authentication failed (check API key)"
            404 -> "Endpoint or model not found"
            429 -> "Rate limit / quota exceeded"
            in 500..599 -> "Remote server error"
            else -> "HTTP $statusCode"
        }
        return if (serverMsg != null) {
            "HTTP $statusCode ($hint): ${serverMsg.take(160)}"
        } else {
            "$hint (HTTP $statusCode at $url)"
        }
    }

    private fun formatExceptionMessage(t: Throwable, url: String): String {
        val raw = t.message.orEmpty()
        return when {
            t is HttpRetryException -> "HTTP ${t.responseCode()} (Authentication failed — check API key)"
            t is SocketTimeoutException -> "Timed out waiting for API response ($url)"
            t is UnknownHostException -> "Cannot resolve host — check Base URL / network"
            t is ConnectException -> "Connection refused — verify server is running at $url"
            raw.contains("CLEARTEXT", ignoreCase = true) ->
                "Cleartext HTTP blocked by OS security policy"
            else -> raw.take(160).ifBlank { t.javaClass.simpleName }
        }
    }
}
