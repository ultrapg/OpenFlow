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
        sizeMb = 337,
        expectedBytes = 337L * 1024L * 1024L,
        isRequired = true
    ),
    EAR(
        id = "ear",
        displayName = "Ear — Auto Language Detection",
        roleSummary = "Automatically detects spoken language before transcription",
        sizeMb = 22,
        expectedBytes = 22L * 1024L * 1024L,
        isRequired = false
    ),
    CLEAR(
        id = "clear",
        displayName = "Clear — Noise Suppression (clear-studio)",
        roleSummary = "Removes background noise and room reverb before transcription",
        sizeMb = 24,
        expectedBytes = 24L * 1024L * 1024L,
        isRequired = false
    ),
    UHM(
        id = "uhm",
        displayName = "Uhm — Filler Word Removal",
        roleSummary = "Trained primarily on English hesitations ('uh', 'um', 'hmm'); transfers acoustically to German, Dutch, French, and Spanish",
        sizeMb = 24,
        expectedBytes = 23_919_472L,
        isRequired = false
    );
}

/**
 * Immutable snapshot of user-configurable Desert Ant model settings persisted in SharedPreferences.
 */
data class ModelSettings(
    val earEnabled: Boolean = true,
    val clearEnabled: Boolean = true,
    val uhmEnabled: Boolean = true,
    val preferredLanguage: String = defaultPreferredLanguage(),
    val customVocabulary: List<String> = emptyList()
) {
    /**
     * `Voz` is the core STT engine and cannot be disabled.
     */
    val vozEnabled: Boolean = true

    /**
     * Active disk footprint in MB based on currently enabled models (Section 5.4).
     */
    val activeDiskFootprintMb: Int
        get() = DesertAntModel.VOZ.sizeMb +
            (if (earEnabled) DesertAntModel.EAR.sizeMb else 0) +
            (if (clearEnabled) DesertAntModel.CLEAR.sizeMb else 0) +
            (if (uhmEnabled) DesertAntModel.UHM.sizeMb else 0)

    /**
     * Estimated peak RAM in MB matching the 8-state Toggle State Matrix (Section 5.4).
     */
    val estimatedPeakRamMb: Int
        get() = when {
            earEnabled && clearEnabled && uhmEnabled -> 850
            earEnabled && !clearEnabled && uhmEnabled -> 750
            earEnabled && clearEnabled && !uhmEnabled -> 730
            earEnabled && !clearEnabled && !uhmEnabled -> 650
            !earEnabled && clearEnabled && uhmEnabled -> 770
            !earEnabled && !clearEnabled && uhmEnabled -> 670
            !earEnabled && clearEnabled && !uhmEnabled -> 650
            else -> 550
        }

    fun isModelEnabled(model: DesertAntModel): Boolean = when (model) {
        DesertAntModel.VOZ -> true
        DesertAntModel.EAR -> earEnabled
        DesertAntModel.CLEAR -> clearEnabled
        DesertAntModel.UHM -> uhmEnabled
    }

    fun enabledModels(): List<DesertAntModel> = buildList {
        add(DesertAntModel.VOZ)
        if (earEnabled) add(DesertAntModel.EAR)
        if (clearEnabled) add(DesertAntModel.CLEAR)
        if (uhmEnabled) add(DesertAntModel.UHM)
    }

    companion object {
        const val PREFS_NAME = "openflow_model_settings"
        const val KEY_EAR_ENABLED = "ear_enabled"
        const val KEY_CLEAR_ENABLED = "clear_enabled"
        const val KEY_UHM_ENABLED = "uhm_enabled"
        const val KEY_PREFERRED_LANGUAGE = "preferred_language"
        const val KEY_CUSTOM_VOCABULARY = "custom_vocabulary"
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

            return ModelSettings(
                earEnabled = prefs.getBoolean(KEY_EAR_ENABLED, true),
                clearEnabled = prefs.getBoolean(KEY_CLEAR_ENABLED, true),
                uhmEnabled = prefs.getBoolean(KEY_UHM_ENABLED, true),
                preferredLanguage = sanitizedLang,
                customVocabulary = vocabList
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
                .putString(KEY_PREFERRED_LANGUAGE, validLang)
                .putString(KEY_CUSTOM_VOCABULARY, jsonArray.toString())
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
 * Helper object for checking on-disk model cache status, running verified downloads with progress,
 * and deleting inactive model files on disk.
 */
object DesertAntModelStorage {

    private val MODEL_REVISIONS = mapOf(
        DesertAntModel.VOZ to "v0.4.0",
        DesertAntModel.EAR to "v0.2.0",
        DesertAntModel.CLEAR to "v0.3.0",
        DesertAntModel.UHM to "v1.1.0"
    )

    @Volatile
    private var bundledExtractionChecked = false

    fun modelCacheRoot(context: Context, model: DesertAntModel): File {
        return File(context.applicationContext.cacheDir, "desert-ant-models/${model.id}")
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
            }
            bundledExtractionChecked = true
        } catch (_: Throwable) {
            // No bundled assets present in On-Demand APK variant
            bundledExtractionChecked = true
        }
    }

    fun isModelDownloaded(context: Context, model: DesertAntModel): Boolean {
        val appContext = context.applicationContext
        ensureBundledModelsExtracted(appContext)
        return try {
            when (model) {
                DesertAntModel.VOZ -> Voz(appContext).use { it.isDownloaded() }
                DesertAntModel.EAR -> Ear(appContext).use { it.isDownloaded() }
                DesertAntModel.CLEAR -> Clear(appContext).use { it.isDownloaded() }
                DesertAntModel.UHM -> Uhm(appContext).use { it.isDownloaded() }
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun areAllEnabledModelsDownloaded(context: Context, settings: ModelSettings = ModelSettings.load(context)): Boolean {
        return settings.enabledModels().all { isModelDownloaded(context, it) }
    }

    fun countDownloadedModels(context: Context): Int {
        return DesertAntModel.entries.count { isModelDownloaded(context, it) }
    }

    /**
     * Downloads [model] on [Dispatchers.IO] while emitting progress in `0.0f..1.0f` and verifying
     * post-download integrity via `isDownloaded()`.
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

        val dirToWatch = modelCacheRoot(appContext, model)
        onProgress(0.01f)

        when (model) {
            DesertAntModel.UHM -> {
                Uhm(appContext).use { uhm ->
                    uhm.download { ratio ->
                        onProgress(ratio.coerceIn(0.01f, 0.99f))
                    }
                    if (!uhm.isDownloaded()) {
                        throw IllegalStateException("Integrity check failed after downloading ${model.displayName}")
                    }
                }
            }
            DesertAntModel.VOZ -> {
                coroutineScope {
                    val watcher = launch {
                        while (isActive) {
                            val bytes = directoryBytes(dirToWatch)
                            val ratio = (bytes.toFloat() / model.expectedBytes.toFloat()).coerceIn(0.01f, 0.98f)
                            onProgress(ratio)
                            delay(120L)
                        }
                    }
                    try {
                        Voz(appContext).use { voz ->
                            voz.download()
                            if (!voz.isDownloaded()) {
                                throw IllegalStateException("Integrity check failed after downloading ${model.displayName}")
                            }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            }
            DesertAntModel.EAR -> {
                coroutineScope {
                    val watcher = launch {
                        while (isActive) {
                            val bytes = directoryBytes(dirToWatch)
                            val ratio = (bytes.toFloat() / model.expectedBytes.toFloat()).coerceIn(0.01f, 0.98f)
                            onProgress(ratio)
                            delay(100L)
                        }
                    }
                    try {
                        Ear(appContext).use { ear ->
                            ear.download()
                            if (!ear.isDownloaded()) {
                                throw IllegalStateException("Integrity check failed after downloading ${model.displayName}")
                            }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            }
            DesertAntModel.CLEAR -> {
                coroutineScope {
                    val watcher = launch {
                        while (isActive) {
                            val bytes = directoryBytes(dirToWatch)
                            val ratio = (bytes.toFloat() / model.expectedBytes.toFloat()).coerceIn(0.01f, 0.98f)
                            onProgress(ratio)
                            delay(100L)
                        }
                    }
                    try {
                        Clear(appContext).use { clear ->
                            clear.download()
                            if (!clear.isDownloaded()) {
                                throw IllegalStateException("Integrity check failed after downloading ${model.displayName}")
                            }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        }

        onProgress(1.0f)
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

    private fun directoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}
