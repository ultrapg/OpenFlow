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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.DownloadSessionState
import com.voicebubble.openflow.settings.ModelDownloadManager
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.settings.supportedLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechRecognitionSettingsSection(
    settings: ModelSettings,
    onSettingsChanged: (ModelSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadState by ModelDownloadManager.sessionState.collectAsState()

    var storageRefreshTick by remember { mutableIntStateOf(0) }
    var pendingEnableModel by remember { mutableStateOf<DesertAntModel?>(null) }
    var newVocabInput by remember { mutableStateOf("") }

    val downloadedStatusMap = remember { mutableStateMapOf<DesertAntModel, Boolean>() }
    LaunchedEffect(settings, storageRefreshTick, downloadState.isRunning, downloadState.isCompleted) {
        val status = withContext(Dispatchers.IO) {
            DesertAntModel.entries.associateWith { model ->
                DesertAntModelStorage.isModelDownloaded(context, model)
            }
        }
        downloadedStatusMap.clear()
        downloadedStatusMap.putAll(status)
    }
    val downloadedCount = downloadedStatusMap.values.count { it }

    val persistAndReload: (ModelSettings) -> Unit = { updated ->
        ModelSettings.save(context, updated)
        onSettingsChanged(updated)
        scope.launch {
            SmartPipeline.getInstance(context).reloadSettings(updated)
        }
    }

    val handleOptionalToggle: (DesertAntModel, Boolean) -> Unit = { model, targetEnabled ->
        if (!targetEnabled) {
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
                    // Settings Toggle-On Background Resumable Download Flow
                    pendingEnableModel = model
                    ModelDownloadManager.startQueue(
                        context = context,
                        models = listOf(model),
                        onAllCompleted = {
                            storageRefreshTick++
                            val latest = ModelSettings.load(context)
                            val updated = when (model) {
                                DesertAntModel.EAR -> latest.copy(earEnabled = true)
                                DesertAntModel.CLEAR -> latest.copy(clearEnabled = true)
                                DesertAntModel.UHM -> latest.copy(uhmEnabled = true)
                                DesertAntModel.VOZ -> latest
                            }
                            pendingEnableModel = null
                            persistAndReload(updated)
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

        // 1. Resource Summary Card (Specification Section 5.2.1 & 5.4)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Resource Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Active Disk: ${settings.activeDiskFootprintMb} MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Peak RAM: ~${settings.estimatedPeakRamMb} MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Text(
                    text = "$downloadedCount / 4 Models Downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // 2. Model Toggle Rows (Specification Section 5.2.2)
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
                    checked = settings.earEnabled,
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
                    checked = settings.clearEnabled,
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
                    checked = settings.uhmEnabled,
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
                    val targetModel = pendingEnableModel ?: downloadState.activeModel
                    if (targetModel != null) {
                        Button(
                            onClick = { handleOptionalToggle(targetModel, true) },
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

        // 3. Manual / Fallback Language Selector (Always Visible, Specification Section 5.2.3)
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

        // 4. Custom Vocabulary Editor (Specification Section 5.3)
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

        // 5. Storage Management Section (Specification Section 5.5)
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
                            val statusText = when {
                                model.isRequired && isDownloaded -> "Downloaded • ${model.sizeMb} MB (Required)"
                                isDownloaded && isEnabled -> "Downloaded • ${model.sizeMb} MB (Active)"
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
private fun ModelToggleItem(
    title: String,
    subtitle: String,
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
