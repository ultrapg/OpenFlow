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

import ai.desertant.clear.Clear
import ai.desertant.ear.Ear
import ai.desertant.uhm.Uhm
import ai.desertant.voz.Voz
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val displayName: String,
    val roleSummary: String,
    val sizeMb: Int,
    val expectedBytes: Long,
    val isRequired: Boolean
) {
    VOZ(
        id = "voz",
        displayName = "Voz — Speech Recognition",
        roleSummary = "Required • Core transcription across 25 European languages",
        sizeMb = 1187,
        expectedBytes = 1_244_258_225L,
        isRequired = true
    ),
    EAR(
        id = "ear",
        displayName = "Ear — Auto Language Detection",
        roleSummary = "Automatically detects spoken language before transcription",
        sizeMb = 10,
        expectedBytes = 10_738_698L,
        isRequired = false
    ),
    CLEAR(
        id = "clear",
        displayName = "Clear — Noise Suppression (clear-studio)",
        roleSummary = "Removes background noise and room reverb before transcription",
        sizeMb = 47,
        expectedBytes = 49_285_108L,
        isRequired = false
    ),
    UHM(
        id = "uhm",
        displayName = "Uhm — Filler Word Removal",
        roleSummary = "Trained primarily on English hesitations ('uh', 'um', 'hmm'); transfers acoustically to German, Dutch, French, and Spanish",
        sizeMb = 23,
        expectedBytes = 23_919_709L,
        isRequired = false
    ),
    GEMMA(
        id = "gemma",
        displayName = "Gemma 3 270M — SLM Formatting Engine (Experimental)",
        roleSummary = "Experimental • Punctuation, spoken commands, number/time formatting, lists & paragraphs",
        sizeMb = 230,
        expectedBytes = 241_410_624L,
        isRequired = false
    );
}

/**
 * Pure layout & typography formatting preset for the Gemma 3 270M SLM formatting engine.
 * Focuses strictly on formatting (punctuation, capitalization, paragraphs, lists) without rewriting user words.
 */
enum class GemmaFormattingStyle(
    val id: String,
    val displayName: String,
    val description: String
) {
    STANDARD(
        id = "standard",
        displayName = "Standard Paragraphs",
        description = "Formats dictation into capitalized, punctuated sentences and groups long dictations into paragraphs"
    ),
    BULLET_LIST(
        id = "bullet_list",
        displayName = "Bullet List",
        description = "Formats each dictated sentence or item into a bulleted list (• Item)"
    ),
    NUMBERED_LIST(
        id = "numbered_list",
        displayName = "Numbered List",
        description = "Formats each dictated sentence or item into a numbered list (1. Item, 2. Item)"
    ),
    COMPACT_CHAT(
        id = "compact_chat",
        displayName = "Compact / Chat",
        description = "Keeps text in a single compact block and omits the trailing period on single-sentence messages"
    );

    companion object {
        fun fromId(id: String?): GemmaFormattingStyle {
            return when (id?.lowercase(Locale.ROOT)) {
                "standard", "smart_cleanup", "concise_prose", "professional_email", "technical_code" -> STANDARD
                "bullet_list", "structured_notes" -> BULLET_LIST
                "numbered_list" -> NUMBERED_LIST
                "compact_chat", "casual_chat" -> COMPACT_CHAT
                else -> STANDARD
            }
        }
    }
}

/**
 * Paragraph break cadence for long multi-sentence dictations in Gemma 3 270M.
 */
