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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.material3.FilterChip
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
/* GEMINI_NANO_DISABLED
import com.voicebubble.openflow.core.speech.GeminiNanoAvailability
import com.voicebubble.openflow.core.speech.GeminiNanoManager
*/
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.settings.DesertAntModel
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.DownloadSessionState
import com.voicebubble.openflow.settings.LlmModelChoice
import com.voicebubble.openflow.settings.LlmProviderMode
/* GEMINI_NANO_DISABLED
import com.voicebubble.openflow.settings.LocalLlmBackend
*/
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    initialSettings: ModelSettings,
    onOnboardingComplete: (ModelSettings) -> Unit
) {
    val context = LocalContext.current
    val sessionState by ModelDownloadManager.sessionState.collectAsState()

    var remoteVozSelected by remember { mutableStateOf(initialSettings.remoteVozEnabled) }
    var earSelected by remember { mutableStateOf(initialSettings.earEnabled) }
    var remoteEarSelected by remember { mutableStateOf(initialSettings.remoteEarEnabled) }
    var punctSelected by remember { mutableStateOf(initialSettings.punctEnabled) }
    var gemmaSelected by remember { mutableStateOf(initialSettings.gemmaEnabled) }
    var llmProvider by remember { mutableStateOf(initialSettings.llmProvider) }
    /* GEMINI_NANO_DISABLED
    var localLlmBackend by remember { mutableStateOf(initialSettings.localLlmBackend) }
    val nanoCatalog by GeminiNanoManager.catalogState.collectAsState()

    LaunchedEffect(Unit) {
        GeminiNanoManager.refreshStatusesAsync(force = false)
    }
    */

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
        remoteVozEnabled = remoteVozSelected,
        earEnabled = earSelected,
        remoteEarEnabled = earSelected && remoteEarSelected,
        punctEnabled = punctSelected,
        gemmaEnabled = gemmaSelected,
        llmProvider = llmProvider
        /* GEMINI_NANO_DISABLED
        , localLlmBackend = localLlmBackend
        */
    )

    val modelsToDownload = configuredSettings.plannedLocalModels()
    val plannedDiskMb = modelsToDownload.sumOf { it.sizeMb }
    val plannedPeakRamMb = configuredSettings.plannedPeakRamMb()

    val totalSelectedBytes = modelsToDownload.sumOf { ModelDownloadManager.modelTotalBytes(it) }
    var cachedOrPartialBytes by remember { mutableLongStateOf(0L) }

    val completeOnboarding: (ModelSettings) -> Unit = { finalSettings ->
        ModelSettings.save(context, finalSettings)
        ModelSettings.setOnboardingCompleted(context, true)
        SmartPipeline.getInstance(context).reloadSettingsAsync(finalSettings)
        onOnboardingComplete(finalSettings)
    }

    LaunchedEffect(modelsToDownload, sessionState.overallDownloadedBytes, step) {
        cachedOrPartialBytes = withContext(Dispatchers.IO) {
            modelsToDownload.sumOf { ModelDownloadManager.getCachedOrPartialBytes(context, it) }
        }
    }

    LaunchedEffect(sessionState.isCompleted, modelsToDownload) {
        if (sessionState.isCompleted) {
            val allReady = withContext(Dispatchers.IO) {
                modelsToDownload.all { DesertAntModelStorage.isModelDownloaded(context, it) }
            }
            if (allReady) {
                completeOnboarding(configuredSettings)
            }
        }
    }

    val remainingBytesToDownload = max(0L, totalSelectedBytes - cachedOrPartialBytes)
    val hasPartialProgress = cachedOrPartialBytes > 0L && remainingBytesToDownload > 0L

    val launchDownloadQueue: () -> Unit = {
        if (modelsToDownload.isEmpty()) {
            completeOnboarding(configuredSettings)
        } else {
            step = OnboardingStep.DOWNLOADING
            ModelSettings.save(context, configuredSettings)
            ModelDownloadManager.startQueue(
                context = context,
                models = modelsToDownload,
                onAllCompleted = {
                    completeOnboarding(configuredSettings)
                }
            )
        }
    }

    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        launchDownloadQueue()
    }

    val startOrResumeDownloads: () -> Unit = {
        if (modelsToDownload.isEmpty()) {
            completeOnboarding(configuredSettings)
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
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
                text = "Run offline on your device using Desert Ant Labs LiteRT models, connect OpenAI-compatible remote/cloud APIs with zero downloads, or mix both.",
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
                            text = "Setup Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = !remoteVozSelected && llmProvider == LlmProviderMode.LOCAL,
                                onClick = {
                                    remoteVozSelected = false
                                    remoteEarSelected = false
                                    llmProvider = LlmProviderMode.LOCAL
                                },
                                label = { Text("On-Device Offline") }
                            )
                            FilterChip(
                                selected = remoteVozSelected && llmProvider == LlmProviderMode.OPENAI_API,
                                onClick = {
                                    remoteVozSelected = true
                                    if (earSelected) remoteEarSelected = true
                                    punctSelected = false
                                    gemmaSelected = true
                                    llmProvider = LlmProviderMode.OPENAI_API
                                },
                                label = { Text("Remote / Cloud APIs (0 MB)") }
                            )
                        }

                        HorizontalDivider()

                        Text(
                            text = "Configure Speech & AI Pipeline",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Voz (Always enabled; Local or Remote API)
                        OnboardingModelRow(
                            title = if (remoteVozSelected) {
                                "Voz — Speech Recognition (Remote API · 0 MB)"
                            } else {
                                "Voz — Speech Recognition (${DesertAntModel.VOZ.sizeMb} MB, Required)"
                            },
                            subtitle = if (remoteVozSelected) {
                                "Uses a remote OpenAI-compatible /audio/transcriptions server configured in Settings."
                            } else {
                                DesertAntModel.VOZ.roleSummary
                            },
                            checked = true,
                            enabled = false,
                            isLocked = !remoteVozSelected,
                            onCheckedChange = {}
                        )
                        OnboardingModeChipRow(
                            localLabel = "Local (${DesertAntModel.VOZ.sizeMb} MB)",
                            remoteLabel = "Remote API (0 MB)",
                            isRemote = remoteVozSelected,
                            onSelectRemote = { remoteVozSelected = it }
                        )

                        HorizontalDivider()

                        // Ear
                        OnboardingModelRow(
                            title = when {
                                !earSelected -> "Ear — Auto Language Detection (${DesertAntModel.EAR.sizeMb} MB, Optional)"
                                remoteEarSelected -> "Ear — Auto Language Detection (Remote API · 0 MB)"
                                else -> "Ear — Auto Language Detection (${DesertAntModel.EAR.sizeMb} MB)"
                            },
                            subtitle = DesertAntModel.EAR.roleSummary,
                            checked = earSelected,
                            enabled = true,
                            isLocked = false,
                            onCheckedChange = { earSelected = it }
                        )
                        if (earSelected) {
                            OnboardingModeChipRow(
                                localLabel = "Local (${DesertAntModel.EAR.sizeMb} MB)",
                                remoteLabel = "Remote API (0 MB)",
                                isRemote = remoteEarSelected,
                                onSelectRemote = { remoteEarSelected = it }
                            )
                        }

                        HorizontalDivider()

                        // Punct (Off by default)
                        OnboardingModelRow(
                            title = "Punct — Text & Punctuation Cleanup (${DesertAntModel.PUNCT.sizeMb} MB, Optional)",
                            subtitle = if (gemmaSelected) {
                                "Automatically bypassed while AI Post-Processing is enabled."
                            } else {
                                DesertAntModel.PUNCT.roleSummary
                            },
                            checked = punctSelected,
                            enabled = true,
                            isLocked = false,
                            onCheckedChange = { punctSelected = it }
                        )

                        HorizontalDivider()

                        // AI Post-Processing (Gemma 4 E2B or OpenAI-Compatible API)
                        val onboardingLlm = configuredSettings.selectedLlmModel
                        /* GEMINI_NANO_DISABLED
                        val nanoStatus = nanoCatalog.statusOf(configuredSettings.geminiNanoVariant)
                        val nanoAnyReadyOrDownloadable = nanoCatalog.anyAvailable || nanoCatalog.anyDownloadable
                        */
                        OnboardingModelRow(
                            title = when {
                                gemmaSelected && llmProvider == LlmProviderMode.OPENAI_API ->
                                    "AI Post-Processing (OpenAI-Compatible API · 0 MB)"
                                /* GEMINI_NANO_DISABLED
                                gemmaSelected && localLlmBackend == LocalLlmBackend.GEMINI_NANO ->
                                    "Gemini Nano — System AI Post-Processing (0 MB)"
                                */
                                else ->
                                    "${onboardingLlm.title} — AI Post-Processing (${onboardingLlm.desertAntModel.sizeMb} MB, Optional)"
                            },
                            subtitle = when {
                                gemmaSelected && llmProvider == LlmProviderMode.OPENAI_API ->
                                    "Cleans dictation using your own cloud or LAN OpenAI-compatible API endpoint."
                                /* GEMINI_NANO_DISABLED
                                gemmaSelected && localLlmBackend == LocalLlmBackend.GEMINI_NANO ->
                                    when (nanoStatus.availability) {
                                        GeminiNanoAvailability.AVAILABLE ->
                                            "Ready on Android AICore · zero app model download."
                                        GeminiNanoAvailability.DOWNLOADABLE ->
                                            "Supported via Android AICore · can be downloaded in Settings."
                                        else ->
                                            "Uses Android system AICore when available (configure Full / Fast in Settings)."
                                    }
                                */
                                else ->
                                    onboardingLlm.description
                            },
                            checked = gemmaSelected,
                            enabled = true,
                            isLocked = false,
                            onCheckedChange = { gemmaSelected = it }
                        )
                        if (gemmaSelected) {
                            OnboardingModeChipRow(
                                localLabel = "${onboardingLlm.title} (${onboardingLlm.desertAntModel.sizeMb} MB)",
                                remoteLabel = "OpenAI API (0 MB)",
                                isRemote = llmProvider == LlmProviderMode.OPENAI_API,
                                onSelectRemote = { useRemote ->
                                    llmProvider = if (useRemote) LlmProviderMode.OPENAI_API else LlmProviderMode.LOCAL
                                }
                            )
                            /* GEMINI_NANO_DISABLED
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = llmProvider == LlmProviderMode.LOCAL && localLlmBackend == LocalLlmBackend.LITERT_LM,
                                    onClick = {
                                        llmProvider = LlmProviderMode.LOCAL
                                        localLlmBackend = LocalLlmBackend.LITERT_LM
                                    },
                                    label = { Text("Gemma 4 E2B (${onboardingLlm.desertAntModel.sizeMb} MB)", maxLines = 1, softWrap = false) }
                                )
                                if (nanoAnyReadyOrDownloadable || localLlmBackend == LocalLlmBackend.GEMINI_NANO) {
                                    FilterChip(
                                        selected = llmProvider == LlmProviderMode.LOCAL && localLlmBackend == LocalLlmBackend.GEMINI_NANO,
                                        onClick = {
                                            llmProvider = LlmProviderMode.LOCAL
                                            localLlmBackend = LocalLlmBackend.GEMINI_NANO
                                        },
                                        label = { Text("Gemini Nano (0 MB)", maxLines = 1, softWrap = false) }
                                    )
                                }
                                FilterChip(
                                    selected = llmProvider == LlmProviderMode.OPENAI_API,
                                    onClick = { llmProvider = LlmProviderMode.OPENAI_API },
                                    label = { Text("OpenAI API (0 MB)", maxLines = 1, softWrap = false) }
                                )
                            }
                            */
                        }
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
                                text = when {
                                    modelsToDownload.isEmpty() -> "No Local Downloads Needed (0 MB)"
                                    hasPartialProgress ->
                                        "Remaining Download: ${DownloadSessionState.formatBytesMb(remainingBytesToDownload)}"
                                    else -> "Total Download: $plannedDiskMb MB"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (modelsToDownload.isEmpty()) {
                                Text(
                                    text = "Configure your API endpoints and keys next in Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                if (hasPartialProgress) {
                                    Text(
                                        text = "${DownloadSessionState.formatBytesMb(cachedOrPartialBytes)} already saved on device (resumable)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Text(
                                    text = "Est. Local Peak RAM: ~$plannedPeakRamMb MB • Runs in background",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Icon(
                            imageVector = if (modelsToDownload.isEmpty()) Icons.Default.Cloud else Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = startOrResumeDownloads,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val label = when {
                        modelsToDownload.isEmpty() -> "Continue to OpenFlow Settings (0 MB)"
                        hasPartialProgress ->
                            "Resume Model Download (${DownloadSessionState.formatBytesMb(remainingBytesToDownload)} left)"
                        else -> "Download Selected Models ($plannedDiskMb MB)"
                    }
                    Text(label)
                }

                if (modelsToDownload.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            val apiSettings = configuredSettings.copy(
                                remoteVozEnabled = true,
                                earEnabled = earSelected,
                                remoteEarEnabled = earSelected,
                                punctEnabled = false,
                                gemmaEnabled = true,
                                llmProvider = LlmProviderMode.OPENAI_API
                            )
                            completeOnboarding(apiSettings)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip Downloads & Use Remote APIs")
                    }
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
                                "Downloading ${activeModel.shortLabel} (${sessionState.currentModelIndex}/${sessionState.totalModelsInQueue})"
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

                        modelsToDownload.forEach { model ->
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingModeChipRow(
    localLabel: String,
    remoteLabel: String,
    isRemote: Boolean,
    onSelectRemote: (Boolean) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = !isRemote,
            onClick = { onSelectRemote(false) },
            label = { Text(localLabel, maxLines = 1, softWrap = false) }
        )
        FilterChip(
            selected = isRemote,
            onClick = { onSelectRemote(true) },
            label = { Text(remoteLabel, maxLines = 1, softWrap = false) }
        )
    }
}
