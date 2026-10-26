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

package com.voicebubble.openflow.settings

import ai.desertant.ear.Ear
import ai.desertant.voz.Voz
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale

/**
 * The 25 European languages supported by Desert Ant Labs `Voz` (Parakeet TDT 0.6B v3).
 */
private val VOZ_SUPPORTED_LANGUAGES: Set<String> = linkedSetOf(
    "bg", "cs", "da", "de", "el",
    "en", "es", "et", "fi", "fr",
    "hr", "hu", "it", "lt", "lv",
    "mt", "nl", "pl", "pt", "ro",
    "ru", "sk", "sl", "sv", "uk"
)

/**
 * Exposes `Voz.supportedLanguages` on `Voz.Companion` to match the Desert Ant SDK specification.
 */
val Voz.Companion.supportedLanguages: Set<String>
    get() = VOZ_SUPPORTED_LANGUAGES

enum class DesertAntModel(
    val id: String,
    val shortLabel: String,
    val displayName: String,
    val roleSummary: String,
    val sizeMb: Int,
    val expectedBytes: Long,
    val isRequired: Boolean,
    val isExperimental: Boolean = false
) {
    VOZ(
        id = "voz",
        shortLabel = "Voz",
        displayName = "Voz — Speech Recognition",
        roleSummary = "Required • Core transcription across 25 European languages",
        sizeMb = 1187,
        expectedBytes = 1_244_258_225L,
        isRequired = true
    ),
    EAR(
        id = "ear",
        shortLabel = "Ear",
        displayName = "Ear — Auto Language Detection",
        roleSummary = "Automatically detects spoken language before transcription",
        sizeMb = 10,
        expectedBytes = 10_738_698L,
        isRequired = false
    ),
    PUNCT(
        id = "punct",
        shortLabel = "Punct",
        displayName = "Punct — Text & Punctuation Cleanup",
        roleSummary = "XLM-RoBERTa token tagger that fixes mid-sentence periods, commas & casing on the NPU (0% hallucination)",
        sizeMb = 282,
        expectedBytes = 295_740_930L,
        isRequired = false
    ),
    GEMMA_4_E2B(
        id = "gemma4_e2b",
        shortLabel = "Gemma 4 E2B",
        displayName = "Gemma 4 E2B — AI Reformatting",
        roleSummary = "Experimental • On-device Gemma 4 E2B LLM on NPU for removing 'um'/fillers, smart reformatting & custom prompts",
        sizeMb = 2468,
        expectedBytes = 2_588_147_712L,
        isRequired = false,
        isExperimental = true
    );
}

/**
 * Selectable system prompt presets (plus [CUSTOM]) for Experimental Gemma 4 E2B post-processing.
 */