enum class GemmaParagraphDensity(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val targetSentences: Int,
    val description: String
) {
    COMPACT(
        id = "compact",
        displayName = "Compact (3 sentences)",
        shortLabel = "Compact",
        targetSentences = 3,
        description = "Starts a new paragraph every 3 sentences or at a topic transition"
    ),
    BALANCED(
        id = "balanced",
        displayName = "Balanced (4 sentences)",
        shortLabel = "Balanced",
        targetSentences = 4,
        description = "Starts a new paragraph every 4 sentences or at a topic transition"
    ),
    LONG_FORM(
        id = "long_form",
        displayName = "Long-Form (6 sentences)",
        shortLabel = "Long-Form",
        targetSentences = 6,
        description = "Starts a new paragraph every 6 sentences or at a topic transition"
    );

    companion object {
        fun fromId(id: String?): GemmaParagraphDensity {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BALANCED
        }
    }
}

/**
 * Fine-grained formatting options for the Gemma 3 270M SLM Formatting Engine.
 * Strictly controls text formatting (never rewrites vocabulary or tone).
 */
data class GemmaFormattingConfig(
    val style: GemmaFormattingStyle = GemmaFormattingStyle.STANDARD,
    val smartPunctuationEnabled: Boolean = true,
    val spokenCommandsEnabled: Boolean = true,
    val smartNumbersEnabled: Boolean = true,
    val stutterRemovalEnabled: Boolean = true,
    val autoParagraphEnabled: Boolean = true,
    val paragraphDensity: GemmaParagraphDensity = GemmaParagraphDensity.BALANCED
)

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
        description = "Multi-threaded ARM NEON / XNNPACK execution; lowest memory footprint and maximum stability with all 4 models"
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
    val clearEnabled: Boolean = false,
    val uhmEnabled: Boolean = false,
    val gemmaEnabled: Boolean = false,
    val gemmaFormattingStyle: GemmaFormattingStyle = GemmaFormattingStyle.STANDARD,
    val gemmaSmartPunctuationEnabled: Boolean = true,
    val gemmaSpokenCommandsEnabled: Boolean = true,
    val gemmaSmartNumbersEnabled: Boolean = true,
    val gemmaStutterRemovalEnabled: Boolean = true,
    val gemmaAutoParagraphEnabled: Boolean = true,
    val gemmaParagraphDensity: GemmaParagraphDensity = GemmaParagraphDensity.BALANCED,
    val preferredLanguage: String = defaultPreferredLanguage(),
    val customVocabulary: List<String> = emptyList(),
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.AUTO
) {
    /**
     * `Voz` is the core STT engine and cannot be disabled.
     */
    val vozEnabled: Boolean = true

    /**
     * Snapshot of all Gemma 3 270M SLM formatting options.
     */
    val gemmaConfig: GemmaFormattingConfig
        get() = GemmaFormattingConfig(
            style = gemmaFormattingStyle,
            smartPunctuationEnabled = gemmaSmartPunctuationEnabled,
            spokenCommandsEnabled = gemmaSpokenCommandsEnabled,
            smartNumbersEnabled = gemmaSmartNumbersEnabled,
            stutterRemovalEnabled = gemmaStutterRemovalEnabled,
            autoParagraphEnabled = gemmaAutoParagraphEnabled,
            paragraphDensity = gemmaParagraphDensity
        )

    /**
     * Active disk footprint in MB based on currently enabled models.
     */
    val activeDiskFootprintMb: Int
        get() = DesertAntModel.VOZ.sizeMb +
            (if (earEnabled) DesertAntModel.EAR.sizeMb else 0) +
            (if (clearEnabled) DesertAntModel.CLEAR.sizeMb else 0) +
            (if (uhmEnabled) DesertAntModel.UHM.sizeMb else 0) +
            (if (gemmaEnabled) DesertAntModel.GEMMA.sizeMb else 0)

    /**
     * Estimated peak RAM in MB based on actual loaded LiteRT / GGUF model weights + runtime buffers:
     * - Base (`Voz` 1,187 MB weights + ~213 MB app & LiteRT runtime): 1,400 MB
     * - `Ear` (10 MB weights + runtime buffers): +50 MB
     * - `Clear` (47 MB weights + streaming conformer/FFT state buffers): +150 MB
     * - `Uhm` (23 MB weights + frame buffers): +80 MB
     * - `Gemma 3 270M` (230 MB Q4_0 weights + ~30 MB KV cache & formatting runtime): +260 MB
     */
    val estimatedPeakRamMb: Int
        get() {
            val audioPipelineRam = when {
                earEnabled && clearEnabled && uhmEnabled -> 1680
                earEnabled && !clearEnabled && uhmEnabled -> 1530
                earEnabled && clearEnabled && !uhmEnabled -> 1600
                earEnabled && !clearEnabled && !uhmEnabled -> 1450
                !earEnabled && clearEnabled && uhmEnabled -> 1630
                !earEnabled && !clearEnabled && uhmEnabled -> 1480
                !earEnabled && clearEnabled && !uhmEnabled -> 1550
                else -> 1400
            }
            return audioPipelineRam + (if (gemmaEnabled) 260 else 0)
        }

    fun isModelEnabled(model: DesertAntModel): Boolean = when (model) {
        DesertAntModel.VOZ -> true
        DesertAntModel.EAR -> earEnabled
        DesertAntModel.CLEAR -> clearEnabled
        DesertAntModel.UHM -> uhmEnabled
        DesertAntModel.GEMMA -> gemmaEnabled
    }

    fun enabledModels(): List<DesertAntModel> = buildList {
        add(DesertAntModel.VOZ)
        if (earEnabled) add(DesertAntModel.EAR)
        if (clearEnabled) add(DesertAntModel.CLEAR)
        if (uhmEnabled) add(DesertAntModel.UHM)
        if (gemmaEnabled) add(DesertAntModel.GEMMA)
    }

    companion object {
        const val PREFS_NAME = "openflow_model_settings"
        const val KEY_EAR_ENABLED = "ear_enabled"
        const val KEY_CLEAR_ENABLED = "clear_enabled"
        const val KEY_UHM_ENABLED = "uhm_enabled"
        const val KEY_GEMMA_ENABLED = "gemma_enabled"
        const val KEY_GEMMA_STYLE = "gemma_formatting_style"
        const val KEY_GEMMA_STUTTER_REMOVAL = "gemma_stutter_removal_enabled"
        const val KEY_GEMMA_SPOKEN_COMMANDS = "gemma_spoken_commands_enabled"
        const val KEY_GEMMA_SMART_NUMBERS = "gemma_smart_numbers_enabled"
        const val KEY_GEMMA_SMART_PUNCTUATION = "gemma_smart_punctuation_enabled"
        const val KEY_GEMMA_AUTO_PARAGRAPH = "gemma_auto_paragraph_enabled"
        const val KEY_GEMMA_PARAGRAPH_DENSITY = "gemma_paragraph_density"
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
            val gemmaStyle = GemmaFormattingStyle.fromId(
                prefs.getString(KEY_GEMMA_STYLE, GemmaFormattingStyle.STANDARD.id)
            )
            val paragraphDensity = GemmaParagraphDensity.fromId(
                prefs.getString(KEY_GEMMA_PARAGRAPH_DENSITY, GemmaParagraphDensity.BALANCED.id)
            )

            return ModelSettings(
                earEnabled = prefs.getBoolean(KEY_EAR_ENABLED, true),
                clearEnabled = prefs.getBoolean(KEY_CLEAR_ENABLED, false),
                uhmEnabled = prefs.getBoolean(KEY_UHM_ENABLED, false),
                gemmaEnabled = prefs.getBoolean(KEY_GEMMA_ENABLED, false),
                gemmaFormattingStyle = gemmaStyle,
                gemmaSmartPunctuationEnabled = prefs.getBoolean(KEY_GEMMA_SMART_PUNCTUATION, true),
                gemmaSpokenCommandsEnabled = prefs.getBoolean(KEY_GEMMA_SPOKEN_COMMANDS, true),
                gemmaSmartNumbersEnabled = prefs.getBoolean(KEY_GEMMA_SMART_NUMBERS, true),
                gemmaStutterRemovalEnabled = prefs.getBoolean(KEY_GEMMA_STUTTER_REMOVAL, true),
                gemmaAutoParagraphEnabled = prefs.getBoolean(KEY_GEMMA_AUTO_PARAGRAPH, true),
                gemmaParagraphDensity = paragraphDensity,
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
                .putBoolean(KEY_CLEAR_ENABLED, settings.clearEnabled)
                .putBoolean(KEY_UHM_ENABLED, settings.uhmEnabled)
                .putBoolean(KEY_GEMMA_ENABLED, settings.gemmaEnabled)
                .putString(KEY_GEMMA_STYLE, settings.gemmaFormattingStyle.id)
                .putBoolean(KEY_GEMMA_SMART_PUNCTUATION, settings.gemmaSmartPunctuationEnabled)
                .putBoolean(KEY_GEMMA_SPOKEN_COMMANDS, settings.gemmaSpokenCommandsEnabled)
                .putBoolean(KEY_GEMMA_SMART_NUMBERS, settings.gemmaSmartNumbersEnabled)
                .putBoolean(KEY_GEMMA_STUTTER_REMOVAL, settings.gemmaStutterRemovalEnabled)
                .putBoolean(KEY_GEMMA_AUTO_PARAGRAPH, settings.gemmaAutoParagraphEnabled)
                .putString(KEY_GEMMA_PARAGRAPH_DENSITY, settings.gemmaParagraphDensity.id)
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
        DesertAntModel.CLEAR to "v0.3.0",
        DesertAntModel.UHM to "v1.1.0",
        DesertAntModel.GEMMA to "v1.0.0"
    )

    @Volatile
    private var bundledExtractionChecked = false

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
     * If this APK is the Offline-Bundled build containing pre-packaged model files under
     * `assets/desert-ant-models/`, extracts any missing model files and `.dal-meta/manifest`
     * into `context.cacheDir/desert-ant-models/<id>/<revision>` automatically.
     */
    @Synchronized
    fun ensureBundledModelsExtracted(context: Context) {
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
     * Critically, this does NOT instantiate native `Voz`/`Ear`/`Clear` JNI objects, avoiding
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
     * Ensures `.dal-meta/manifest` is present for native `ModelDistribution.load(from:)`,
     * but intentionally does NOT forge `.openflow_verified` stamps for unverified files.
     */
    fun isModelDirectoryComplete(targetDir: File, artifacts: List<ModelFileArtifact>): Boolean {
        if (!targetDir.exists() || !targetDir.isDirectory || artifacts.isEmpty()) return false
        for (artifact in artifacts) {
            val finalFile = File(targetDir, artifact.relativePath)
            val partFile = File(targetDir, "${artifact.relativePath}.part")
            if (partFile.exists()) return false
            if (!finalFile.exists() || !finalFile.isFile || finalFile.length() != artifact.sizeBytes) {
                return false
            }
        }
        val manifestFile = File(File(targetDir, ".dal-meta"), "manifest")
        if (!manifestFile.exists()) {
            try {
                writeModelManifest(targetDir, artifacts)
            } catch (_: Throwable) {
            }
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
        if (!finalFile.exists()) return
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
     * Writes both `.dal-meta/manifest` and `.openflow_verified` for all [artifacts] in [targetDir].
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
    }

    private fun artifactStampLine(file: File, artifact: ModelFileArtifact): String {
        return "${artifact.relativePath}\t${artifact.sizeBytes}\t${file.lastModified()}\t${artifact.sha256.lowercase(Locale.ROOT)}"
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
     * Deletes cached files on disk for a non-required model (`Ear`, `Clear`, or `Uhm`).
     * `Voz` is locked and cannot be deleted.
     */
    fun deleteModelCache(context: Context, model: DesertAntModel): Boolean {
        if (model.isRequired) return false
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
