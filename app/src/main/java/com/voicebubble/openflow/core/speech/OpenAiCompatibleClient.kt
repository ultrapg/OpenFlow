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

import ai.desertant.voz.Transcription
import ai.desertant.voz.Voz
import ai.desertant.voz.Word
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.GemmaPromptPreset
import com.voicebubble.openflow.settings.LlmModelChoice
import com.voicebubble.openflow.settings.LlmProviderMode
import com.voicebubble.openflow.settings.LlmThinkingEffort
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

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
 * Result of a remote OpenAI-compatible speech-to-text (`/audio/transcriptions`) request.
 */
data class RemoteAudioTranscriptionOutcome(
    val transcription: Transcription?,
    val detectedLanguage: String?,
    val latencyMs: Long,
    val errorSummary: String? = null
)

/**
 * Result of a remote OpenAI-compatible spoken language identification (`Ear`) request.
 */
data class RemoteLanguageIdOutcome(
    val languageCode: String?,
    val latencyMs: Long,
    val errorSummary: String? = null
)

/**
 * Zero-dependency OpenAI-compatible HTTP client built on Android/JDK `HttpURLConnection` + `org.json`.
 *
 * Supports both:
 * - Chat Completions (`/chat/completions`) for LLM transcript post-processing with configurable reasoning effort,
 *   full thinking-token sanitization, and optional single-pass or chunked processing.
 * - Audio Transcriptions (`/audio/transcriptions`) for remote `Voz` (STT) and remote `Ear` (language ID)
 *   hosted on the same server or separate endpoints.
 */
object OpenAiCompatibleClient {
    private const val TAG = "OpenAiCompatibleClient"

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val DEFAULT_TIMEOUT_SECONDS = 20

    const val DEFAULT_REMOTE_VOZ_MODEL = "voz"
    const val DEFAULT_REMOTE_EAR_MODEL = "ear"
    const val DEFAULT_AUDIO_TIMEOUT_SECONDS = 30
    const val AUDIO_SAMPLE_RATE_HZ = 16_000

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

    val AUDIO_PRESETS: List<OpenAiEndpointPreset> = listOf(
        OpenAiEndpointPreset(
            id = "lan_voz",
            label = "LAN / Self-Hosted",
            baseUrl = "http://192.168.1.50:8000/v1",
            defaultModel = DEFAULT_REMOTE_VOZ_MODEL,
            requiresApiKey = false
        ),
        OpenAiEndpointPreset(
            id = "openai_whisper",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "whisper-1",
            requiresApiKey = true
        ),
        OpenAiEndpointPreset(
            id = "groq_whisper",
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "whisper-large-v3-turbo",
            requiresApiKey = true
        ),
        OpenAiEndpointPreset(
            id = "speaches_local",
            label = "Local / Speaches",
            baseUrl = "http://127.0.0.1:8000/v1",
            defaultModel = "Systran/faster-whisper-small",
            requiresApiKey = false
        )
    )

    private val THINKING_CONTENT_PART_TYPES = setOf(
        "thinking",
        "reasoning",
        "thought",
        "redacted_thinking",
        "analysis",
        "reasoning_content"
    )