enum class GemmaPromptPreset(
    val id: String,
    val title: String,
    val shortLabel: String,
    val subtitle: String,
    val defaultSystemPrompt: String
) {
    CLEAN_AND_POLISH(
        id = "clean_polish",
        title = "Clean & Polish (Default)",
        shortLabel = "Clean & Polish",
        subtitle = "Removes fillers & false starts; formats spoken numbers, dates, currencies, versions, units, paragraphs & punctuation",
        defaultSystemPrompt = """Clean and format the raw spoken dictation into natural, ready-to-send written text while keeping the speaker's exact language, voice, and meaning:
- Disfluencies & pauses: Remove vocal fillers ('um', 'uh', 'er', 'ah', 'äh', 'ähm', 'hm', filler 'like', 'you know'), stutters, accidental repetitions, and spoken self-corrections (keep only the intended correction). Fix false mid-sentence periods and broken casing caused by pauses.
- Numbers, dates & units: Convert spoken numbers into proper digits following the transcript language's conventions — including decimals ('3.5' / '3,5'), percentages ('25%'), currencies ('$50', '49,99 €'), dates, clock times ('14:30', '3:30 PM'), version numbers ('2.6.9'), ranges ('10–15'), and measurement/tech units ('8 GB', '500 ms', '16 kHz', '24 °C'). Keep small idiomatic number words spelled out when digits would look unnatural (e.g., 'one of the', 'a couple of', 'ein paar').
- Acronyms & symbols: Properly capitalize acronyms and tech terms ('NPU', 'GPU', 'CPU', 'RAM', 'LLM', 'API', 'USB', 'OK') and format clearly dictated email addresses, URLs, or symbols ('name@example.com').
- Structure & punctuation: Fix punctuation and capitalization, apply explicit spoken formatting cues ('new line' / 'neue Zeile', 'new paragraph' / 'neuer Absatz') as actual breaks (removing the spoken cue), group distinct topics into clean paragraphs, and format clear spoken enumerations cleanly without adding new information."""
    ),
    STRICT_CLEANUP(
        id = "strict_cleanup",
        title = "Strict Filler & Grammar Fix",
        shortLabel = "Strict Cleanup",
        subtitle = "Minimalist cleanup: strips vocal hesitations, formats spoken numbers/units & fixes punctuation with zero rephrasing",
        defaultSystemPrompt = "Perform strictly minimal dictation cleanup: remove obvious vocal fillers ('um', 'uh', 'äh', 'ähm', 'er', 'hm') and stutters, convert clearly spoken numbers, decimals, percentages, currencies, dates, times, version numbers, and units into digits, capitalize standard acronyms ('NPU', 'GPU', 'RAM', 'LLM'), and repair broken punctuation, mid-sentence periods, and capitalization. Do not rephrase, reorder, summarize, or change the speaker's word choice."
    ),
    CONCISE_DIRECT(
        id = "concise_direct",
        title = "Concise & Direct",
        shortLabel = "Concise",
        subtitle = "Tightens rambling spoken thoughts into clear, crisp sentences with clean number & unit formatting",
        defaultSystemPrompt = "Rewrite the spoken dictation into clear, concise, direct prose. Remove all vocal fillers, hedging, tangents, and redundant phrasing, and format spoken numbers, decimals, percentages, currencies, dates, times, versions, and units cleanly as digits while preserving every fact, name, and the speaker's original language."
    ),
    PROFESSIONAL_MESSAGE(
        id = "professional_message",
        title = "Professional Message / Email",
        shortLabel = "Professional",
        subtitle = "Formats dictation into a polished, well-structured professional message or email with clean paragraphs & numbers",
        defaultSystemPrompt = "Format the spoken dictation into a polished, natural, well-structured professional message or email. Remove all spoken hesitations and false starts, convert spoken numbers, dates, times, currencies, versions, and units cleanly into standard written digits, organize distinct thoughts into clean paragraphs, and maintain a courteous, professional tone in the speaker's original language without inventing placeholders."
    ),
    STRUCTURED_NOTES(
        id = "structured_notes",
        title = "Bullet Points & Action Items",
        shortLabel = "Bullet Points",
        subtitle = "Organizes spoken thoughts, meeting notes, or tasks into clean, scannable bullet points",
        defaultSystemPrompt = "Transform the spoken dictation into a clean, well-organized list of concise bullet points using '- '. Remove all vocal fillers and repetitions, format spoken numbers, dates, times, versions, currencies, and units cleanly as digits, group related ideas or action items clearly, and keep the speaker's original language."
    ),
    CUSTOM(
        id = "custom",
        title = "Custom System Prompt",
        shortLabel = "Custom",
        subtitle = "Write your own custom system instruction for how Gemma 4 E2B transforms dictated speech",
        defaultSystemPrompt = "Remove vocal fillers ('um', 'uh', 'like'), stutters, and false starts, convert spoken numbers, dates, versions, and units into proper digits, and format the text cleanly while keeping the original meaning."
    );

    companion object {
        fun fromId(id: String?): GemmaPromptPreset {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CLEAN_AND_POLISH
        }
    }
}

/**
 * Hardware acceleration preference for LiteRT on-device audio inference.
 */
