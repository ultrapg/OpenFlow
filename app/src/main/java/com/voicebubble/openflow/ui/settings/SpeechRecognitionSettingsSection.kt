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
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.speech.ModelLoadState
import com.voicebubble.openflow.core.speech.PipelinePhase
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.DownloadSessionState
import com.voicebubble.openflow.settings.HardwareAccelerator
import com.voicebubble.openflow.settings.ModelDownloadManager
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.Dispatchers
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
            ModelDownloadManager.cancelAutoEnable(model)
            val updated = when (model) {
                DesertAntModel.EAR -> settings.copy(earEnabled = false)
                DesertAntModel.CLEAR -> settings.copy(clearEnabled = false)
                DesertAntModel.UHM -> settings.copy(uhmEnabled = false)
                DesertAntModel.VOZ -> settings
            }
            persistAndReload(updated)
        } else {
            scope.launch {
                val alreadyDownloaded = withContext(Dispatchers.IO) {
                    DesertAntModelStorage.isModelDownloaded(context, model)
                }
                if (alreadyDownloaded) {
                    val updated = when (model) {
                        DesertAntModel.EAR -> settings.copy(earEnabled = true)
                        DesertAntModel.CLEAR -> settings.copy(clearEnabled = true)
                        DesertAntModel.UHM -> settings.copy(uhmEnabled = true)
                        DesertAntModel.VOZ -> settings
                    }
                    persistAndReload(updated)
                } else {
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

                // Per-model live status pills
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DesertAntModel.entries.forEach { model ->
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
                        Text(
                            text = "Active Disk: ${settings.activeDiskFootprintMb} MB ($downloadedCount/4)",
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
                            text = "Peak RAM: ~${settings.estimatedPeakRamMb} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                val isWorking = pipelineStatus.phase == PipelinePhase.LOADING ||
                    pipelineStatus.phase == PipelinePhase.WARMING_UP ||
                    pipelineStatus.phase == PipelinePhase.TRANSCRIBING
                OutlinedButton(
                    onClick = { smartPipeline.initializeAsync(warmUp = true) },
                    enabled = !isWorking && !downloadState.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (pipelineStatus.isReady) Icons.Default.CheckCircle else Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            isWorking -> "Loading & Warming Up Models..."
                            pipelineStatus.loadedCount == pipelineStatus.enabledCount && pipelineStatus.isReady ->
                                "All Enabled Models Loaded in RAM (Tap to Re-Warm)"
                            else -> "Load & Warm Up Models in RAM Now"
                        }
                    )
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
                    title = "Voz — Speech Recognition (337 MB)",
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
                    title = "Ear — Auto Language Detection (22 MB)",
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

                // Clear
                ModelToggleItem(
                    title = "Clear — Noise Suppression (24 MB)",
                    subtitle = DesertAntModel.CLEAR.roleSummary,
                    loadState = pipelineStatus.modelStates[DesertAntModel.CLEAR],
                    checked = settings.clearEnabled || DesertAntModel.CLEAR in downloadState.pendingAutoEnableModels,
                    enabled = !(downloadState.isRunning && downloadState.activeModel == DesertAntModel.CLEAR),
                    isLocked = false,
                    activeDownloadState = if (downloadState.activeModel == DesertAntModel.CLEAR && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(DesertAntModel.CLEAR, true) },
                    onCheckedChange = { handleOptionalToggle(DesertAntModel.CLEAR, it) }
                )

                HorizontalDivider()

                // Uhm
                ModelToggleItem(
                    title = "Uhm — Filler Word Removal (24 MB)",
                    subtitle = DesertAntModel.UHM.roleSummary,
                    loadState = pipelineStatus.modelStates[DesertAntModel.UHM],
                    checked = settings.uhmEnabled || DesertAntModel.UHM in downloadState.pendingAutoEnableModels,
                    enabled = !(downloadState.isRunning && downloadState.activeModel == DesertAntModel.UHM),
                    isLocked = false,
                    activeDownloadState = if (downloadState.activeModel == DesertAntModel.UHM && (downloadState.isRunning || downloadState.isPaused)) downloadState else null,
                    onPause = { ModelDownloadManager.pauseDownloads() },
                    onResume = { handleOptionalToggle(DesertAntModel.UHM, true) },
                    onCheckedChange = { handleOptionalToggle(DesertAntModel.UHM, it) }
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

                DesertAntModel.entries.forEach { model ->
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
                            val ramTag = if (loadState == ModelLoadState.LOADED) " • Loaded in RAM" else ""
                            val statusText = when {
                                model.isRequired && isDownloaded -> "Downloaded • ${model.sizeMb} MB (Required$ramTag)"
                                isDownloaded && isEnabled -> "Downloaded • ${model.sizeMb} MB (Active$ramTag)"
                                isDownloaded && !isEnabled -> "Downloaded • ${model.sizeMb} MB (Inactive)"
                                else -> "Not downloaded (${model.sizeMb} MB)"
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
                text = "${model.id.replaceFirstChar { it.uppercase() }}: ${state.label}",
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