    private val LANGUAGE_ALIAS_MAP: Map<String, String> = mapOf(
        // ISO 639-2 / 639-3 codes & English/native language names -> Voz 2-letter codes
        "eng" to "en", "english" to "en",
        "deu" to "de", "ger" to "de", "german" to "de", "deutsch" to "de",
        "fra" to "fr", "fre" to "fr", "french" to "fr", "francais" to "fr", "français" to "fr",
        "spa" to "es", "spanish" to "es", "espanol" to "es", "español" to "es", "castilian" to "es",
        "ita" to "it", "italian" to "it", "italiano" to "it",
        "por" to "pt", "portuguese" to "pt", "portugues" to "pt", "português" to "pt",
        "nld" to "nl", "dut" to "nl", "dutch" to "nl", "nederlands" to "nl", "flemish" to "nl",
        "rus" to "ru", "russian" to "ru",
        "jpn" to "ja", "japanese" to "ja",
        "zho" to "zh", "chi" to "zh", "cmn" to "zh", "chinese" to "zh", "mandarin" to "zh",
        "kor" to "ko", "korean" to "ko",
        "ara" to "ar", "arabic" to "ar",
        "hin" to "hi", "hindi" to "hi",
        "pol" to "pl", "polish" to "pl", "polski" to "pl",
        "tur" to "tr", "turkish" to "tr",
        "ukr" to "uk", "ukrainian" to "uk",
        "vie" to "vi", "vietnamese" to "vi",
        "swe" to "sv", "swedish" to "sv",
        "nor" to "no", "nob" to "no", "nno" to "no", "norwegian" to "no",
        "dan" to "da", "danish" to "da",
        "fin" to "fi", "finnish" to "fi",
        "ces" to "cs", "cze" to "cs", "czech" to "cs",
        "ell" to "el", "gre" to "el", "greek" to "el",
        "heb" to "he", "hebrew" to "he",
        "tha" to "th", "thai" to "th",
        "ind" to "id", "indonesian" to "id",
        "ron" to "ro", "rum" to "ro", "romanian" to "ro",
        "hun" to "hu", "hungarian" to "hu",
        "cat" to "ca", "catalan" to "ca",
        "slk" to "sk", "slo" to "sk", "slovak" to "sk",
        "hrv" to "hr", "croatian" to "hr",
        "bul" to "bg", "bulgarian" to "bg",
        "srp" to "sr", "serbian" to "sr",
        "slv" to "sl", "slovenian" to "sl",
        "msa" to "ms", "may" to "ms", "malay" to "ms",
        "fas" to "fa", "per" to "fa", "persian" to "fa", "farsi" to "fa",
        "urd" to "ur", "urdu" to "ur",
        "ben" to "bn", "bengali" to "bn",
        "tam" to "ta", "tamil" to "ta",
        "tel" to "te", "telugu" to "te",
        "mar" to "mr", "marathi" to "mr",
        "afr" to "af", "afrikaans" to "af",
        "tgl" to "tl", "fil" to "tl", "tagalog" to "tl", "filipino" to "tl"
    )

    private fun stripEndpointSuffix(rawBaseUrl: String): String {
        val cleaned = rawBaseUrl.trim().trimEnd('/')
        if (cleaned.isEmpty()) return ""
        val lower = cleaned.lowercase(Locale.ROOT)
        return when {
            lower.endsWith("/audio/transcriptions") -> cleaned.dropLast("/audio/transcriptions".length)
            lower.endsWith("/transcriptions") -> cleaned.dropLast("/transcriptions".length)
            lower.endsWith("/chat/completions") -> cleaned.dropLast("/chat/completions".length)
            lower.endsWith("/completions") -> cleaned.dropLast("/completions".length)
            else -> cleaned
        }.trimEnd('/')
    }

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
        val base = stripEndpointSuffix(cleaned)
        val path = runCatching { URI(base).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return if (path.isEmpty()) {
            "$base/v1/chat/completions"
        } else {
            "$base/chat/completions"
        }
    }

