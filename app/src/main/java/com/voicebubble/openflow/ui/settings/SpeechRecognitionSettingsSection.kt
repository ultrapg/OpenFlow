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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.speech.LlmBenchmarkResult
import com.voicebubble.openflow.core.speech.LlmSinglePassMetrics
import com.voicebubble.openflow.core.speech.ModelLoadState
import com.voicebubble.openflow.core.speech.PipelinePhase
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.DownloadSessionState
import com.voicebubble.openflow.settings.GemmaPromptPreset
import com.voicebubble.openflow.settings.HardwareAccelerator
import com.voicebubble.openflow.settings.LlmBenchmarkSample
import com.voicebubble.openflow.settings.LlmModelChoice
import com.voicebubble.openflow.settings.ModelDownloadManager
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    var storageRefreshTick by remember { mutableIntStateOf(0) }
    var newVocabInput by remember { mutableStateOf("") }
    var customPromptDraft by remember { mutableStateOf(settings.gemmaCustomPrompt) }
    LaunchedEffect(settings.gemmaCustomPrompt) {
        if (customPromptDraft != settings.gemmaCustomPrompt) {
            customPromptDraft = settings.gemmaCustomPrompt
        }
    }

    val downloadedStatusMap = remember { mutableStateMapOf<DesertAntModel, Boolean>() }
    LaunchedEffect(
        settings,
        storageRefreshTick,
        downloadState.isRunning,
        downloadState.isCompleted,
        downloadState.pendingAutoEnableModels
    ) {
        val latestPersisted = ModelSettings.load(context)
        if (latestPersisted != settings) {
            onSettingsChanged(latestPersisted)
        }
        val status = withContext(Dispatchers.IO) {
            DesertAntModel.entries.associateWith { model ->
                DesertAntModelStorage.isModelDownloaded(context, model)
            }
        }
        downloadedStatusMap.clear()
        downloadedStatusMap.putAll(status)
        smartPipeline.refreshStatusSnapshot()
    }
    val downloadedCount = downloadedStatusMap.values.count { it }

    val persistAndReload: (ModelSettings) -> Unit = { updated ->
        ModelSettings.save(context, updated)
        onSettingsChanged(updated)
        smartPipeline.reloadSettingsAsync(updated)
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
        } else {
            scope.launch {
                val alreadyDownloaded = withContext(Dispatchers.IO) {
                    DesertAntModelStorage.isModelDownloaded(context, model)
                }
                if (alreadyDownloaded) {
                    if (model.isLlmModel) {
                        ModelDownloadManager.cancelAllLlmAutoEnables()
                        val llmChoice = LlmModelChoice.fromDesertAntModel(model) ?: settings.selectedLlmModel
                        persistAndReload(settings.copy(gemmaEnabled = true, selectedLlmModel = llmChoice))
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
                        ModelDownloadManager.cancelAllLlmAutoEnables(keepModel = model)
                        // Immediately unload any previously active LLM while downloading the newly chosen model
                        persistAndReload(settings.copy(gemmaEnabled = false, selectedLlmModel = llmChoice))
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
        val anyLlmActiveOrPending = settings.gemmaEnabled ||
            downloadState.pendingAutoEnableModels.any { it.isLlmModel }
        if (!anyLlmActiveOrPending) {
            if (settings.selectedLlmModel != choice) {
                persistAndReload(settings.copy(selectedLlmModel = choice))
            }
        } else {
            // Switching models while AI Post-Processing is enabled (or downloading):
            // immediately switch & unload any previous LLM, or download + auto-enable the newly chosen model
            handleOptionalToggle(choice.desertAntModel, true)
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

                // Voz (Locked ON)
                ModelToggleItem(
                    title = "Voz — Speech Recognition (${DesertAntModel.VOZ.sizeMb} MB)",
                    subtitle = "Required • Core transcription across 25 European languages",
                    loadState = pipelineStatus.modelStates[DesertAntModel.VOZ],
                    checked = true,
                    enabled = false,
                    isLocked = true,
                    activeDownloadState = if (downloadState.activeModel == DesertAntModel.VOZ && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(DesertAntModel.VOZ, true) },
                    onCheckedChange = {}
                )

                HorizontalDivider()

                // Ear
                ModelToggleItem(
                    title = "Ear — Auto Language Detection (${DesertAntModel.EAR.sizeMb} MB)",
                    subtitle = DesertAntModel.EAR.roleSummary,
                    loadState = pipelineStatus.modelStates[DesertAntModel.EAR],
                    checked = settings.earEnabled || DesertAntModel.EAR in downloadState.pendingAutoEnableModels,
                    enabled = !(downloadState.isRunning && downloadState.activeModel == DesertAntModel.EAR),
                    isLocked = false,
                    activeDownloadState = if (downloadState.activeModel == DesertAntModel.EAR && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(DesertAntModel.EAR, true) },
                    onCheckedChange = { handleOptionalToggle(DesertAntModel.EAR, it) }
                )

                HorizontalDivider()

                // Punct (Off by default)
                val punctSubtitle = if (settings.gemmaEnabled) {
                    "Automatically bypassed & kept out of RAM while ${settings.selectedLlmModel.shortLabel} is active"
                } else {
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
            }
        }

        // 3b. AI Post-Processing & Reformatting (5 Selectable Local LLMs • Smart Single-Model RAM)
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
                val selectedLlm = settings.selectedLlmModel
                val selectedDesertModel = selectedLlm.desertAntModel
                val anyLlmPendingAutoEnable = downloadState.pendingAutoEnableModels.any { it.isLlmModel }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI Post-Processing & Cleanup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "OFFLINE AI",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "Removes fillers ('um', 'uh'), self-corrections, and formats numbers & lists offline via LiteRT-LM. Off by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val activeGemmaBackend = pipelineStatus.activeGemmaBackendLabel
                if (!activeGemmaBackend.isNullOrBlank()) {
                    Text(
                        text = "Active ${selectedLlm.title} runtime: $activeGemmaBackend",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                ModelToggleItem(
                    title = "Enable AI Post-Processing (${selectedLlm.title})",
                    subtitle = "${selectedLlm.sizeGbLabel} • Auto-unloads when idle",
                    loadState = pipelineStatus.modelStates[selectedDesertModel],
                    checked = settings.gemmaEnabled || anyLlmPendingAutoEnable,
                    enabled = !(downloadState.isRunning && downloadState.activeModel == selectedDesertModel),
                    isLocked = false,
                    activeDownloadState = if (downloadState.activeModel == selectedDesertModel && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(selectedDesertModel, true) },
                    onCheckedChange = { handleOptionalToggle(selectedDesertModel, it) }
                )

                HorizontalDivider()

                Text(
                    text = "Choose AI Model",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Only one model is loaded on demand when you dictate.",
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

                HorizontalDivider()

                val supportsThinking = selectedLlm.supportsThinking
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = supportsThinking) {
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
                            text = if (supportsThinking) {
                                "Disable Model Thinking"
                            } else {
                                "Disable Model Thinking (Always Direct)"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (supportsThinking) {
                                "Skips ${selectedLlm.title}'s reasoning step for direct output. Off by default."
                            } else {
                                "${selectedLlm.title} is a direct non-thinking model."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = if (supportsThinking) settings.gemmaDisableThinking else true,
                        enabled = supportsThinking,
                        onCheckedChange = { disableThinking ->
                            if (supportsThinking) {
                                persistAndReload(settings.copy(gemmaDisableThinking = disableThinking))
                            }
                        }
                    )
                }

                if (supportsThinking) {
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
                }

                HorizontalDivider()

                Text(
                    text = "System Prompt Preset",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Each preset is tuned specifically for ${selectedLlm.title}'s size and architecture, or you can write a custom system prompt.",
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
                        placeholder = { Text(GemmaPromptPreset.CUSTOM.promptForModel(selectedLlm)) },
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
                                "Using ${selectedLlm.title}'s fallback instruction until you enter custom text."
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
                    val activeOptimizedPrompt = settings.gemmaPromptPreset.promptForModel(selectedLlm)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Active System Instruction (Optimized for ${selectedLlm.title}):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
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

                val isSelectedLlmDownloaded = downloadedStatusMap[selectedDesertModel] == true
                if (isSelectedLlmDownloaded) {
                    HorizontalDivider()
                    LlmSpeedBenchmarkSection(
                        selectedLlm = selectedLlm,
                        smartPipeline = smartPipeline,
                        isPipelineBusy = pipelineStatus.phase == PipelinePhase.TRANSCRIBING || downloadState.isRunning
                    )
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
                        if (candidate.isLlmModel && downloadedStatusMap[candidate] == true && candidate !in this) {
                            add(candidate)
                        }
                    }
                }

                storageModels.forEach { model ->
                    val isDownloaded = downloadedStatusMap[model] == true
                    val isEnabled = settings.isModelEnabled(model)
                    val canDelete = !model.isRequired && !isEnabled && isDownloaded

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            val loadState = pipelineStatus.modelStates[model]
                            val ramTag = if (loadState == ModelLoadState.LOADED) " • Loaded" else ""
                            val sizeLabel = if (model.isLlmModel) model.sizeGbLabel else "${model.sizeMb} MB"
                            val statusText = when {
                                model.isRequired && isDownloaded -> "Downloaded • $sizeLabel (Required$ramTag)"
                                isDownloaded && isEnabled -> "Downloaded • $sizeLabel (Active$ramTag)"
                                isDownloaded && !isEnabled -> "Downloaded • $sizeLabel (Inactive)"
                                else -> "Not downloaded ($sizeLabel)"
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (model.isRequired) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Required",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (canDelete) {
                            OutlinedButton(
                                onClick = {
                                    DesertAntModelStorage.deleteModelCache(context, model)
                                    storageRefreshTick++
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete")
                            }
                        }
                    }
                }

                val hasInactiveDownloaded = DesertAntModel.entries.any { model ->
                    !model.isRequired && !settings.isModelEnabled(model) && downloadedStatusMap[model] == true
                }

                OutlinedButton(
                    onClick = {
                        DesertAntModelStorage.deleteAllInactiveModels(context, settings)
                        storageRefreshTick++
                    },
                    enabled = hasInactiveDownloaded,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete all inactive models")
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
                        color = if (choice == LlmModelChoice.QWEN_3_0_6B) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Text(
                            text = choice.badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (choice == LlmModelChoice.QWEN_3_0_6B) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
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
        ModelLoadState.DISABLED, ModelLoadState.NOT_DOWNLOADED -> Triple(
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