enum class HardwareAccelerator(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val description: String
) {
    AUTO(
        id = "auto",
        displayName = "Auto (NPU / GPU / CPU)",
        shortLabel = "Auto",
        description = "Automatically selects Qualcomm Hexagon NPU, MediaTek NeuroPilot, Tensor TPU, Exynos NPU, or OpenCL GPU with XNNPACK fallback"
    ),
    NPU(
        id = "npu",
        displayName = "NPU / AI Accelerator",
        shortLabel = "NPU",
        description = "Prioritizes Qualcomm Hexagon HTP/QNN, MediaTek NeuroPilot APU, Google Tensor TPU, Exynos ENN, Kirin HiAI & NNAPI"
    ),
    GPU(
        id = "gpu",
        displayName = "GPU (OpenCL / GLES)",
        shortLabel = "GPU",
        description = "Accelerates neural audio models on Adreno / Mali / Immortalis GPU via LiteRT OpenCL/GLES with CPU fallback"
    ),
    CPU(
        id = "cpu",
        displayName = "CPU (XNNPACK)",
        shortLabel = "CPU",
        description = "Multi-threaded ARM NEON / XNNPACK execution; lowest memory footprint and maximum stability"
    );

    companion object {
        fun fromId(id: String?): HardwareAccelerator {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Immutable snapshot of user-configurable Desert Ant model settings persisted in SharedPreferences.
 */
data class ModelSettings(
    val earEnabled: Boolean = true,
    val punctEnabled: Boolean = false,
    val gemmaEnabled: Boolean = false,
    val gemmaPromptPreset: GemmaPromptPreset = GemmaPromptPreset.CLEAN_AND_POLISH,
    val gemmaCustomPrompt: String = "",
    val preferredLanguage: String = defaultPreferredLanguage(),
    val customVocabulary: List<String> = emptyList(),
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO
) {
    /**
     * `Voz` is the core STT engine and cannot be disabled.
     */
    val vozEnabled: Boolean = true

    /**
     * Resolves the active task instruction for Gemma 4 E2B based on [gemmaPromptPreset] and [gemmaCustomPrompt].
     */
    fun resolvedGemmaTaskInstruction(): String {
        return if (gemmaPromptPreset == GemmaPromptPreset.CUSTOM) {
            gemmaCustomPrompt.trim().ifEmpty { GemmaPromptPreset.CUSTOM.defaultSystemPrompt }
        } else {
            gemmaPromptPreset.defaultSystemPrompt
        }
    }

    /**
     * Active disk footprint in MB based on currently enabled models:
     * - `Voz`: 1,187 MB
     * - `Ear`: +10 MB
     * - `Punct`: +282 MB
     * - `Gemma 4 E2B` (Experimental): +2,468 MB
     */
    val activeDiskFootprintMb: Int
        get() = DesertAntModel.VOZ.sizeMb +
            (if (earEnabled) DesertAntModel.EAR.sizeMb else 0) +
            (if (punctEnabled) DesertAntModel.PUNCT.sizeMb else 0) +
            (if (gemmaEnabled) DesertAntModel.GEMMA_4_E2B.sizeMb else 0)

    /**
     * Estimated peak RAM in MB based on loaded LiteRT/ONNX/LiteRT-LM model weights + runtime buffers:
     * - `Voz` (1,187 MB weights + runtime buffers): 1,400 MB
     * - `Ear` (10 MB weights + runtime buffers): +50 MB
     * - `Punct` (INT8 XLM-RoBERTa token classifier on NPU/XNNPACK): +120 MB
     * - `Gemma 4 E2B` (2/4/8-bit QAT weights + mmap embeddings via LiteRT-LM): +850 MB
     */
    val estimatedPeakRamMb: Int
        get() = 1400 +
            (if (earEnabled) 50 else 0) +
            (if (punctEnabled) 120 else 0) +
            (if (gemmaEnabled) 850 else 0)

    fun isModelEnabled(model: DesertAntModel): Boolean = when (model) {
        DesertAntModel.VOZ -> true
        DesertAntModel.EAR -> earEnabled
        DesertAntModel.PUNCT -> punctEnabled
        DesertAntModel.GEMMA_4_E2B -> gemmaEnabled
    }

    fun enabledModels(): List<DesertAntModel> = buildList {
        add(DesertAntModel.VOZ)
        if (earEnabled) add(DesertAntModel.EAR)
        if (punctEnabled) add(DesertAntModel.PUNCT)
        if (gemmaEnabled) add(DesertAntModel.GEMMA_4_E2B)
    }

    companion object {
        const val PREFS_NAME = "openflow_model_settings"
        const val KEY_EAR_ENABLED = "ear_enabled"
        const val KEY_PUNCT_ENABLED = "punct_enabled"
        const val KEY_GEMMA_ENABLED = "gemma_enabled"
        const val KEY_GEMMA_PROMPT_PRESET = "gemma_prompt_preset"
        const val KEY_GEMMA_CUSTOM_PROMPT = "gemma_custom_prompt"
        const val KEY_PREFERRED_LANGUAGE = "preferred_language"
        const val KEY_CUSTOM_VOCABULARY = "custom_vocabulary"
        const val KEY_HARDWARE_ACCELERATOR = "hardware_accelerator"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        fun defaultPreferredLanguage(): String {
            val systemLang = Locale.getDefault().language.lowercase(Locale.ROOT)
            return if (systemLang in Voz.supportedLanguages) systemLang else "en"
        }

        fun getPreferences(context: Context): SharedPreferences {
            return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun load(context: Context): ModelSettings {
            val prefs = getPreferences(context)
            val defaultLang = defaultPreferredLanguage()
            val rawLang = prefs.getString(KEY_PREFERRED_LANGUAGE, defaultLang) ?: defaultLang
            val sanitizedLang = if (rawLang in Voz.supportedLanguages) rawLang else defaultLang

            val vocabJson = prefs.getString(KEY_CUSTOM_VOCABULARY, null)
            val vocabList = parseVocabularyJson(vocabJson)
            val accel = HardwareAccelerator.fromId(
                prefs.getString(KEY_HARDWARE_ACCELERATOR, HardwareAccelerator.AUTO.id)
            )
            val preset = GemmaPromptPreset.fromId(
                prefs.getString(KEY_GEMMA_PROMPT_PRESET, GemmaPromptPreset.CLEAN_AND_POLISH.id)
            )
            val customPrompt = prefs.getString(KEY_GEMMA_CUSTOM_PROMPT, "") ?: ""

            return ModelSettings(
                earEnabled = prefs.getBoolean(KEY_EAR_ENABLED, true),
                punctEnabled = prefs.getBoolean(KEY_PUNCT_ENABLED, false),
                gemmaEnabled = prefs.getBoolean(KEY_GEMMA_ENABLED, false),
                gemmaPromptPreset = preset,
                gemmaCustomPrompt = customPrompt,
                preferredLanguage = sanitizedLang,
                customVocabulary = vocabList,
                hardwareAccelerator = accel
            )
        }

        fun save(context: Context, settings: ModelSettings) {
            val sanitizedVocab = settings.customVocabulary
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            val jsonArray = JSONArray()
            sanitizedVocab.forEach { jsonArray.put(it) }

            val validLang = if (settings.preferredLanguage in Voz.supportedLanguages) {
                settings.preferredLanguage
            } else {
                defaultPreferredLanguage()
            }

            getPreferences(context).edit()
                .putBoolean(KEY_EAR_ENABLED, settings.earEnabled)
                .putBoolean(KEY_PUNCT_ENABLED, settings.punctEnabled)
                .putBoolean(KEY_GEMMA_ENABLED, settings.gemmaEnabled)
                .putString(KEY_GEMMA_PROMPT_PRESET, settings.gemmaPromptPreset.id)
                .putString(KEY_GEMMA_CUSTOM_PROMPT, settings.gemmaCustomPrompt)
                .putString(KEY_PREFERRED_LANGUAGE, validLang)
                .putString(KEY_CUSTOM_VOCABULARY, jsonArray.toString())
                .putString(KEY_HARDWARE_ACCELERATOR, settings.hardwareAccelerator.id)
                .apply()
        }

        fun setOnboardingCompleted(context: Context, completed: Boolean) {
            getPreferences(context).edit()
                .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
                .apply()
        }

        fun isOnboardingCompleted(context: Context): Boolean {
            return getPreferences(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
        }

        private fun parseVocabularyJson(rawJson: String?): List<String> {
            if (rawJson.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(rawJson)
                buildList {
                    for (i in 0 until array.length()) {
                        val term = array.optString(i, "").trim()
                        if (term.isNotEmpty()) {
                            add(term)
                        }
                    }
                }.distinct()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun languageDisplayName(isoCode: String): String {
            val locale = Locale(isoCode)
            val display = locale.getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            return if (display.isNotBlank() && !display.equals(isoCode, ignoreCase = true)) {
                "$display ($isoCode)"
            } else {
                isoCode.uppercase(Locale.ROOT)
            }
        }
    }
}

/**
 * Helper object for checking on-disk model cache status in O(1) time without spawning native
 * JNI handles, managing verified artifact stamps, and deleting inactive model files on disk.
 */
object DesertAntModelStorage {

    private const val VERIFIED_STAMP_FILE = ".openflow_verified"

    private val MODEL_REVISIONS = mapOf(
        DesertAntModel.VOZ to "v0.4.0",
        DesertAntModel.EAR to "v0.2.0",
        DesertAntModel.PUNCT to "v1.0.0",
        DesertAntModel.GEMMA_4_E2B to "v1.0.0"
    )

    // In-memory cache of (targetDir.absolutePath -> (lastModifiedSum, sizeSum)) for verified model directories
    // so repeated per-chunk / per-UI checks avoid redundant disk stat() syscalls when nothing on disk changed.
    private val directoryCompleteCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    @Volatile
    private var bundledExtractionChecked = false

    @Volatile
    private var legacyModelsCleanedUp = false

    fun modelRevision(model: DesertAntModel): String {
        return MODEL_REVISIONS.getValue(model)
    }

    fun modelCacheRoot(context: Context, model: DesertAntModel): File {
        return File(context.applicationContext.cacheDir, "desert-ant-models/${model.id}")
    }

    fun modelRevisionDir(context: Context, model: DesertAntModel): File {
        return File(modelCacheRoot(context, model), modelRevision(model))
    }

    /**
     * Automatically removes any leftover `clear` or `uhm` model directories from previous releases
     * to reclaim up to 70 MB of device storage.
     */
    fun cleanupLegacyRemovedModels(context: Context) {
        if (legacyModelsCleanedUp) return
        legacyModelsCleanedUp = true
        try {
            val modelsBase = File(context.applicationContext.cacheDir, "desert-ant-models")
            if (modelsBase.exists()) {
                for (legacyId in listOf("clear", "uhm")) {
                    val dir = File(modelsBase, legacyId)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * If this APK is the Offline-Bundled build containing pre-packaged model files under
     * `assets/desert-ant-models/`, extracts any missing model files and `.dal-meta/manifest`
     * into `context.cacheDir/desert-ant-models/<id>/<revision>` automatically.
     */
    @Synchronized
    fun ensureBundledModelsExtracted(context: Context) {
        cleanupLegacyRemovedModels(context)
        if (bundledExtractionChecked) return
        val appContext = context.applicationContext
        val assetManager = appContext.assets
        try {
            val bundledModels = assetManager.list("desert-ant-models") ?: emptyArray()
            if (bundledModels.isEmpty()) {
                bundledExtractionChecked = true
                return
            }
            for ((model, rev) in MODEL_REVISIONS) {
                if (model.id !in bundledModels) continue
                val assetBase = "desert-ant-models/${model.id}/$rev"
                val assetFiles = assetManager.list(assetBase) ?: emptyArray()
                if (assetFiles.isEmpty()) continue

                val targetDir = File(modelCacheRoot(appContext, model), rev)
                val targetManifest = File(File(targetDir, ".dal-meta"), "manifest")
                if (targetManifest.exists() && targetManifest.length() > 0L) {
                    continue
                }

                targetDir.mkdirs()
                var hasDalMeta = false
                for (entry in assetFiles) {
                    if (entry == "dal_meta") {
                        hasDalMeta = true
                    } else {
                        val outFile = File(targetDir, entry)
                        if (!outFile.exists() || outFile.length() == 0L) {
                            val tempFile = File(targetDir, "$entry.part")
                            assetManager.open("$assetBase/$entry").use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output, bufferSize = 256 * 1024)
                                }
                            }
                            if (outFile.exists()) outFile.delete()
                            tempFile.renameTo(outFile)
                        }
                    }
                }
                if (hasDalMeta) {
                    val metaDir = File(targetDir, ".dal-meta")
                    metaDir.mkdirs()
                    assetManager.open("$assetBase/dal_meta/manifest").use { input ->
                        targetManifest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                val specArtifacts = ModelDownloadManager.MODEL_SPECS[model]?.second
                if (specArtifacts != null) {
                    writeModelManifestAndStamp(targetDir, specArtifacts)
                }
            }
            bundledExtractionChecked = true
        } catch (_: Throwable) {
            bundledExtractionChecked = true
        }
    }

    /**
     * Fast, non-blocking O(1) check that verifies whether all required model artifacts exist
     * on disk with their exact expected byte sizes and no incomplete `.part` files.
     *
     * Critically, this does NOT instantiate native `Voz`/`Ear` JNI objects, avoiding
     * `ModelStore`'s 1.2 GB pure-Swift `SoftwareSHA256` memory spike on UI/background threads.
     */
    fun isModelDownloaded(context: Context, model: DesertAntModel): Boolean {
        val appContext = context.applicationContext
        ensureBundledModelsExtracted(appContext)
        return try {
            val (revision, artifacts) = ModelDownloadManager.MODEL_SPECS[model] ?: return false
            val targetDir = File(modelCacheRoot(appContext, model), revision)
            isModelDirectoryComplete(targetDir, artifacts)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks whether all required model artifacts exist on disk AND have valid SHA-256 verification
     * stamps in `.openflow_verified`.
     */
    fun isModelVerified(context: Context, model: DesertAntModel): Boolean {
        val appContext = context.applicationContext
        ensureBundledModelsExtracted(appContext)
        return try {
            val (revision, artifacts) = ModelDownloadManager.MODEL_SPECS[model] ?: return false
            val targetDir = File(modelCacheRoot(appContext, model), revision)
            areAllArtifactsVerified(targetDir, artifacts)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Pure directory completeness check testable without Android Context.
     */
    fun isModelDirectoryComplete(targetDir: File, artifacts: List<ModelFileArtifact>): Boolean {
        if (!targetDir.exists() || !targetDir.isDirectory || artifacts.isEmpty()) {
            directoryCompleteCache.remove(targetDir.absolutePath)
            return false
        }
        val manifestFile = File(File(targetDir, ".dal-meta"), "manifest")
        val dirKey = targetDir.absolutePath
        if (manifestFile.exists() && manifestFile.length() > 0L) {
            val cached = directoryCompleteCache[dirKey]
            if (cached != null) {
                val dirLastMod = targetDir.lastModified()
                if (dirLastMod > 0L && dirLastMod == cached.first && artifacts.size.toLong() == cached.second) {
                    return true
                }
            }
        }
        for (artifact in artifacts) {
            val finalFile = File(targetDir, artifact.relativePath)
            val partFile = File(targetDir, "${artifact.relativePath}.part")
            if (partFile.exists()) {
                directoryCompleteCache.remove(dirKey)
                return false
            }
            if (!finalFile.exists() || !finalFile.isFile || finalFile.length() != artifact.sizeBytes) {
                directoryCompleteCache.remove(dirKey)
                return false
            }
        }
        if (!manifestFile.exists()) {
            try {
                writeModelManifest(targetDir, artifacts)
            } catch (_: Throwable) {
            }
        }
        val finalDirMod = targetDir.lastModified()
        if (finalDirMod > 0L) {
            directoryCompleteCache[dirKey] = finalDirMod to artifacts.size.toLong()
        }
        return true
    }

    /**
     * Checks whether all [artifacts] in [targetDir] exist and have valid `.openflow_verified` stamps.
     */
    fun areAllArtifactsVerified(targetDir: File, artifacts: List<ModelFileArtifact>): Boolean {
        if (!isModelDirectoryComplete(targetDir, artifacts)) return false
        return artifacts.all { isArtifactAlreadyVerified(targetDir, it) }
    }

    /**
     * Checks if a single [artifact] in [targetDir] has already been SHA-256 verified and recorded
     * in `.openflow_verified` with matching file size and lastModified timestamp.
     */
    fun isArtifactAlreadyVerified(targetDir: File, artifact: ModelFileArtifact): Boolean {
        val finalFile = File(targetDir, artifact.relativePath)
        val partFile = File(targetDir, "${artifact.relativePath}.part")
        if (partFile.exists()) return false
        if (!finalFile.exists() || !finalFile.isFile || finalFile.length() != artifact.sizeBytes) {
            return false
        }
        val stampFile = File(targetDir, VERIFIED_STAMP_FILE)
        if (!stampFile.exists()) return false
        return try {
            val expectedLine = artifactStampLine(finalFile, artifact)
            stampFile.readLines().any { it.trim() == expectedLine }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Appends or updates the verified stamp entry for [artifact] in [targetDir].
     */
    @Synchronized
    fun markArtifactVerified(targetDir: File, artifact: ModelFileArtifact) {
        val finalFile = File(targetDir, artifact.relativePath)
        if (!finalFile.exists() || finalFile.length() != artifact.sizeBytes) return
        try {
            val stampFile = File(targetDir, VERIFIED_STAMP_FILE)
            val prefix = "${artifact.relativePath}\t"
            val existing = if (stampFile.exists()) {
                stampFile.readLines().filter { it.isNotBlank() && !it.startsWith(prefix) }
            } else {
                emptyList()
            }
            val updated = existing + artifactStampLine(finalFile, artifact)
            stampFile.writeText(updated.joinToString("\n") + "\n")
            directoryCompleteCache.remove(targetDir.absolutePath)
        } catch (_: Throwable) {
        }
    }

    /**
     * Writes `.dal-meta/manifest` for [artifacts] in [targetDir] without touching `.openflow_verified`.
     */
    @Synchronized
    fun writeModelManifest(targetDir: File, artifacts: List<ModelFileArtifact>) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val metaDir = File(targetDir, ".dal-meta")
        metaDir.mkdirs()
        val manifestFile = File(metaDir, "manifest")
        val rLines = artifacts.joinToString("\n") { "R\t${it.relativePath}" }
        val fLines = artifacts.joinToString("\n") { "F\t${it.relativePath}\t${it.sizeBytes}\t${it.sha256}" }
        manifestFile.writeText("DAL1\n$rLines\n$fLines\n")
    }

    /**
     * Writes `.dal-meta/manifest` and `.openflow_verified` for all [artifacts] in [targetDir]
     * after their SHA-256 hashes have been verified.
     */
    @Synchronized
    fun writeModelManifestAndStamp(targetDir: File, artifacts: List<ModelFileArtifact>) {
        writeModelManifest(targetDir, artifacts)
        val stampLines = artifacts.mapNotNull { artifact ->
            val f = File(targetDir, artifact.relativePath)
            if (f.exists() && f.length() == artifact.sizeBytes) {
                artifactStampLine(f, artifact)
            } else {
                null
            }
        }
        if (stampLines.size == artifacts.size) {
            File(targetDir, VERIFIED_STAMP_FILE).writeText(stampLines.joinToString("\n") + "\n")
        }
        directoryCompleteCache.remove(targetDir.absolutePath)
    }

    private fun artifactStampLine(file: File, artifact: ModelFileArtifact): String {
        return "${artifact.relativePath}\t${file.length()}\t${file.lastModified()}\t${artifact.sha256.lowercase(Locale.ROOT)}"
    }

    fun areAllEnabledModelsDownloaded(context: Context, settings: ModelSettings = ModelSettings.load(context)): Boolean {
        return settings.enabledModels().all { isModelDownloaded(context, it) }
    }

    fun countDownloadedModels(context: Context): Int {
        return DesertAntModel.entries.count { isModelDownloaded(context, it) }
    }

    /**
     * Downloads [model] on [Dispatchers.IO] while emitting progress in `0.0f..1.0f`.
     */
    suspend fun downloadModel(
        context: Context,
        model: DesertAntModel,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (isModelDownloaded(appContext, model)) {
            onProgress(1.0f)
            return@withContext
        }
        ModelDownloadManager.downloadSingleModelDirect(appContext, model, onProgress)
    }

    /**
     * Deletes cached files on disk for a non-required model (`Ear`).
     * `Voz` is locked and cannot be deleted.
     */
    fun deleteModelCache(context: Context, model: DesertAntModel): Boolean {
        if (model.isRequired) return false
        val revDir = modelRevisionDir(context, model)
        directoryCompleteCache.remove(revDir.absolutePath)
        val root = modelCacheRoot(context, model)
        return if (root.exists()) {
            root.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Deletes all downloaded optional models that are currently toggled OFF in [settings].
     */
    fun deleteAllInactiveModels(context: Context, settings: ModelSettings): Int {
        var deletedCount = 0
        for (model in DesertAntModel.entries) {
            if (!model.isRequired && !settings.isModelEnabled(model) && isModelDownloaded(context, model)) {
                if (deleteModelCache(context, model)) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }
}
