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

package com.voicebubble.openflow.ui.settings

import ai.desertant.voz.Voz
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
/* GEMINI_NANO_DISABLED
import com.voicebubble.openflow.core.speech.GeminiNanoAvailability
import com.voicebubble.openflow.core.speech.GeminiNanoBenchmarkResult
import com.voicebubble.openflow.core.speech.GeminiNanoManager
import com.voicebubble.openflow.core.speech.GeminiNanoTextProcessor
import com.voicebubble.openflow.core.speech.GeminiNanoVariantStatus
*/
import com.voicebubble.openflow.core.speech.LlmBenchmarkResult
import com.voicebubble.openflow.core.speech.LlmSinglePassMetrics
import com.voicebubble.openflow.core.speech.ModelLoadState
import com.voicebubble.openflow.core.speech.OpenAiCompatibleClient
import com.voicebubble.openflow.core.speech.PipelinePhase
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.DownloadSessionState
/* GEMINI_NANO_DISABLED
import com.voicebubble.openflow.settings.GeminiNanoVariant
*/
import com.voicebubble.openflow.settings.GemmaPromptPreset
import com.voicebubble.openflow.settings.HardwareAccelerator
import com.voicebubble.openflow.settings.LlmBenchmarkSample
import com.voicebubble.openflow.settings.LlmModelChoice
import com.voicebubble.openflow.settings.LlmProviderMode
import com.voicebubble.openflow.settings.LlmThinkingEffort
/* GEMINI_NANO_DISABLED
import com.voicebubble.openflow.settings.LocalLlmBackend
*/
import com.voicebubble.openflow.settings.ModelDownloadManager
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechRecognitionSettingsSection(
    settings: ModelSettings,
    onSettingsChanged: (ModelSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val smartPipeline = remember { SmartPipeline.getInstance(context) }
    val downloadState by ModelDownloadManager.sessionState.collectAsState()
    val pipelineStatus by smartPipeline.pipelineStatus.collectAsState()
    /* GEMINI_NANO_DISABLED
    val nanoCatalog by GeminiNanoManager.catalogState.collectAsState()

    LaunchedEffect(Unit) {
        GeminiNanoManager.refreshStatusesAsync(force = false)
    }
    */

    var storageRefreshTick by remember { mutableIntStateOf(0) }
    var newVocabInput by remember { mutableStateOf("") }
    var customPromptDraft by remember { mutableStateOf(settings.gemmaCustomPrompt) }
    LaunchedEffect(settings.gemmaCustomPrompt) {
        if (customPromptDraft != settings.gemmaCustomPrompt) {
            customPromptDraft = settings.gemmaCustomPrompt
        }
    }

    val downloadedStatusMap = remember { mutableStateMapOf<DesertAntModel, Boolean>() }
    val diskBytesMap = remember { mutableStateMapOf<DesertAntModel, Long>() }
    var isDeletingStorage by remember { mutableStateOf(false) }
    var storageActionFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        settings,
        storageRefreshTick,
        downloadState.isRunning,
        downloadState.isCompleted,
        downloadState.pendingAutoEnableModels
    ) {
        val (latestPersisted, status, bytes) = withContext(Dispatchers.IO) {
            val loaded = ModelSettings.load(context)
            val diskStatus = DesertAntModel.entries.associateWith { model ->
                DesertAntModelStorage.isModelDownloaded(context, model)
            }
            val diskBytes = DesertAntModel.entries.associateWith { model ->
                DesertAntModelStorage.modelCacheDirectoryBytes(context, model)
            }
            Triple(loaded, diskStatus, diskBytes)
        }
        if (latestPersisted != settings) {
            onSettingsChanged(latestPersisted)
        }
        downloadedStatusMap.clear()
        downloadedStatusMap.putAll(status)
        diskBytesMap.clear()
        diskBytesMap.putAll(bytes)
        smartPipeline.refreshStatusSnapshotAsync()
    }
    val downloadedCount = downloadedStatusMap.values.count { it }

    val persistAndReload: (ModelSettings) -> Unit = { updated ->
        ModelSettings.save(context, updated)
        onSettingsChanged(updated)
        smartPipeline.reloadSettingsAsync(updated)
    }

    val deleteLocalModelsSafely: (List<DesertAntModel>) -> Unit = { requestedTargets ->
        if (!isDeletingStorage && requestedTargets.isNotEmpty()) {
            scope.launch {
                isDeletingStorage = true
                storageActionFeedback = null
                try {
                    val deletedModels = mutableListOf<DesertAntModel>()
                    var freedBytes = 0L
                    var blockedByActiveDictation = false

                    requestedTargets.forEach { target ->
                        ModelDownloadManager.cancelModelDownloadAndWait(target)
                    }

                    withContext(Dispatchers.IO) {
                        requestedTargets.forEach { model ->
                            val unloaded = smartPipeline.unloadModelForDeletion(model)
                            if (!unloaded) {
                                blockedByActiveDictation = true
                            } else {
                                val bytesBefore = maxOf(
                                    DesertAntModelStorage.modelCacheDirectoryBytes(context, model),
                                    diskBytesMap[model] ?: 0L
                                )
                                if (DesertAntModelStorage.deleteModelCache(context, model, allowRequired = true)) {
                                    deletedModels.add(model)
                                    freedBytes += bytesBefore
                                }
                            }
                        }
                        DesertAntModelStorage.cleanupLegacyRemovedModels(context)
                    }

                    if (DesertAntModel.PUNCT in deletedModels && settings.punctEnabled) {
                        persistAndReload(settings.copy(punctEnabled = false))
                    } else {
                        smartPipeline.refreshStatusSnapshotAsync()
                    }

                    storageRefreshTick++
                    storageActionFeedback = when {
                        deletedModels.isNotEmpty() -> {
                            val freedLabel = formatStorageBytesLabel(freedBytes)
                            if (deletedModels.size == 1) {
                                "Deleted ${deletedModels.first().shortLabel} ($freedLabel freed)."
                            } else {
                                "Deleted ${deletedModels.size} local models ($freedLabel freed)."
                            }
                        }
                        blockedByActiveDictation ->
                            "Finish the active dictation before deleting loaded models."
                        else ->
                            "Could not remove cached model files from storage."
                    }
                } finally {
                    isDeletingStorage = false
                }
            }
        }
    }

    val handleOptionalToggle: (DesertAntModel, Boolean) -> Unit = { model, targetEnabled ->
        if (!targetEnabled) {
            if (model.isLlmModel) {
                ModelDownloadManager.cancelAllLlmAutoEnables()
                persistAndReload(settings.copy(gemmaEnabled = false))
            } else {
                ModelDownloadManager.cancelAutoEnable(model)
                val updated = when (model) {
                    DesertAntModel.EAR -> settings.copy(earEnabled = false)
                    DesertAntModel.PUNCT -> settings.copy(punctEnabled = false)
                    else -> settings
                }
                persistAndReload(updated)
            }
        } else if (model == DesertAntModel.EAR && settings.remoteEarEnabled && settings.isRemoteEarConfigured) {
            persistAndReload(settings.copy(earEnabled = true))
        } else {
            scope.launch {
                val alreadyDownloaded = withContext(Dispatchers.IO) {
                    DesertAntModelStorage.isModelDownloaded(context, model)
                }
                if (alreadyDownloaded) {
                    if (model.isLlmModel) {
                        ModelDownloadManager.cancelAllLlmAutoEnables()
                        val llmChoice = LlmModelChoice.fromDesertAntModel(model) ?: settings.selectedLlmModel
                        val nextDisableThinking = if (llmChoice != settings.selectedLlmModel) {
                            llmChoice.defaultDisableThinking
                        } else {
                            settings.gemmaDisableThinking
                        }
                        persistAndReload(
                            settings.copy(
                                gemmaEnabled = true,
                                llmProvider = LlmProviderMode.LOCAL,
                                /* GEMINI_NANO_DISABLED
                                localLlmBackend = LocalLlmBackend.LITERT_LM,
                                */
                                selectedLlmModel = llmChoice,
                                gemmaDisableThinking = nextDisableThinking
                            )
                        )
                    } else {
                        val updated = when (model) {
                            DesertAntModel.EAR -> settings.copy(earEnabled = true)
                            DesertAntModel.PUNCT -> settings.copy(punctEnabled = true)
                            else -> settings
                        }
                        persistAndReload(updated)
                    }
                } else {
                    if (model.isLlmModel) {
                        val llmChoice = LlmModelChoice.fromDesertAntModel(model) ?: settings.selectedLlmModel
                        val nextDisableThinking = if (llmChoice != settings.selectedLlmModel) {
                            llmChoice.defaultDisableThinking
                        } else {
                            settings.gemmaDisableThinking
                        }
                        ModelDownloadManager.cancelAllLlmAutoEnables(keepModel = model)
                        // Immediately unload any previously active LLM while downloading the newly chosen model
                        persistAndReload(
                            settings.copy(
                                gemmaEnabled = false,
                                /* GEMINI_NANO_DISABLED
                                localLlmBackend = LocalLlmBackend.LITERT_LM,
                                */
                                selectedLlmModel = llmChoice,
                                gemmaDisableThinking = nextDisableThinking
                            )
                        )
                    }
                    // Settings Toggle-On Background Resumable Download Flow (supports queuing multiple models)
                    ModelDownloadManager.startQueue(
                        context = context,
                        models = listOf(model),
                        autoEnableOnComplete = true,
                        onAllCompleted = {
                            storageRefreshTick++
                            val latest = ModelSettings.load(context)
                            onSettingsChanged(latest)
                            smartPipeline.reloadSettingsAsync(latest)
                        }
                    )
                }
            }
        }
    }

    val handleLlmSelection: (LlmModelChoice) -> Unit = { choice ->
        val anyLlmActiveOrPending = (settings.gemmaEnabled && settings.llmProvider == LlmProviderMode.LOCAL) ||
            downloadState.pendingAutoEnableModels.any { it.isLlmModel }
        if (!anyLlmActiveOrPending) {
            if (settings.selectedLlmModel != choice ||
                settings.llmProvider != LlmProviderMode.LOCAL
                /* GEMINI_NANO_DISABLED || settings.localLlmBackend != LocalLlmBackend.LITERT_LM */
            ) {
                persistAndReload(
                    settings.copy(
                        llmProvider = LlmProviderMode.LOCAL,
                        /* GEMINI_NANO_DISABLED
                        localLlmBackend = LocalLlmBackend.LITERT_LM,
                        */
                        selectedLlmModel = choice,
                        gemmaDisableThinking = choice.defaultDisableThinking
                    )
                )
            }
        } else {
            // Switching models while AI Post-Processing is enabled (or downloading):
            // immediately switch & unload any previous LLM, or download + auto-enable the newly chosen model
            handleOptionalToggle(choice.desertAntModel, true)
        }
    }

    val handleLlmProviderChange: (LlmProviderMode) -> Unit = { targetProvider ->
        if (settings.llmProvider != targetProvider) {
            if (targetProvider == LlmProviderMode.OPENAI_API) {
                ModelDownloadManager.cancelAllLlmAutoEnables()
                persistAndReload(settings.copy(llmProvider = LlmProviderMode.OPENAI_API))
            /* GEMINI_NANO_DISABLED
            } else if (settings.localLlmBackend == LocalLlmBackend.GEMINI_NANO) {
                val nanoReady = nanoCatalog.statusOf(settings.geminiNanoVariant).isAvailable
                persistAndReload(
                    settings.copy(
                        llmProvider = LlmProviderMode.LOCAL,
                        gemmaEnabled = settings.gemmaEnabled && nanoReady
                    )
                )
            */
            } else {
                scope.launch {
                    val localDownloaded = withContext(Dispatchers.IO) {
                        DesertAntModelStorage.isModelDownloaded(context, settings.selectedLlmModel.desertAntModel)
                    }
                    persistAndReload(
                        settings.copy(
                            llmProvider = LlmProviderMode.LOCAL,
                            gemmaEnabled = settings.gemmaEnabled && localDownloaded
                        )
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Speech Recognition & AI Pipeline",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 1. Resource Summary & Live Model Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isBusy = pipelineStatus.phase == PipelinePhase.LOADING ||
                            pipelineStatus.phase == PipelinePhase.WARMING_UP ||
                            pipelineStatus.phase == PipelinePhase.TRANSCRIBING
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            val dotColor = when {
                                pipelineStatus.isReady -> Color(0xFF2E7D32)
                                pipelineStatus.phase == PipelinePhase.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pipelineStatus.phase.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Text(
                        text = "${pipelineStatus.loadedCount}/${pipelineStatus.enabledCount} in RAM",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Text(
                    text = pipelineStatus.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                // Per-model live status pills (core audio + single selected AI model)
                val visibleStatusModels = listOf(
                    DesertAntModel.VOZ,
                    DesertAntModel.EAR,
                    DesertAntModel.PUNCT,
                    settings.selectedLlmModel.desertAntModel
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visibleStatusModels.forEach { model ->
                        val state = pipelineStatus.modelStates[model] ?: ModelLoadState.STANDBY
                        ModelStatusChip(model = model, state = state)
                    }
                    /* GEMINI_NANO_DISABLED
                    if (settings.isGeminiNanoEnabled || nanoCatalog.anyAvailable || nanoCatalog.anyDownloadable) {
                        val activeNanoVariant = settings.geminiNanoVariant
                        val nanoStatus = nanoCatalog.statusOf(activeNanoVariant)
                        GeminiNanoStatusChip(
                            variant = activeNanoVariant,
                            isActive = settings.isGeminiNanoEnabled,
                            status = nanoStatus
                        )
                    }
                    */
                }

                // Last inference latency & speed telemetry
                val lastInferenceMs = pipelineStatus.lastInferenceMs
                val lastAudioMs = pipelineStatus.lastAudioDurationMs
                if (lastInferenceMs != null && lastAudioMs != null && lastAudioMs > 0L) {
                    val speedup = lastAudioMs.toDouble() / lastInferenceMs.coerceAtLeast(1L).toDouble()
                    val speedupFormatted = String.format(Locale.US, "%.1f×", speedup)
                    val audioSecFormatted = String.format(Locale.US, "%.1fs", lastAudioMs / 1000.0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Last dictation: ${lastInferenceMs} ms for $audioSecFormatted audio ($speedupFormatted real-time)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val activeDiskGb = String.format(Locale.US, "%.2f GB", settings.activeDiskFootprintMb / 1024.0)
                        Text(
                            text = "Active Disk: $activeDiskGb ($downloadedCount downloaded)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Idle unload: ${ModelSettings.autoUnloadChipLabel(settings.modelAutoUnloadMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Auto-Unload All Models When Idle (Battery Saver)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = settings.autoUnloadSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ModelSettings.AUTO_UNLOAD_OPTIONS_MINUTES.forEach { minutes ->
                            val selected = settings.modelAutoUnloadMinutes == minutes
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        persistAndReload(settings.copy(modelAutoUnloadMinutes = minutes))
                                    }
                                },
                                label = {
                                    Text(ModelSettings.autoUnloadChipLabel(minutes))
                                }
                            )
                        }
                    }
                }

                val isWorking = pipelineStatus.phase == PipelinePhase.LOADING ||
                    pipelineStatus.phase == PipelinePhase.WARMING_UP ||
                    pipelineStatus.phase == PipelinePhase.TRANSCRIBING
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { smartPipeline.initializeAsync(warmUp = true, forceReload = pipelineStatus.isReady) },
                        enabled = !isWorking && !downloadState.isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (pipelineStatus.isReady) Icons.Default.CheckCircle else Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when {
                                isWorking -> "Loading..."
                                settings.enabledModels().isEmpty() && pipelineStatus.isReady -> "Refresh Remote Status"
                                pipelineStatus.loadedCount == pipelineStatus.enabledCount && pipelineStatus.isReady ->
                                    "Re-Warm Models"
                                else -> "Load Models Now"
                            }
                        )
                    }
                    if (pipelineStatus.loadedCount > 0) {
                        OutlinedButton(
                            onClick = { smartPipeline.evictAllModelsAsync(reason = "manual unload from Settings") },
                            enabled = !isWorking
                        ) {
                            Text("Unload Now")
                        }
                    }
                }
            }
        }

        // 2. Hardware Acceleration Selector (Auto / NPU / GPU / CPU)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hardware Acceleration (NPU / GPU / CPU)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Active delegate: ${pipelineStatus.activeAcceleratorLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Detected NPU / SoC: ${pipelineStatus.detectedNpuSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                HardwareAccelerator.entries.forEach { accelerator ->
                    val selected = settings.hardwareAccelerator == accelerator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (!selected) {
                                    persistAndReload(settings.copy(hardwareAccelerator = accelerator))
                                }
                            }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    persistAndReload(settings.copy(hardwareAccelerator = accelerator))
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = accelerator.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = accelerator.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 3. Model Toggle Rows (Specification Section 5.2.2)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Desert Ant Audio Models",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Voz (Local or Remote API)
                val vozTitle = if (settings.isRemoteVozActive) {
                    "Voz — Speech Recognition (Remote API)"
                } else {
                    "Voz — Speech Recognition (${DesertAntModel.VOZ.sizeMb} MB)"
                }
                val vozSubtitle = when {
                    settings.isRemoteVozActive ->
                        "Using remote model '${settings.remoteVozModel.trim()}' • 0 MB local RAM"
                    settings.remoteVozEnabled ->
                        "Remote Voz selected • Finish configuring URL & model below (local fallback active)"
                    else ->
                        "Required • Core transcription across 25 European languages"
                }
                ModelToggleItem(
                    title = vozTitle,
                    subtitle = vozSubtitle,
                    loadState = pipelineStatus.modelStates[DesertAntModel.VOZ],
                    checked = true,
                    enabled = false,
                    isLocked = true,
                    activeDownloadState = if (!settings.isRemoteVozActive && downloadState.activeModel == DesertAntModel.VOZ && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(DesertAntModel.VOZ, true) },
                    onCheckedChange = {}
                )
                if (!settings.isRemoteVozActive &&
                    downloadedStatusMap[DesertAntModel.VOZ] == false &&
                    downloadState.activeModel != DesertAntModel.VOZ
                ) {
                    OutlinedButton(
                        onClick = {
                            ModelDownloadManager.startQueue(
                                context = context,
                                models = listOf(DesertAntModel.VOZ),
                                autoEnableOnComplete = false,
                                onAllCompleted = {
                                    storageRefreshTick++
                                    val latest = ModelSettings.load(context)
                                    onSettingsChanged(latest)
                                    smartPipeline.reloadSettingsAsync(latest)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download Local Voz (${DesertAntModel.VOZ.sizeMb} MB)")
                    }
                }

                HorizontalDivider()

                // Ear (Local or Remote API)
                val earTitle = if (settings.isRemoteEarActive) {
                    "Ear — Auto Language Detection (Remote API)"
                } else {
                    "Ear — Auto Language Detection (${DesertAntModel.EAR.sizeMb} MB)"
                }
                val earSubtitle = if (settings.isRemoteEarActive) {
                    val endpointTag = if (settings.remoteEarUseSameEndpoint && settings.remoteVozEnabled) {
                        "shared endpoint"
                    } else {
                        "separate endpoint"
                    }
                    "Using remote model '${settings.remoteEarModel.trim()}' ($endpointTag) • 0 MB local RAM"
                } else {
                    DesertAntModel.EAR.roleSummary
                }
                ModelToggleItem(
                    title = earTitle,
                    subtitle = earSubtitle,
                    loadState = pipelineStatus.modelStates[DesertAntModel.EAR],
                    checked = settings.earEnabled || DesertAntModel.EAR in downloadState.pendingAutoEnableModels,
                    enabled = !(downloadState.isRunning && downloadState.activeModel == DesertAntModel.EAR),
                    isLocked = false,
                    activeDownloadState = if (!settings.isRemoteEarActive && downloadState.activeModel == DesertAntModel.EAR && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(DesertAntModel.EAR, true) },
                    onCheckedChange = { handleOptionalToggle(DesertAntModel.EAR, it) }
                )

                HorizontalDivider()

                // Punct (Off by default)
                val punctSubtitle = when {
                    settings.isOpenAiApiEnabled ->
                        "Automatically bypassed & kept out of RAM while OpenAI API (${settings.openAiModel.trim()}) is active"
                    /* GEMINI_NANO_DISABLED
                    settings.isGeminiNanoEnabled ->
                        "Automatically bypassed & kept out of RAM while ${settings.geminiNanoVariant.shortLabel} is active"
                    */
                    settings.isLocalLlmEnabled ->
                        "Automatically bypassed & kept out of RAM while ${settings.selectedLlmModel.shortLabel} is active"
                    else ->
                        DesertAntModel.PUNCT.roleSummary
                }
                ModelToggleItem(
                    title = "Punct — Text & Punctuation Cleanup (${DesertAntModel.PUNCT.sizeMb} MB)",
                    subtitle = punctSubtitle,
                    loadState = pipelineStatus.modelStates[DesertAntModel.PUNCT],
                    checked = settings.punctEnabled || DesertAntModel.PUNCT in downloadState.pendingAutoEnableModels,
                    enabled = !(downloadState.isRunning && downloadState.activeModel == DesertAntModel.PUNCT),
                    isLocked = false,
                    activeDownloadState = if (downloadState.activeModel == DesertAntModel.PUNCT && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(DesertAntModel.PUNCT, true) },
                    onCheckedChange = { handleOptionalToggle(DesertAntModel.PUNCT, it) }
                )

                if (downloadState.errorMessage != null) {
                    Text(
                        text = downloadState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    val targetModels = (downloadState.pendingAutoEnableModels + listOfNotNull(downloadState.activeModel)).distinct()
                    if (targetModels.isNotEmpty()) {
                        Button(
                            onClick = {
                                ModelDownloadManager.startQueue(
                                    context = context,
                                    models = targetModels,
                                    autoEnableOnComplete = downloadState.pendingAutoEnableModels.isNotEmpty(),
                                    onAllCompleted = {
                                        storageRefreshTick++
                                        val latest = ModelSettings.load(context)
                                        onSettingsChanged(latest)
                                        smartPipeline.reloadSettingsAsync(latest)
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Resume Download")
                        }
                    }
                }

                RemoteAudioModelsCollapsibleSection(
                    settings = settings,
                    onPersistSettings = persistAndReload
                )
            }
        }

        // 3b. AI Post-Processing & Formatting (Collapsed by default; auto-expands if API setup is pending)
        val openAiSetupPending = settings.gemmaEnabled &&
            settings.llmProvider == LlmProviderMode.OPENAI_API &&
            !settings.isOpenAiApiEnabled
        var aiSectionExpanded by rememberSaveable { mutableStateOf(openAiSetupPending) }
        val isAiActive = settings.isAiPostProcessingActive
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val selectedLlm = settings.selectedLlmModel
                val selectedDesertModel = selectedLlm.desertAntModel
                val anyLlmPendingAutoEnable = downloadState.pendingAutoEnableModels.any { it.isLlmModel }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { aiSectionExpanded = !aiSectionExpanded }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "AI Post-Processing & Formatting",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Instant Engine, Gemma 4 E2B & OpenAI API",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                isAiActive -> MaterialTheme.colorScheme.primaryContainer
                                settings.formattingEngineEnabled -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = settings.aiSectionSummaryLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isAiActive -> MaterialTheme.colorScheme.onPrimaryContainer
                                    settings.formattingEngineEnabled -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Icon(
                        imageVector = if (aiSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (aiSectionExpanded) "Collapse AI Post-Processing" else "Expand AI Post-Processing",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = aiSectionExpanded) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Removes fillers ('um', 'uh'), fixes self-corrections, and formats numbers & lists via the Instant Engine, an offline LiteRT-LM model, or an OpenAI-compatible API. AI LLM is disabled by default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    persistAndReload(settings.copy(formattingEngineEnabled = !settings.formattingEngineEnabled))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "Instant Formatting Engine (no AI)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Rule-based numbers, units, times, dates, lists, fillers and simple corrections in under 1 ms. Works with or without an AI model.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.formattingEngineEnabled,
                                onCheckedChange = { persistAndReload(settings.copy(formattingEngineEnabled = it)) }
                            )
                        }

                        HorizontalDivider()

                        Text(
                            text = "AI Backend",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = settings.llmProvider == LlmProviderMode.LOCAL,
                                onClick = { handleLlmProviderChange(LlmProviderMode.LOCAL) },
                                label = { Text("Local Offline Model") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                            FilterChip(
                                selected = settings.llmProvider == LlmProviderMode.OPENAI_API,
                                onClick = { handleLlmProviderChange(LlmProviderMode.OPENAI_API) },
                                label = { Text("OpenAI-Compatible API") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Cloud,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }

                        if (settings.llmProvider == LlmProviderMode.LOCAL) {
                            /* GEMINI_NANO_DISABLED
                            val selectedNanoVariant = settings.geminiNanoVariant
                            val selectedNanoStatus = nanoCatalog.statusOf(selectedNanoVariant)
                            */
                            val activeGemmaBackend = pipelineStatus.activeGemmaBackendLabel
                            /* GEMINI_NANO_DISABLED
                            if (settings.isGeminiNanoEnabled) {
                                Text(
                                    text = "Active ${selectedNanoVariant.title}: Android AICore (${selectedNanoStatus.baseModelName ?: "system"})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else
                            */
                            if (!activeGemmaBackend.isNullOrBlank()) {
                                Text(
                                    text = "Active ${selectedLlm.title} runtime: $activeGemmaBackend",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            /* GEMINI_NANO_DISABLED
                            if (settings.localLlmBackend == LocalLlmBackend.GEMINI_NANO) {
                                val nanoToggleEnabled = !selectedNanoStatus.isDownloading
                                val nanoSubtitle = when (selectedNanoStatus.availability) {
                                    GeminiNanoAvailability.AVAILABLE ->
                                        "0 MB OpenFlow storage · Runs on Android AICore${selectedNanoStatus.baseModelName?.let { " ($it)" } ?: ""}"
                                    GeminiNanoAvailability.DOWNLOADABLE ->
                                        "0 MB OpenFlow storage · Download ${selectedNanoVariant.shortLabel} via AICore below to use"
                                    GeminiNanoAvailability.DOWNLOADING ->
                                        "Downloading ${selectedNanoVariant.shortLabel} via Android AICore..."
                                    GeminiNanoAvailability.CHECKING, GeminiNanoAvailability.UNKNOWN ->
                                        "Checking Android AICore availability for ${selectedNanoVariant.shortLabel}..."
                                    GeminiNanoAvailability.UNAVAILABLE ->
                                        "Unavailable on this device${if (settings.geminiNanoAutoFallback) " · Auto-fallback enabled" else ""}"
                                }
                                SettingToggleRow(
                                    title = "Enable AI Post-Processing (${selectedNanoVariant.title})",
                                    subtitle = nanoSubtitle,
                                    checked = settings.gemmaEnabled && settings.llmProvider == LlmProviderMode.LOCAL,
                                    onCheckedChange = { enableNano ->
                                        if (!nanoToggleEnabled) return@SettingToggleRow
                                        if (!enableNano) {
                                            persistAndReload(settings.copy(gemmaEnabled = false))
                                        } else if (selectedNanoStatus.isDownloadable) {
                                            GeminiNanoManager.startDownload(selectedNanoVariant) { ok ->
                                                if (ok) {
                                                    scope.launch(Dispatchers.Main) {
                                                        persistAndReload(
                                                            settings.copy(
                                                                gemmaEnabled = true,
                                                                llmProvider = LlmProviderMode.LOCAL,
                                                                localLlmBackend = LocalLlmBackend.GEMINI_NANO
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            persistAndReload(
                                                settings.copy(
                                                    gemmaEnabled = true,
                                                    llmProvider = LlmProviderMode.LOCAL,
                                                    localLlmBackend = LocalLlmBackend.GEMINI_NANO
                                                )
                                            )
                                        }
                                    }
                                )
                            } else {
                            */
                            ModelToggleItem(
                                title = "Enable AI Post-Processing (${selectedLlm.title})",
                                subtitle = "${selectedLlm.sizeGbLabel} • Auto-unloads when idle",
                                loadState = pipelineStatus.modelStates[selectedDesertModel],
                                checked = (settings.gemmaEnabled && settings.llmProvider == LlmProviderMode.LOCAL) || anyLlmPendingAutoEnable,
                                enabled = !(downloadState.isRunning && downloadState.activeModel == selectedDesertModel),
                                isLocked = false,
                                activeDownloadState = if (downloadState.activeModel == selectedDesertModel && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                                onPause = { ModelDownloadManager.pauseDownloads() },
                                onResume = { handleOptionalToggle(selectedDesertModel, true) },
                                onCheckedChange = { handleOptionalToggle(selectedDesertModel, it) }
                            )
                            /* GEMINI_NANO_DISABLED } */

                            HorizontalDivider()

                            Text(
                                text = "Local AI Model",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Gemma 4 E2B runs locally via LiteRT-LM with automatic NPU / GPU / CPU dispatch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            LlmModelChoice.entries.forEach { choice ->
                                val modelEnum = choice.desertAntModel
                                val isSelected = selectedLlm == choice
                                val isDownloaded = downloadedStatusMap[modelEnum] == true
                                val loadState = pipelineStatus.modelStates[modelEnum]
                                val isDownloadingThis = downloadState.activeModel == modelEnum &&
                                    (downloadState.isRunning || downloadState.isPaused)
                                LlmModelOptionCard(
                                    choice = choice,
                                    selected = isSelected,
                                    isDownloaded = isDownloaded,
                                    loadState = loadState,
                                    isDownloading = isDownloadingThis,
                                    onSelect = { handleLlmSelection(choice) }
                                )
                            }

                            /* GEMINI_NANO_DISABLED
                            GeminiNanoCollapsibleSection(
                                settings = settings,
                                nanoCatalog = nanoCatalog,
                                isBusy = pipelineStatus.phase == PipelinePhase.TRANSCRIBING || downloadState.isRunning,
                                onPersistSettings = persistAndReload
                            )
                            */

                            HorizontalDivider()

                            val activeThinkingSupported = selectedLlm.supportsThinking
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = activeThinkingSupported) {
                                        persistAndReload(settings.copy(gemmaDisableThinking = !settings.gemmaDisableThinking))
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = if (activeThinkingSupported) {
                                            "Disable Model Thinking"
                                        } else {
                                            "Disable Model Thinking (Always Direct)"
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = when {
                                            /* GEMINI_NANO_DISABLED
                                            settings.localLlmBackend == LocalLlmBackend.GEMINI_NANO && !activeThinkingSupported ->
                                                "${selectedNanoVariant.title} runs in direct fast output mode on this device."
                                            settings.localLlmBackend == LocalLlmBackend.GEMINI_NANO ->
                                                "Skips Gemini Nano's thinking pass for lower-latency direct output."
                                            */
                                            !activeThinkingSupported ->
                                                "${selectedLlm.title} is a direct non-thinking model."
                                            selectedLlm.defaultDisableThinking ->
                                                "Skips ${selectedLlm.title}'s reasoning step for fast direct output. On by default."
                                            else ->
                                                "Skips ${selectedLlm.title}'s reasoning step for direct output. Off by default."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = if (activeThinkingSupported) settings.gemmaDisableThinking else true,
                                    enabled = activeThinkingSupported,
                                    onCheckedChange = { disableThinking ->
                                        if (activeThinkingSupported) {
                                            persistAndReload(settings.copy(gemmaDisableThinking = disableThinking))
                                        }
                                    }
                                )
                            }

                            if (activeThinkingSupported) {
                                val adaptiveSwitchEnabled = !settings.gemmaDisableThinking
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = adaptiveSwitchEnabled) {
                                            persistAndReload(settings.copy(gemmaAdaptiveThinking = !settings.gemmaAdaptiveThinking))
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 12.dp)
                                    ) {
                                        Text(
                                            text = "Adaptive Smart Thinking",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Uses reasoning only on self-corrections, spoken lists, or structural rewrites, and outputs directly on simple sentences. On by default.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = settings.gemmaAdaptiveThinking && !settings.gemmaDisableThinking,
                                        enabled = adaptiveSwitchEnabled,
                                        onCheckedChange = { adaptive ->
                                            persistAndReload(settings.copy(gemmaAdaptiveThinking = adaptive))
                                        }
                                    )
                                }

                                if (!settings.gemmaDisableThinking) {
                                    ThinkingEffortDropdown(
                                        title = "Thinking Effort",
                                        subtitle = "Controls how much reasoning token budget ${selectedLlm.title} uses when thinking is active.",
                                        options = LlmThinkingEffort.LOCAL_OPTIONS,
                                        selected = settings.localThinkingEffort,
                                        onSelect = { effort ->
                                            persistAndReload(settings.copy(localThinkingEffort = effort))
                                        }
                                    )
                                }
                            }

                            SettingToggleRow(
                                title = "Split Long Text into Chunks",
                                subtitle = "Splits long transcripts into sentence-bounded passes for finite KV cache memory. On by default for local models.",
                                checked = settings.localLlmChunkingEnabled,
                                onCheckedChange = { chunking ->
                                    persistAndReload(settings.copy(localLlmChunkingEnabled = chunking))
                                }
                            )
                        } else {
                            OpenAiApiEndpointSection(
                                settings = settings,
                                onPersistSettings = persistAndReload
                            )
                        }

                        HorizontalDivider()

                        Text(
                            text = "System Prompt Preset",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (settings.llmProvider == LlmProviderMode.OPENAI_API) {
                                "Sent as the system instruction to your OpenAI-compatible endpoint alongside Custom Vocabulary and anti-chatter guardrails."
                            } else {
                                "Each preset is tuned specifically for ${selectedLlm.title}'s size and architecture, or you can write a custom system prompt."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        GemmaPromptPreset.entries.forEach { preset ->
                            val selected = settings.gemmaPromptPreset == preset
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (!selected) {
                                            persistAndReload(settings.copy(gemmaPromptPreset = preset))
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            persistAndReload(settings.copy(gemmaPromptPreset = preset))
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        text = preset.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (settings.gemmaPromptPreset == GemmaPromptPreset.CUSTOM) {
                            OutlinedTextField(
                                value = customPromptDraft,
                                onValueChange = { updatedText ->
                                    customPromptDraft = updatedText
                                    persistAndReload(settings.copy(gemmaCustomPrompt = updatedText))
                                },
                                label = { Text("Custom System Prompt") },
                                placeholder = {
                                    Text(
                                        GemmaPromptPreset.CUSTOM.promptForProvider(
                                            settings.llmProvider,
                                            selectedLlm
                                        )
                                    )
                                },
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (customPromptDraft.isBlank()) {
                                        "Using fallback instruction until you enter custom text."
                                    } else {
                                        "Anti-chatter & Custom Vocabulary guardrails are applied automatically."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (customPromptDraft.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            customPromptDraft = ""
                                            persistAndReload(settings.copy(gemmaCustomPrompt = ""))
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reset")
                                    }
                                }
                            }
                        } else {
                            val activeOptimizedPrompt = settings.gemmaPromptPreset.promptForProvider(
                                settings.llmProvider,
                                selectedLlm
                            )
                            var instructionPreviewExpanded by rememberSaveable { mutableStateOf(false) }
                            val instructionHeaderTitle = if (settings.llmProvider == LlmProviderMode.OPENAI_API) {
                                "Active System Instruction (Cloud API · ${settings.gemmaPromptPreset.shortLabel})"
                            } else {
                                "Active System Instruction (${selectedLlm.title})"
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { instructionPreviewExpanded = !instructionPreviewExpanded }
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        ) {
                                            Text(
                                                text = instructionHeaderTitle,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (!instructionPreviewExpanded) {
                                                Text(
                                                    text = "Tap to view or copy active prompt",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (instructionPreviewExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = if (instructionPreviewExpanded) "Collapse system instruction" else "Expand system instruction",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    AnimatedVisibility(visible = instructionPreviewExpanded) {
                                        Column(
                                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = activeOptimizedPrompt,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    customPromptDraft = activeOptimizedPrompt
                                                    persistAndReload(
                                                        settings.copy(
                                                            gemmaPromptPreset = GemmaPromptPreset.CUSTOM,
                                                            gemmaCustomPrompt = activeOptimizedPrompt
                                                        )
                                                    )
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Copy & Edit as Custom Prompt")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val isSelectedLlmDownloaded = downloadedStatusMap[selectedDesertModel] == true
                        if (settings.llmProvider == LlmProviderMode.LOCAL && isSelectedLlmDownloaded) {
                            HorizontalDivider()
                            LlmSpeedBenchmarkSection(
                                selectedLlm = selectedLlm,
                                smartPipeline = smartPipeline,
                                isPipelineBusy = pipelineStatus.phase == PipelinePhase.TRANSCRIBING || downloadState.isRunning
                            )
                        }
                    }
                }
            }
        }

        // 4. Manual / Fallback Language Selector (Always Visible, Specification Section 5.2.3)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val selectorTitle = if (settings.earEnabled) {
                    "Fallback Language (Used if auto-detect is uncertain)"
                } else {
                    "Transcription Language (Manual)"
                }

                Text(
                    text = selectorTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Supported by Voz (${Voz.supportedLanguages.size} European languages)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Voz.supportedLanguages.forEach { langCode ->
                        val selected = settings.preferredLanguage == langCode
                        FilterChip(
                            selected = selected,
                            onClick = {
                                persistAndReload(settings.copy(preferredLanguage = langCode))
                            },
                            label = {
                                Text(ModelSettings.languageDisplayName(langCode))
                            }
                        )
                    }
                }
            }
        }

        // 5. Custom Vocabulary Editor (Specification Section 5.3)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Custom Vocabulary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Add domain-specific terms, proper nouns, or acronyms to bias Voz transcription.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newVocabInput,
                        onValueChange = { newVocabInput = it },
                        label = { Text("Add word or phrase") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val trimmed = newVocabInput.trim()
                            if (trimmed.isNotEmpty() && trimmed !in settings.customVocabulary) {
                                val updatedVocab = settings.customVocabulary + trimmed
                                newVocabInput = ""
                                persistAndReload(settings.copy(customVocabulary = updatedVocab))
                            } else {
                                newVocabInput = ""
                            }
                        },
                        enabled = newVocabInput.trim().isNotEmpty()
                    ) {
                        Text("Add")
                    }
                }

                if (settings.customVocabulary.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        settings.customVocabulary.forEach { term ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    val updatedVocab = settings.customVocabulary.filterNot { it == term }
                                    persistAndReload(settings.copy(customVocabulary = updatedVocab))
                                },
                                label = { Text(term) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove $term",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // 6. Storage Management Section (Specification Section 5.5)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Model Storage Management",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                val storageModels = buildList {
                    add(DesertAntModel.VOZ)
                    add(DesertAntModel.EAR)
                    add(DesertAntModel.PUNCT)
                    add(settings.selectedLlmModel.desertAntModel)
                    DesertAntModel.entries.forEach { candidate ->
                        val hasCache = downloadedStatusMap[candidate] == true || (diskBytesMap[candidate] ?: 0L) > 0L
                        if (candidate.isLlmModel && hasCache && candidate !in this) {
                            add(candidate)
                        }
                    }
                }

                storageModels.forEach { model ->
                    val isDownloaded = downloadedStatusMap[model] == true
                    val cachedBytes = diskBytesMap[model] ?: 0L
                    val isOnDisk = isDownloaded || cachedBytes > 0L
                    val isEnabled = settings.isModelEnabled(model)
                    val isBypassedByAi = model == DesertAntModel.PUNCT && isEnabled && settings.isAiPostProcessingActive
                    val isLocallyRequired = settings.isModelLocallyRequired(model)
                    val canDelete = settings.canDeleteModel(model) && isOnDisk

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            val loadState = pipelineStatus.modelStates[model]
                            val ramTag = if (loadState == ModelLoadState.LOADED) " • Loaded" else ""
                            val sizeLabel = if (isOnDisk && cachedBytes > 0L) {
                                formatStorageBytesLabel(cachedBytes)
                            } else if (model.isLlmModel) {
                                model.sizeGbLabel
                            } else {
                                "${model.sizeMb} MB"
                            }
                            val statusText = when {
                                model == DesertAntModel.VOZ && settings.isRemoteVozActive ->
                                    if (isOnDisk) "Downloaded • $sizeLabel (Unused — Remote Voz API active)" else "Using Remote Voz API (${model.sizeMb} MB saved)"
                                model == DesertAntModel.EAR && settings.isRemoteEarActive ->
                                    if (isOnDisk) "Downloaded • $sizeLabel (Unused — Remote Ear API active)" else "Using Remote Ear API (${model.sizeMb} MB saved)"
                                isLocallyRequired && isOnDisk -> "Downloaded • $sizeLabel (Required$ramTag)"
                                isLocallyRequired -> "Not downloaded (${model.sizeMb} MB • Required locally)"
                                !isDownloaded && cachedBytes > 0L -> "Partial cache • $sizeLabel"
                                isDownloaded && isBypassedByAi -> if (settings.isOpenAiApiEnabled) {
                                    "Downloaded • $sizeLabel (Bypassed by OpenAI API)"
                                } else {
                                    "Downloaded • $sizeLabel (Bypassed by AI)"
                                }
                                isDownloaded && isEnabled -> "Downloaded • $sizeLabel (Active$ramTag)"
                                isDownloaded && !isEnabled -> "Downloaded • $sizeLabel (Inactive)"
                                else -> "Not downloaded (${if (model.isLlmModel) model.sizeGbLabel else "${model.sizeMb} MB"})"
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isLocallyRequired) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Required",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (canDelete) {
                            OutlinedButton(
                                enabled = !isDeletingStorage,
                                onClick = { deleteLocalModelsSafely(listOf(model)) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", maxLines = 1)
                            }
                        }
                    }
                }

                val unusedModelsOnDisk = DesertAntModel.entries.filter { model ->
                    val onDisk = downloadedStatusMap[model] == true || (diskBytesMap[model] ?: 0L) > 0L
                    settings.canDeleteModel(model) && onDisk
                }
                val reclaimableBytes = unusedModelsOnDisk.sumOf { model ->
                    maxOf(diskBytesMap[model] ?: 0L, if (downloadedStatusMap[model] == true) model.expectedBytes else 0L)
                }

                if (settings.hasAnyRemoteApiActive && unusedModelsOnDisk.isNotEmpty()) {
                    Text(
                        text = "Cloud / Remote API active — ${formatStorageBytesLabel(reclaimableBytes)} of replaced local models can be deleted safely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedButton(
                    onClick = { deleteLocalModelsSafely(unusedModelsOnDisk) },
                    enabled = !isDeletingStorage && unusedModelsOnDisk.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isDeletingStorage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Deleting local models...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (unusedModelsOnDisk.isNotEmpty()) {
                                "Delete all unused models (${formatStorageBytesLabel(reclaimableBytes)})"
                            } else {
                                "No unused local models on disk"
                            }
                        )
                    }
                }

                if (storageActionFeedback != null) {
                    Text(
                        text = storageActionFeedback ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Powered by Desert Ant Labs",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun LlmModelOptionCard(
    choice: LlmModelChoice,
    selected: Boolean,
    isDownloaded: Boolean,
    loadState: ModelLoadState?,
    isDownloading: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    }
    val stateLabel = when {
        isDownloading -> "Downloading..."
        loadState == ModelLoadState.LOADED || loadState == ModelLoadState.ACTIVE -> "Loaded"
        isDownloaded -> "Downloaded"
        else -> null
    }
    val stateColor = when {
        isDownloading -> Color(0xFFEF6C00)
        loadState == ModelLoadState.LOADED || loadState == ModelLoadState.ACTIVE -> Color(0xFF2E7D32)
        isDownloaded -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = choice.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (choice) {
                            LlmModelChoice.GEMMA_4_E2B -> MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Text(
                            text = choice.badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when (choice) {
                                LlmModelChoice.GEMMA_4_E2B -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${choice.sizeGbLabel} • ${choice.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (stateLabel != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = stateColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusChip(
    model: DesertAntModel,
    state: ModelLoadState
) {
    val (bgColor, textColor, dotColor) = when (state) {
        ModelLoadState.LOADED -> Triple(
            Color(0xFF1B5E20).copy(alpha = 0.16f),
            Color(0xFF2E7D32),
            Color(0xFF2E7D32)
        )
        ModelLoadState.REMOTE -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.primary
        )
        ModelLoadState.ACTIVE -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary
        )
        ModelLoadState.LOADING -> Triple(
            Color(0xFFE65100).copy(alpha = 0.16f),
            Color(0xFFEF6C00),
            Color(0xFFEF6C00)
        )
        ModelLoadState.STANDBY -> Triple(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outline
        )
        ModelLoadState.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error
        )
        ModelLoadState.DISABLED,
        ModelLoadState.BYPASSED,
        ModelLoadState.NOT_DOWNLOADED -> Triple(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "${model.shortLabel}: ${state.label}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Composable
private fun ModelToggleItem(
    title: String,
    subtitle: String,
    loadState: ModelLoadState?,
    checked: Boolean,
    enabled: Boolean,
    isLocked: Boolean,
    activeDownloadState: DownloadSessionState?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (isLocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Required",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (loadState != null) {
                    val statusColor = when (loadState) {
                        ModelLoadState.LOADED -> Color(0xFF2E7D32)
                        ModelLoadState.ACTIVE -> MaterialTheme.colorScheme.primary
                        ModelLoadState.LOADING -> Color(0xFFEF6C00)
                        ModelLoadState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "Status: ${loadState.label}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }

        if (activeDownloadState != null) {
            LinearProgressIndicator(
                progress = { activeDownloadState.modelProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (activeDownloadState.isRunning) {
                            "${(activeDownloadState.modelProgress * 100).roundToInt()}% (${activeDownloadState.formattedModelBytes}) • ${activeDownloadState.formattedSpeed} • ${activeDownloadState.formattedEta}"
                        } else {
                            "Paused at ${(activeDownloadState.modelProgress * 100).roundToInt()}% (${activeDownloadState.formattedModelBytes})"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (activeDownloadState.statusNote != null) {
                        Text(
                            text = activeDownloadState.statusNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (activeDownloadState.isRunning) {
                    OutlinedButton(onClick = onPause) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Pause",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause")
                    }
                } else if (activeDownloadState.isPaused) {
                    Button(onClick = onResume) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume")
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmSpeedBenchmarkSection(
    selectedLlm: LlmModelChoice,
    smartPipeline: SmartPipeline,
    isPipelineBusy: Boolean
) {
    val scope = rememberCoroutineScope()
    val localView = LocalView.current

    // Collapsed by default, expandable / collapsible by tapping the header
    var expanded by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedSample by remember { mutableStateOf(LlmBenchmarkSample.EXAMPLE_1_CASUAL_FILLERS) }
    var customInputText by remember { mutableStateOf(LlmBenchmarkSample.CUSTOM_INPUT.sampleText) }

    var isRunning by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf("") }
    var benchmarkJob by remember { mutableStateOf<Job?>(null) }
    var benchmarkResult by remember { mutableStateOf<LlmBenchmarkResult?>(null) }
    var benchmarkError by remember { mutableStateOf<String?>(null) }

    // Keep the display awake while benchmarking in Settings
    DisposableEffect(isRunning) {
        val prevKeepOn = localView.keepScreenOn
        if (isRunning) {
            localView.keepScreenOn = true
        }
        onDispose {
            localView.keepScreenOn = prevKeepOn
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Speed Benchmark (${selectedLlm.title})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Test Uncached vs Cached System Prompt (tokens/sec & output)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse benchmark" else "Expand benchmark",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Sample selector dropdown (5 examples + 2 really complex ones + Custom Input)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !isRunning) { dropdownExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Benchmark Input",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = selectedSample.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (selectedSample.isComplex) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = "COMPLEX",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Choose benchmark input"
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        LlmBenchmarkSample.entries.forEach { sample ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = sample.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (sample == selectedSample) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (sample.isComplex) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Text(
                                                    text = "COMPLEX",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    selectedSample = sample
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                val activeInputText = if (selectedSample.isCustom) {
                    customInputText
                } else {
                    selectedSample.sampleText
                }

                if (selectedSample.isCustom) {
                    OutlinedTextField(
                        value = customInputText,
                        onValueChange = { customInputText = it },
                        enabled = !isRunning,
                        label = { Text("Custom Benchmark Input") },
                        placeholder = { Text("Type or dictate text to benchmark...") },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Raw Input (${activeInputText.length} chars):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = activeInputText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val inputToRun = activeInputText.trim()
                            if (inputToRun.isEmpty()) return@Button
                            benchmarkError = null
                            isRunning = true
                            progressLabel = "Preparing ${selectedLlm.title}..."
                            benchmarkJob = scope.launch {
                                try {
                                    val result = smartPipeline.runLlmBenchmark(
                                        inputText = inputToRun,
                                        onProgress = { msg ->
                                            scope.launch(Dispatchers.Main) {
                                                progressLabel = msg
                                            }
                                        }
                                    )
                                    benchmarkResult = result
                                } catch (ce: CancellationException) {
                                    benchmarkError = null
                                } catch (t: Throwable) {
                                    benchmarkError = if (t is LinkageError) {
                                        "${t.javaClass.simpleName}: ${t.message ?: "initialization error"}"
                                    } else {
                                        t.message?.takeIf { it.isNotBlank() } ?: "${t.javaClass.simpleName}: Benchmark failed"
                                    }
                                } finally {
                                    isRunning = false
                                    progressLabel = ""
                                    benchmarkJob = null
                                }
                            }
                        },
                        enabled = !isRunning && !isPipelineBusy && activeInputText.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Benchmarking...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Benchmark ${selectedLlm.shortLabel}")
                        }
                    }

                    if (isRunning) {
                        OutlinedButton(
                            onClick = {
                                benchmarkJob?.cancel()
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                if (isRunning && progressLabel.isNotBlank()) {
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!benchmarkError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = benchmarkError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                val result = benchmarkResult
                if (result != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${result.modelTitle} • ${result.backendSummary}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Preset: ${result.promptPresetTitle} • ${result.thinkingModeSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BenchmarkMetricPassCard(
                                    title = "Prompt Uncached",
                                    metrics = result.uncached,
                                    highlighted = false,
                                    extraNote = "Prefill: ${result.uncached.prefillTokens} tok (${String.format(Locale.US, "%.0f", result.uncached.prefillTokensPerSec)} tok/s)",
                                    modifier = Modifier.weight(1f)
                                )
                                if (result.cached != null) {
                                    BenchmarkMetricPassCard(
                                        title = "Prompt Cached",
                                        metrics = result.cached,
                                        highlighted = true,
                                        extraNote = if (result.cachedSystemPromptTokens > 0) {
                                            "Cached ${result.cachedSystemPromptTokens} tok (prewarm ${result.cachePrewarmMs} ms)"
                                        } else {
                                            "KV prewarm: ${result.cachePrewarmMs} ms"
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            val cachedPass = result.cached
                            val speedup = result.speedupFactor
                            if (cachedPass != null && speedup != null) {
                                val savedMs = result.uncached.totalMs - cachedPass.totalMs
                                val summaryText = if (savedMs > 0L) {
                                    String.format(
                                        Locale.US,
                                        "KV Cache Speedup: %.2f× faster end-to-end (saved %d ms • TTFT %d ms → %d ms)",
                                        speedup,
                                        savedMs,
                                        result.uncached.ttftMs,
                                        cachedPass.ttftMs
                                    )
                                } else {
                                    String.format(
                                        Locale.US,
                                        "TTFT: %d ms uncached → %d ms cached (one-time prewarm %d ms)",
                                        result.uncached.ttftMs,
                                        cachedPass.ttftMs,
                                        result.cachePrewarmMs
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = summaryText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            } else if (!result.cacheUnavailableReason.isNullOrBlank()) {
                                Text(
                                    text = result.cacheUnavailableReason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            HorizontalDivider()

                            Text(
                                text = "Model Output:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = result.cleanedOutput,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkMetricPassCard(
    title: String,
    metrics: LlmSinglePassMetrics,
    highlighted: Boolean,
    extraNote: String,
    modifier: Modifier = Modifier
) {
    val borderColor = if (highlighted) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        modifier = modifier.border(1.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format(Locale.US, "%.1f tok/s", metrics.effectiveTokensPerSec),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = String.format(Locale.US, "Decode: %.1f tok/s (%d tok)", metrics.decodeTokensPerSec, metrics.decodeTokens),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Total: ${metrics.totalMs} ms • TTFT: ${metrics.ttftMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = extraNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenAiApiEndpointSection(
    settings: ModelSettings,
    onPersistSettings: (ModelSettings) -> Unit
) {
    val urlReady = OpenAiCompatibleClient.isValidHttpUrl(settings.openAiBaseUrl)
    val modelReady = settings.openAiModel.trim().isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingToggleRow(
            title = "Enable AI Post-Processing (OpenAI API)",
            subtitle = when {
                !urlReady || !modelReady -> "Enter a valid Base URL & Model ID below."
                settings.gemmaEnabled -> "Active · ${settings.openAiModel.trim()} (${settings.openAiTimeoutSeconds}s timeout)"
                else -> "Configured · Toggle on to format transcripts remotely."
            },
            checked = settings.gemmaEnabled && settings.llmProvider == LlmProviderMode.OPENAI_API,
            onCheckedChange = { enabled ->
                onPersistSettings(
                    settings.copy(
                        llmProvider = LlmProviderMode.OPENAI_API,
                        gemmaEnabled = enabled
                    )
                )
            }
        )

        key("openai_llm_endpoint") {
            RemoteEndpointFieldsBlock(
                baseUrl = settings.openAiBaseUrl,
                apiKey = settings.openAiApiKey,
                model = settings.openAiModel,
                timeoutSeconds = settings.openAiTimeoutSeconds,
                presets = OpenAiCompatibleClient.PRESETS.asCompat(),
                urlPlaceholder = "https://api.openai.com/v1",
                modelPlaceholder = "gpt-4o-mini",
                urlSupportingDefault = "Accepts base URL, host:port (LAN Ollama/LM Studio), or /chat/completions.",
                resolveEndpointPreview = OpenAiCompatibleClient::resolveChatCompletionsUrl,
                includeAudioModelsInFetch = false,
                timeoutOptions = listOf(10, 20, 30, 45),
                testButtonLabel = "Test Connection",
                testingButtonLabel = "Testing API Connection...",
                onFieldsCommit = { url, key, model ->
                    onPersistSettings(
                        settings.copy(
                            llmProvider = LlmProviderMode.OPENAI_API,
                            openAiBaseUrl = url,
                            openAiApiKey = key,
                            openAiModel = model
                        )
                    )
                },
                onTimeoutChange = { seconds ->
                    onPersistSettings(
                        settings.copy(
                            llmProvider = LlmProviderMode.OPENAI_API,
                            openAiTimeoutSeconds = seconds
                        )
                    )
                },
                onTestConnection = { url, key, model, timeout ->
                    OpenAiCompatibleClient.testConnection(
                        baseUrl = url,
                        apiKey = key,
                        model = model,
                        timeoutSeconds = timeout,
                        thinkingEffort = settings.openAiThinkingEffort,
                        customReasoningEffort = settings.openAiCustomReasoningEffort
                    )
                },
                footerTip = if (settings.isRemoteVozActive) {
                    "Tip: Hidden reasoning/thinking output is automatically stripped so only final formatted text is pasted."
                } else {
                    "Tip: Voice audio is transcribed offline on your device first; only transcript text is sent, and hidden reasoning/thinking output is stripped automatically."
                }
            )
        }

        ThinkingEffortDropdown(
            title = "Reasoning Effort",
            subtitle = "Choose a reasoning depth preset (including Max) or enter any custom level supported by your endpoint. Hidden thinking blocks are stripped automatically.",
            options = LlmThinkingEffort.API_OPTIONS,
            selected = settings.openAiThinkingEffort,
            allowCustom = true,
            customValue = settings.openAiCustomReasoningEffort,
            showApiBadges = true,
            onSelect = { effort ->
                onPersistSettings(settings.copy(openAiThinkingEffort = effort))
            },
            onCustomValueChange = { custom ->
                onPersistSettings(
                    settings.copy(
                        openAiThinkingEffort = LlmThinkingEffort.CUSTOM,
                        openAiCustomReasoningEffort = custom
                    )
                )
            }
        )

        SettingToggleRow(
            title = "Split Long Text into Chunks",
            subtitle = "Off by default — cloud models have huge context windows and format your entire transcript in one pass. Enable only for small local servers.",
            checked = settings.openAiChunkingEnabled,
            onCheckedChange = { chunking ->
                onPersistSettings(settings.copy(openAiChunkingEnabled = chunking))
            }
        )
    }
}

// GeminiNanoStatusChip, GeminiNanoCollapsibleSection, and GeminiNanoVariantCard live in GeminiNanoSettingsComponents.kt
// (excluded from compilation via app/build.gradle.kts while Gemini Nano is disabled).

private fun formatStorageBytesLabel(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) {
        return String.format(Locale.US, "%.2f GB", gb)
    }
    val mb = (bytes.toDouble() / (1024.0 * 1024.0)).roundToInt().coerceAtLeast(1)
    return "$mb MB"
}