    /**
     * Normalizes a user-entered Base URL into the full `/audio/transcriptions` URL for remote `Voz` / `Ear`.
     * Supports full endpoint URLs (`.../v1/audio/transcriptions`), versioned API roots (`.../v1`),
     * and bare host/port (`http://192.168.1.50:8000` -> `http://192.168.1.50:8000/v1/audio/transcriptions`).
     */
    fun resolveAudioTranscriptionsUrl(rawBaseUrl: String): String {
        val cleaned = rawBaseUrl.trim().trimEnd('/')
        if (cleaned.isEmpty()) return ""
        val lower = cleaned.lowercase(Locale.ROOT)
        if (lower.endsWith("/audio/transcriptions") || lower.endsWith("/transcriptions")) {
            return cleaned
        }
        val base = stripEndpointSuffix(cleaned)
        val path = runCatching { URI(base).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return if (path.isEmpty()) {
            "$base/v1/audio/transcriptions"
        } else {
            "$base/audio/transcriptions"
        }
    }

    /**
     * Normalizes a user-entered Base URL into the `/models` discovery endpoint.
     */
    fun resolveModelsUrl(rawBaseUrl: String): String {
        val withoutEndpoint = stripEndpointSuffix(rawBaseUrl)
        if (withoutEndpoint.isEmpty()) return ""
        if (withoutEndpoint.lowercase(Locale.ROOT).endsWith("/models")) {
            return withoutEndpoint
        }
        val path = runCatching { URI(withoutEndpoint).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return if (path.isEmpty()) {
            "$withoutEndpoint/v1/models"
        } else {
            "$withoutEndpoint/models"
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
        maxTokens: Int? = null,
        thinkingEffort: LlmThinkingEffort = LlmThinkingEffort.DEFAULT
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
            thinkingEffort.apiValue?.let { effortValue ->
                put("reasoning_effort", effortValue)
            }
        }
    }

    /**
     * Extracts assistant text from an OpenAI `/chat/completions` JSON response, supporting
     * both scalar `message.content` strings and structured content-part arrays while excluding
     * structured `thinking` / `reasoning` / `redacted_thinking` content parts.
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
                                    val partType = part.optString("type", "").lowercase(Locale.ROOT)
                                    val isThoughtPart = part.optBoolean("thought", false) ||
                                        partType in THINKING_CONTENT_PART_TYPES
                                    if (!isThoughtPart) {
                                        val text = part.optString("text", "")
                                        if (text.isNotEmpty()) append(text)
                                    }
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
    fun parseModelIdsFromResponse(
        responseBody: String,
        includeAudioModels: Boolean = false
    ): List<String> {
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
                    if (id != null && !isLikelyNonTargetModel(id, includeAudioModels)) {
                        ids.add(id)
                    }
                }
            } else {
                val root = JSONObject(trimmed)
                val data = root.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val obj = data.optJSONObject(i)
                        val id = obj?.optString("id")?.takeIf { it.isNotBlank() }
                        if (id != null && !isLikelyNonTargetModel(id, includeAudioModels)) {
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

    private fun isLikelyNonTargetModel(modelId: String, includeAudioModels: Boolean): Boolean {
        val lower = modelId.lowercase(Locale.ROOT)
        if (lower.contains("embedding") ||
            lower.startsWith("text-embedding") ||
            lower.startsWith("dall-e") ||
            lower.startsWith("tts-") ||
            lower.contains("moderation")
        ) {
            return true
        }
        return !includeAudioModels && lower.startsWith("whisper")
    }

    /**
     * Fetches the list of models exposed by the configured OpenAI-compatible endpoint.
     */
    suspend fun fetchAvailableModels(
        baseUrl: String,
        apiKey: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        includeAudioModels: Boolean = false
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (!isValidHttpUrl(baseUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid http:// or https:// base URL."))
        }
        val modelsUrl = resolveModelsUrl(baseUrl)
        val timeoutMs = timeoutSeconds.coerceIn(5, 90) * 1_000
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
                val models = parseModelIdsFromResponse(response.body, includeAudioModels = includeAudioModels)
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
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        thinkingEffort: LlmThinkingEffort = LlmThinkingEffort.DEFAULT
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
                disableThinking = thinkingEffort == LlmThinkingEffort.OFF || thinkingEffort == LlmThinkingEffort.DEFAULT,
                llmChoice = LlmModelChoice.GEMMA_4_E2B,
                llmProvider = LlmProviderMode.OPENAI_API
            )
            val sampleInput = "um hey sarah lets meet at three thirty pm"
            val userPrompt = GemmaTextProcessor.buildUserPrompt(sampleInput, "en")
            val testMaxTokens = when (thinkingEffort) {
                LlmThinkingEffort.LOW -> 256
                LlmThinkingEffort.MEDIUM -> 512
                LlmThinkingEffort.HIGH -> 1_024
                else -> 128
            }
            val response = executeChatCompletionWithFallback(
                chatUrl = chatUrl,
                apiKey = apiKey,
                model = cleanModel,
                systemInstruction = systemPrompt,
                userPrompt = userPrompt,
                maxTokens = testMaxTokens,
                thinkingEffort = thinkingEffort,
                connectTimeoutMs = minOf(timeoutMs, 10_000),
                readTimeoutMs = timeoutMs
            )
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
     * Runs in a single pass by default (`chunkingEnabled = false`) since cloud and hosted LLMs support
     * large context windows, or in sequential overlapping chunks when `chunkingEnabled = true`.
     * Strips any thinking/reasoning tokens before returning text so chain-of-thought never leaks into
     * pasted text, and gracefully falls back on any network or API error without losing dictated text.
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
        engineResolved: Boolean = false,
        thinkingEffort: LlmThinkingEffort = LlmThinkingEffort.DEFAULT,
        chunkingEnabled: Boolean = false
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
        val segments = if (chunkingEnabled) {
            GemmaTextProcessor.splitLongTranscript(inputForPrompt, 1_800).ifEmpty { listOf(inputForPrompt) }
        } else {
            listOf(inputForPrompt)
        }

        val startNs = System.nanoTime()
        try {
            val transformedSegments = ArrayList<String>(segments.size)
            for ((index, segment) in segments.withIndex()) {
                val segmentFieldCtx = if (index == 0) {
                    fieldContext
                } else {
                    fieldContext.copy(
                        isMidSentenceContinuation = false,
                        continuingNumberedListFrom = null,
                        isContinuingBulletList = false
                    )
                }
                val precedingTail = if (index > 0) {
                    GemmaTextProcessor.extractContinuityTail(transformedSegments.lastOrNull())
                } else {
                    null
                }
                val systemInstruction = GemmaTextProcessor.buildSystemInstruction(
                    preset = preset,
                    customPrompt = customPrompt,
                    languageCode = languageCode,
                    customVocabulary = customVocabulary,
                    fieldContext = segmentFieldCtx,
                    precedingSegmentTail = precedingTail,
                    disableThinking = thinkingEffort == LlmThinkingEffort.OFF || thinkingEffort == LlmThinkingEffort.DEFAULT,
                    llmChoice = LlmModelChoice.GEMMA_4_E2B,
                    llmProvider = LlmProviderMode.OPENAI_API
                )
                val userPrompt = GemmaTextProcessor.buildUserPrompt(segment, languageCode)
                val maxOutputTokens = computeRemoteMaxOutputTokens(segment, thinkingEffort)

                val response = executeChatCompletionWithFallback(
                    chatUrl = chatUrl,
                    apiKey = apiKey,
                    model = cleanModel,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    maxTokens = maxOutputTokens,
                    thinkingEffort = thinkingEffort,
                    connectTimeoutMs = minOf(timeoutMs, 10_000),
                    readTimeoutMs = timeoutMs
                )
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

                transformedSegments.add(
                    GemmaTextProcessor.sanitizeGemmaOutput(
                        rawOutput = rawAssistant,
                        fallbackInput = segment,
                        preset = preset,
                        customPrompt = customPrompt,
                        precedingSegmentTail = precedingTail
                    )
                )
            }

            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            var cleaned = if (transformedSegments.size == 1) {
                transformedSegments[0]
            } else {
                GemmaTextProcessor.joinFormattedSegments(transformedSegments, preset)
            }
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

    /**
     * Encodes normalized [-1f, 1f] float PCM samples as a 16-bit mono little-endian RIFF/WAVE clip.
     */
    fun encodePcm16Wav(
        samples: FloatArray,
        sampleRateHz: Int = AUDIO_SAMPLE_RATE_HZ
    ): ByteArray {
        val dataBytes = samples.size * 2
        val totalSize = 44 + dataBytes
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(36 + dataBytes)
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16) // PCM fmt subchunk size
        buffer.putShort(1) // AudioFormat = 1 (PCM)
        buffer.putShort(1) // NumChannels = 1 (mono)
        buffer.putInt(sampleRateHz)
        buffer.putInt(sampleRateHz * 2) // ByteRate = sampleRate * 1 * 16 / 8
        buffer.putShort(2) // BlockAlign = 2
        buffer.putShort(16) // BitsPerSample = 16
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(dataBytes)
        for (sample in samples) {
            val clamped = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            val pcm = (clamped * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer.putShort(pcm)
        }
        return buffer.array()
    }

    /**
     * Runs a live smoke test against a remote OpenAI-compatible `/audio/transcriptions` endpoint
     * (either `Voz` STT or `Ear` language identification) using a brief silent calibration clip.
     */
    suspend fun testAudioConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        isEarMode: Boolean,
        timeoutSeconds: Int = DEFAULT_AUDIO_TIMEOUT_SECONDS
    ): OpenAiConnectionTestResult = withContext(Dispatchers.IO) {
        if (!isValidHttpUrl(baseUrl)) {
            return@withContext OpenAiConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "Invalid Base URL. Use http:// or https:// (e.g. http://192.168.1.50:8000/v1)."
            )
        }
        val cleanModel = model.trim()
        if (cleanModel.isEmpty()) {
            return@withContext OpenAiConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "Enter a model ID or tap Fetch."
            )
        }
        val targetUrl = resolveAudioTranscriptionsUrl(baseUrl)
        val timeoutMs = timeoutSeconds.coerceIn(5, 120) * 1_000
        val calibrationSamples = FloatArray(AUDIO_SAMPLE_RATE_HZ / 2)
        val wavBytes = encodePcm16Wav(calibrationSamples)
        val startNs = System.nanoTime()
        try {
            val response = postAudioMultipartWithFallback(
                urlString = targetUrl,
                apiKey = apiKey,
                model = cleanModel,
                wavBytes = wavBytes,
                language = null,
                promptHint = null,
                timeoutMs = timeoutMs
            )
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            if (response.statusCode in 200..299) {
                val roleLabel = if (isEarMode) "Remote Ear" else "Remote Voz"
                val preview = if (isEarMode) {
                    val lang = parseAudioLanguageDetectionResponse(response.body)
                    if (lang != null) "lang=\"$lang\"" else "calibration clip accepted"
                } else {
                    val (tx, lang) = parseTranscriptionResponse(response.body, calibrationSamples.size)
                    val clean = tx.text.trim()
                    when {
                        clean.isNotEmpty() -> "\"${clean.take(48)}\""
                        lang != null -> "OK (lang=$lang)"
                        else -> "calibration clip accepted"
                    }
                }
                OpenAiConnectionTestResult(
                    success = true,
                    latencyMs = elapsedMs,
                    httpStatus = response.statusCode,
                    previewOutput = preview,
                    message = "$roleLabel connected in ${elapsedMs} ms · $preview"
                )
            } else {
                OpenAiConnectionTestResult(
                    success = false,
                    latencyMs = elapsedMs,
                    httpStatus = response.statusCode,
                    message = formatHttpErrorMessage(response.statusCode, response.body, targetUrl)
                )
            }
        } catch (t: Throwable) {
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            OpenAiConnectionTestResult(
                success = false,
                latencyMs = elapsedMs,
                message = formatExceptionMessage(t, targetUrl)
            )
        }
    }

    /**
     * Transcribes 16 kHz mono audio on a remote OpenAI-compatible `/audio/transcriptions` endpoint (`Voz`).
     */
    suspend fun transcribeAudioRemote(
        samples: FloatArray,
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String? = null,
        customVocabulary: List<String> = emptyList(),
        timeoutSeconds: Int = DEFAULT_AUDIO_TIMEOUT_SECONDS
    ): RemoteAudioTranscriptionOutcome = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) {
            return@withContext RemoteAudioTranscriptionOutcome(
                transcription = Transcription("", emptyList(), 0.0),
                detectedLanguage = null,
                latencyMs = 0L
            )
        }
        val cleanModel = model.trim()
        if (!isValidHttpUrl(baseUrl) || cleanModel.isEmpty()) {
            return@withContext RemoteAudioTranscriptionOutcome(
                transcription = null,
                detectedLanguage = null,
                latencyMs = 0L,
                errorSummary = "Remote Voz URL or model is blank"
            )
        }
        val urlString = resolveAudioTranscriptionsUrl(baseUrl)
        val timeoutMs = timeoutSeconds.coerceIn(5, 120) * 1_000
        val wavBytes = encodePcm16Wav(samples)
        val promptHint = customVocabulary
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(24)
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
        val normalizedLangHint = normalizeLanguageCode(language)
        val startNs = System.nanoTime()
        try {
            val response = postAudioMultipartWithFallback(
                urlString = urlString,
                apiKey = apiKey,
                model = cleanModel,
                wavBytes = wavBytes,
                language = normalizedLangHint,
                promptHint = promptHint,
                timeoutMs = timeoutMs
            )
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            if (response.statusCode !in 200..299) {
                val msg = formatHttpErrorMessage(response.statusCode, response.body, urlString)
                return@withContext RemoteAudioTranscriptionOutcome(null, null, elapsedMs, msg)
            }
            val (transcription, detectedLang) = parseTranscriptionResponse(response.body, samples.size)
            RemoteAudioTranscriptionOutcome(
                transcription = transcription,
                detectedLanguage = detectedLang,
                latencyMs = elapsedMs
            )
        } catch (t: Throwable) {
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            RemoteAudioTranscriptionOutcome(
                transcription = null,
                detectedLanguage = null,
                latencyMs = elapsedMs,
                errorSummary = formatExceptionMessage(t, urlString)
            )
        }
    }

    /**
     * Identifies spoken language on a remote OpenAI-compatible `/audio/transcriptions` endpoint (`Ear`).
     */
    suspend fun identifyLanguageRemote(
        samples: FloatArray,
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutSeconds: Int = DEFAULT_AUDIO_TIMEOUT_SECONDS
    ): RemoteLanguageIdOutcome = withContext(Dispatchers.IO) {
        val cleanModel = model.trim()
        if (samples.isEmpty() || !isValidHttpUrl(baseUrl) || cleanModel.isEmpty()) {
            return@withContext RemoteLanguageIdOutcome(null, 0L, "Remote Ear endpoint not configured")
        }
        val urlString = resolveAudioTranscriptionsUrl(baseUrl)
        val timeoutMs = timeoutSeconds.coerceIn(5, 120) * 1_000
        val wavBytes = encodePcm16Wav(samples)
        val startNs = System.nanoTime()
        try {
            val response = postAudioMultipartWithFallback(
                urlString = urlString,
                apiKey = apiKey,
                model = cleanModel,
                wavBytes = wavBytes,
                language = null,
                promptHint = null,
                timeoutMs = timeoutMs
            )
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            if (response.statusCode !in 200..299) {
                val msg = formatHttpErrorMessage(response.statusCode, response.body, urlString)
                return@withContext RemoteLanguageIdOutcome(null, elapsedMs, msg)
            }
            val detected = parseAudioLanguageDetectionResponse(response.body)
            RemoteLanguageIdOutcome(
                languageCode = detected,
                latencyMs = elapsedMs,
                errorSummary = if (detected == null) "Unrecognized language output from remote Ear" else null
            )
        } catch (t: Throwable) {
            val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            RemoteLanguageIdOutcome(null, elapsedMs, formatExceptionMessage(t, urlString))
        }
    }

    /**
     * Normalizes ISO-639-1, ISO-639-2/3, BCP-47 tags (`en-US`), or full language names (`German`)
     * to a supported 2-letter `Voz.supportedLanguages` code.
     */
    internal fun normalizeLanguageCode(raw: String?): String? {
        val cleaned = raw?.trim()?.trim('"', '\'', '.', ',', ':', ';')?.lowercase(Locale.ROOT)
        if (cleaned.isNullOrEmpty() || cleaned == "auto" || cleaned == "unknown" || cleaned == "und") {
            return null
        }
        val supported = Voz.supportedLanguages
        if (cleaned in supported) return cleaned
        LANGUAGE_ALIAS_MAP[cleaned]?.takeIf { it in supported }?.let { return it }
        val primarySubtag = cleaned.substringBefore('-').substringBefore('_')
        if (primarySubtag in supported) return primarySubtag
        LANGUAGE_ALIAS_MAP[primarySubtag]?.takeIf { it in supported }?.let { return it }
        return null
    }

    /**
     * Parses an OpenAI `/audio/transcriptions` response (`verbose_json`, `json`, or plain `text`)
     * into a Desert Ant [Transcription] and optional normalized language code.
     */
    internal fun parseTranscriptionResponse(
        responseBody: String,
        samplesCount: Int,
        sampleRateHz: Int = AUDIO_SAMPLE_RATE_HZ
    ): Pair<Transcription, String?> {
        val fallbackDuration = samplesCount.toDouble() / sampleRateHz.toDouble()
        val trimmed = responseBody.trim()
        if (trimmed.isEmpty()) {
            return Transcription("", emptyList(), fallbackDuration) to null
        }
        if (!trimmed.startsWith("{")) {
            return Transcription(trimmed, emptyList(), fallbackDuration) to null
        }
        return runCatching {
            val root = JSONObject(trimmed)
            val text = root.optString("text", "").trim()
            val duration = root.optDouble("duration", fallbackDuration).let { d ->
                if (d.isFinite() && d > 0.0) d else fallbackDuration
            }
            val detectedLang = normalizeLanguageCode(
                root.optString("language", "").takeIf { it.isNotBlank() }
                    ?: root.optString("detected_language", "").takeIf { it.isNotBlank() }
            )
            val words = parseWordsFromTranscriptionJson(root)
            Transcription(text, words, duration) to detectedLang
        }.getOrElse {
            Transcription(trimmed, emptyList(), fallbackDuration) to null
        }
    }

    private fun parseWordsFromTranscriptionJson(root: JSONObject): List<Word> {
        val wordsList = ArrayList<Word>()
        fun appendWordArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val wordText = obj.optString("word", "").ifEmpty { obj.optString("text", "") }
                val start = obj.optDouble("start", Double.NaN)
                val end = obj.optDouble("end", Double.NaN)
                if (wordText.isNotEmpty() && start.isFinite() && end.isFinite()) {
                    wordsList.add(Word(wordText, start, end))
                }
            }
        }
        appendWordArray(root.optJSONArray("words"))
        if (wordsList.isEmpty()) {
            val segments = root.optJSONArray("segments")
            if (segments != null) {
                for (i in 0 until segments.length()) {
                    appendWordArray(segments.optJSONObject(i)?.optJSONArray("words"))
                }
            }
        }
        return wordsList
    }

    /**
     * Extracts a normalized language code from a remote `Ear` endpoint response, supporting
     * OpenAI `verbose_json` (`language` / `detected_language` / `probabilities`), JSON-inside-`text`,
     * or bare language text (`"de"`, `"German"`).
     */
    internal fun parseAudioLanguageDetectionResponse(responseBody: String): String? {
        val trimmed = responseBody.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{")) {
            return normalizeLanguageCode(trimmed)
        }
        return runCatching {
            val root = JSONObject(trimmed)
            val direct = listOf("language", "detected_language", "lang", "locale", "code")
                .firstNotNullOfOrNull { key ->
                    root.optString(key, "").takeIf { it.isNotBlank() }?.let(::normalizeLanguageCode)
                }
            if (direct != null) return@runCatching direct

            root.optJSONObject("result")
                ?.optString("language", "")
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeLanguageCode)
                ?.let { return@runCatching it }

            val probs = root.optJSONObject("probabilities") ?: root.optJSONObject("languages")
            if (probs != null) {
                var bestCode: String? = null
                var bestScore = Double.NEGATIVE_INFINITY
                val keys = probs.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val score = probs.optDouble(key, Double.NaN)
                    val normalized = normalizeLanguageCode(key)
                    if (normalized != null && score.isFinite() && score > bestScore) {
                        bestScore = score
                        bestCode = normalized
                    }
                }
                if (bestCode != null) return@runCatching bestCode
            }

            val innerText = root.optString("text", "").trim()
            if (innerText.startsWith("{")) {
                parseAudioLanguageDetectionResponse(innerText)
            } else {
                normalizeLanguageCode(innerText)
            }
        }.getOrNull()
    }

    private fun computeRemoteMaxOutputTokens(
        text: String,
        thinkingEffort: LlmThinkingEffort
    ): Int {
        val approxWords = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val baseTokens = (approxWords * 4 + 256).coerceIn(256, 12_288)
        val thinkingHeadroom = when (thinkingEffort) {
            LlmThinkingEffort.DEFAULT,
            LlmThinkingEffort.OFF -> 0
            LlmThinkingEffort.LOW -> 1_024
            LlmThinkingEffort.MEDIUM -> 2_048
            LlmThinkingEffort.HIGH -> 4_096
        }
        return (baseTokens + thinkingHeadroom).coerceIn(256, 16_384)
    }

    private suspend fun executeChatCompletionWithFallback(
        chatUrl: String,
        apiKey: String,
        model: String,
        systemInstruction: String,
        userPrompt: String,
        maxTokens: Int?,
        thinkingEffort: LlmThinkingEffort,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResult {
        val initialPayload = buildChatCompletionPayload(
            model = model,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            includeSamplingParams = true,
            maxTokens = maxTokens,
            thinkingEffort = thinkingEffort
        )
        var response = executeHttpRequest(
            urlString = chatUrl,
            method = "POST",
            apiKey = apiKey,
            body = initialPayload.toString(),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
        if ((response.statusCode == 400 || response.statusCode == 422) && shouldRetryWithoutOptionalParams(response.body)) {
            val dropEffort = thinkingEffort != LlmThinkingEffort.DEFAULT && mentionsReasoningParam(response.body)
            val fallbackEffort = if (dropEffort) LlmThinkingEffort.DEFAULT else thinkingEffort
            val retryPayload = buildChatCompletionPayload(
                model = model,
                systemInstruction = systemInstruction,
                userPrompt = userPrompt,
                includeSamplingParams = false,
                maxTokens = null,
                thinkingEffort = fallbackEffort
            )
            response = executeHttpRequest(
                urlString = chatUrl,
                method = "POST",
                apiKey = apiKey,
                body = retryPayload.toString(),
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            )
            if ((response.statusCode == 400 || response.statusCode == 422) &&
                fallbackEffort != LlmThinkingEffort.DEFAULT &&
                mentionsReasoningParam(response.body)
            ) {
                val minimalPayload = buildChatCompletionPayload(
                    model = model,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    includeSamplingParams = false,
                    maxTokens = null,
                    thinkingEffort = LlmThinkingEffort.DEFAULT
                )
                response = executeHttpRequest(
                    urlString = chatUrl,
                    method = "POST",
                    apiKey = apiKey,
                    body = minimalPayload.toString(),
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs
                )
            }
        }
        return response
    }

    private suspend fun postAudioMultipartWithFallback(
        urlString: String,
        apiKey: String,
        model: String,
        wavBytes: ByteArray,
        language: String?,
        promptHint: String?,
        timeoutMs: Int
    ): HttpResult {
        fun buildFields(verbose: Boolean): List<Pair<String, String>> = buildList {
            add("model" to model)
            if (!language.isNullOrBlank()) add("language" to language)
            if (!promptHint.isNullOrBlank()) add("prompt" to promptHint)
            if (verbose) {
                add("response_format" to "verbose_json")
                add("timestamp_granularities[]" to "word")
            } else {
                add("response_format" to "json")
            }
        }

        val boundary1 = "----OpenFlowBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val body1 = buildMultipartBody(
            boundary = boundary1,
            fields = buildFields(verbose = true),
            fileBytes = wavBytes
        )
        var response = executeBinaryHttpPost(
            urlString = urlString,
            apiKey = apiKey,
            contentType = "multipart/form-data; boundary=$boundary1",
            bodyBytes = body1,
            connectTimeoutMs = minOf(timeoutMs, 10_000),
            readTimeoutMs = timeoutMs
        )
        if ((response.statusCode == 400 || response.statusCode == 422) && shouldRetryAudioAsSimpleJson(response.body)) {
            val boundary2 = "----OpenFlowBoundary${UUID.randomUUID().toString().replace("-", "")}"
            val body2 = buildMultipartBody(
                boundary = boundary2,
                fields = buildFields(verbose = false),
                fileBytes = wavBytes
            )
            response = executeBinaryHttpPost(
                urlString = urlString,
                apiKey = apiKey,
                contentType = "multipart/form-data; boundary=$boundary2",
                bodyBytes = body2,
                connectTimeoutMs = minOf(timeoutMs, 10_000),
                readTimeoutMs = timeoutMs
            )
        }
        return response
    }

    private fun buildMultipartBody(
        boundary: String,
        fields: List<Pair<String, String>>,
        fileFieldName: String = "file",
        fileName: String = "audio.wav",
        fileContentType: String = "audio/wav",
        fileBytes: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream(fileBytes.size + 1024)
        for ((name, value) in fields) {
            out.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            out.write(value.toByteArray(StandardCharsets.UTF_8))
            out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        out.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
        out.write(
            "Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n"
                .toByteArray(StandardCharsets.UTF_8)
        )
        out.write("Content-Type: $fileContentType\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        out.write(fileBytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    private data class HttpResult(val statusCode: Int, val body: String)

    private suspend fun executeHttpRequest(
        urlString: String,
        method: String,
        apiKey: String,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResult = executeRawHttpRequest(
        urlString = urlString,
        method = method,
        apiKey = apiKey,
        contentType = if (body != null) "application/json; charset=utf-8" else null,
        bodyBytes = body?.toByteArray(StandardCharsets.UTF_8),
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    )

    private suspend fun executeBinaryHttpPost(
        urlString: String,
        apiKey: String,
        contentType: String,
        bodyBytes: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResult = executeRawHttpRequest(
        urlString = urlString,
        method = "POST",
        apiKey = apiKey,
        contentType = contentType,
        bodyBytes = bodyBytes,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    )

    private suspend fun executeRawHttpRequest(
        urlString: String,
        method: String,
        apiKey: String,
        contentType: String?,
        bodyBytes: ByteArray?,
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
            if (bodyBytes != null && contentType != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        cont.invokeOnCancellation {
            runCatching { conn.disconnect() }
        }
        try {
            if (bodyBytes != null) {
                conn.outputStream.use { os ->
                    os.write(bodyBytes)
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

    private fun mentionsReasoningParam(errorBody: String): Boolean {
        val lower = errorBody.lowercase(Locale.ROOT)
        return lower.contains("reasoning_effort") || lower.contains("reasoning") || lower.contains("'none'")
    }

    private fun shouldRetryWithoutOptionalParams(errorBody: String): Boolean {
        val lower = errorBody.lowercase(Locale.ROOT)
        return lower.contains("temperature") ||
            lower.contains("max_tokens") ||
            lower.contains("max_completion_tokens") ||
            lower.contains("reasoning_effort") ||
            lower.contains("unsupported parameter") ||
            lower.contains("unknown_parameter")
    }

    private fun shouldRetryAudioAsSimpleJson(errorBody: String): Boolean {
        val lower = errorBody.lowercase(Locale.ROOT)
        return lower.contains("verbose_json") ||
            lower.contains("timestamp_granularities") ||
            lower.contains("response_format") ||
            lower.contains("extra inputs") ||
            lower.contains("unsupported") ||
            lower.contains("unknown")
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
