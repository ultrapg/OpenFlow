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

package com.voicebubble.openflow.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.voicebubble.openflow.settings.ModelQueueStatus
import com.voicebubble.openflow.settings.ModelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private enum class OnboardingStep {
    CUSTOMIZE,
    DOWNLOADING
}

@Composable
fun OnboardingScreen(
    initialSettings: ModelSettings,
    onOnboardingComplete: (ModelSettings) -> Unit
) {
    val context = LocalContext.current
    val sessionState by ModelDownloadManager.sessionState.collectAsState()

    var earSelected by remember { mutableStateOf(initialSettings.earEnabled) }

    var step by remember {
        mutableStateOf(
            if (sessionState.isRunning || sessionState.isPaused || sessionState.errorMessage != null) {
                OnboardingStep.DOWNLOADING
            } else {
                OnboardingStep.CUSTOMIZE
            }
        )
    }

    val configuredSettings = initialSettings.copy(
        earEnabled = earSelected
    )

    val selectedModels = configuredSettings.enabledModels()
    val totalSelectedBytes = selectedModels.sumOf { ModelDownloadManager.modelTotalBytes(it) }
    var cachedOrPartialBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(selectedModels, sessionState.overallDownloadedBytes, step) {
        cachedOrPartialBytes = withContext(Dispatchers.IO) {
            selectedModels.sumOf { ModelDownloadManager.getCachedOrPartialBytes(context, it) }
        }
    }

    LaunchedEffect(sessionState.isCompleted) {
        if (sessionState.isCompleted) {
            val allReady = withContext(Dispatchers.IO) {
                DesertAntModelStorage.areAllEnabledModelsDownloaded(context, configuredSettings)
            }
            if (allReady) {
                ModelSettings.save(context, configuredSettings)
                ModelSettings.setOnboardingCompleted(context, true)
                SmartPipeline.getInstance(context).reloadSettingsAsync(configuredSettings)
                onOnboardingComplete(configuredSettings)
            }
        }
    }

    val remainingBytesToDownload = max(0L, totalSelectedBytes - cachedOrPartialBytes)
    val hasPartialProgress = cachedOrPartialBytes > 0L && remainingBytesToDownload > 0L

    val launchDownloadQueue: () -> Unit = {
        step = OnboardingStep.DOWNLOADING
        ModelSettings.save(context, configuredSettings)
        ModelDownloadManager.startQueue(
            context = context,
            models = configuredSettings.enabledModels(),
            onAllCompleted = {
                ModelSettings.save(context, configuredSettings)
                ModelSettings.setOnboardingCompleted(context, true)
                SmartPipeline.getInstance(context).reloadSettingsAsync(configuredSettings)
                onOnboardingComplete(configuredSettings)
            }
        )
    }

    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        launchDownloadQueue()
    }

    val startOrResumeDownloads: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchDownloadQueue()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Welcome to OpenFlow",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "OpenFlow runs 100% offline on your device using the Desert Ant Labs neural audio pipeline on LiteRT. Select which speech models to download once before starting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (step == OnboardingStep.CUSTOMIZE) {
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
                            text = "Select On-Device Models",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Voz (Locked ON)
                        OnboardingModelRow(
                            title = "Voz — Speech Recognition (${DesertAntModel.VOZ.sizeMb} MB, Required)",
                            subtitle = DesertAntModel.VOZ.roleSummary,
                            checked = true,
                            enabled = false,
                            isLocked = true,
                            onCheckedChange = {}
                        )

                        HorizontalDivider()

                        // Ear
                        OnboardingModelRow(
                            title = "Ear — Auto Language Detection (${DesertAntModel.EAR.sizeMb} MB)",
                            subtitle = DesertAntModel.EAR.roleSummary,
                            checked = earSelected,
                            enabled = true,
                            isLocked = false,
                            onCheckedChange = { earSelected = it }
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (hasPartialProgress) {
                                    "Remaining Download: ${DownloadSessionState.formatBytesMb(remainingBytesToDownload)}"
                                } else {
                                    "Total Download: ${configuredSettings.activeDiskFootprintMb} MB"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (hasPartialProgress) {
                                Text(
                                    text = "${DownloadSessionState.formatBytesMb(cachedOrPartialBytes)} already saved on device (resumable)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                text = "Est. Peak RAM: ~${configuredSettings.estimatedPeakRamMb} MB • Runs in background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = startOrResumeDownloads,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = if (hasPartialProgress) {
                        "Resume Model Download (${DownloadSessionState.formatBytesMb(remainingBytesToDownload)} left)"
                    } else {
                        "Download Selected Models (${configuredSettings.activeDiskFootprintMb} MB)"
                    }
                    Text(label)
                }
            } else {
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
                        val activeModel = sessionState.activeModel
                        val headerText = when {
                            sessionState.isRunning && activeModel != null -> {
                                val modelTitle = activeModel.id.replaceFirstChar { it.uppercase() }
                                "Downloading $modelTitle (${sessionState.currentModelIndex}/${sessionState.totalModelsInQueue})"
                            }
                            sessionState.isPaused -> "Download Paused (Progress Saved)"
                            sessionState.errorMessage != null -> "Connection Interrupted (Progress Saved)"
                            else -> "Preparing Model Download..."
                        }

                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (sessionState.currentFileName.isNotBlank()) {
                            Text(
                                text = "Current file: ${sessionState.currentFileName} (${sessionState.formattedModelBytes})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Overall progress bar
                        LinearProgressIndicator(
                            progress = { sessionState.overallProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )

                        // Speed, ETA, and Byte progress telemetry card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Speed,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (sessionState.isRunning) {
                                                sessionState.formattedSpeed
                                            } else if (sessionState.isPaused) {
                                                "Paused"
                                            } else {
                                                "Waiting"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    Text(
                                        text = if (sessionState.isRunning) {
                                            sessionState.formattedEta
                                        } else {
                                            "${(sessionState.overallProgress * 100f).roundToInt()}% Complete"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Total: ${sessionState.formattedOverallBytes}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "${(sessionState.overallProgress * 100f).roundToInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        Text(
                            text = sessionState.statusNote
                                ?: "Downloads continue in the background with a status notification even if you switch apps or lock your screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Pause / Resume / Customize Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (sessionState.isRunning) {
                                OutlinedButton(
                                    onClick = { ModelDownloadManager.pauseDownloads() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pause Download")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { step = OnboardingStep.CUSTOMIZE },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Customize")
                                }
                                Button(
                                    onClick = startOrResumeDownloads,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Resume Download")
                                }
                            }
                        }

                        if (sessionState.errorMessage != null) {
                            Text(
                                text = sessionState.errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        HorizontalDivider()

                        selectedModels.forEach { model ->
                            val state = sessionState.modelStatuses[model] ?: ModelQueueStatus.QUEUED
                            val modelTotal = ModelDownloadManager.modelTotalBytes(model)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${model.displayName} (${DownloadSessionState.formatBytesMb(modelTotal)})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    val statusLabel = when (state) {
                                        ModelQueueStatus.DONE -> "Done (SHA-256 Verified)"
                                        ModelQueueStatus.VERIFYING -> "Verifying SHA-256 integrity..."
                                        ModelQueueStatus.DOWNLOADING ->
                                            "Downloading (${(sessionState.modelProgress * 100).roundToInt()}% • ${sessionState.formattedModelBytes})"
                                        ModelQueueStatus.PAUSED -> "Paused (Resumable)"
                                        ModelQueueStatus.QUEUED -> "Queued"
                                        ModelQueueStatus.ERROR -> "Interrupted — tap Resume to continue"
                                    }
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state == ModelQueueStatus.ERROR) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }

                                when (state) {
                                    ModelQueueStatus.DONE -> Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Done",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    ModelQueueStatus.DOWNLOADING, ModelQueueStatus.VERIFYING -> CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    ModelQueueStatus.PAUSED, ModelQueueStatus.QUEUED -> Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = "Queued",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    ModelQueueStatus.ERROR -> Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = "Error",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Powered by Desert Ant Labs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun OnboardingModelRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    isLocked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
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
    }
}
