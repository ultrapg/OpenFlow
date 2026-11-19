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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.speech.GeminiNanoAvailability
import com.voicebubble.openflow.core.speech.GeminiNanoBenchmarkResult
import com.voicebubble.openflow.core.speech.GeminiNanoCatalogState
import com.voicebubble.openflow.core.speech.GeminiNanoManager
import com.voicebubble.openflow.core.speech.GeminiNanoTextProcessor
import com.voicebubble.openflow.core.speech.GeminiNanoVariantStatus
import com.voicebubble.openflow.settings.DownloadSessionState
import com.voicebubble.openflow.settings.GeminiNanoVariant
import com.voicebubble.openflow.settings.LlmBenchmarkSample
import com.voicebubble.openflow.settings.LlmProviderMode
import com.voicebubble.openflow.settings.LocalLlmBackend
import com.voicebubble.openflow.settings.ModelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Status pill + collapsible Android AICore Gemini Nano (`FULL` & `FAST`) configuration panel.
 * Excluded from compilation via `app/build.gradle.kts` (`enableGeminiNano = false`) while kept
 * intact in the repository for future completion.
 */
@Composable
internal fun GeminiNanoStatusChip(
    variant: GeminiNanoVariant,
    isActive: Boolean,
    status: GeminiNanoVariantStatus
) {
    val (bgColor, textColor, dotColor, stateLabel) = when {
        status.isDownloading -> listOf(
            Color(0xFFE65100).copy(alpha = 0.16f),
            Color(0xFFEF6C00),
            Color(0xFFEF6C00),
            "Downloading..."
        )
        isActive && status.isAvailable -> listOf(
            Color(0xFF1B5E20).copy(alpha = 0.16f),
            Color(0xFF2E7D32),
            Color(0xFF2E7D32),
            "AICore Ready"
        )
        isActive -> listOf(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            MaterialTheme.colorScheme.primary,
            status.availability.label
        )
        status.isAvailable -> listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            Color(0xFF2E7D32),
            "Available"
        )
        status.isDownloadable -> listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.primary,
            "Downloadable"
        )
        else -> listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            status.availability.label
        )
    }
    val bg = bgColor as Color
    val txt = textColor as Color
    val dot = dotColor as Color
    val label = stateLabel as String

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "${variant.shortLabel}: $label",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = txt
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GeminiNanoCollapsibleSection(
    settings: ModelSettings,
    nanoCatalog: GeminiNanoCatalogState,
    isBusy: Boolean,
    onPersistSettings: (ModelSettings) -> Unit
) {
    val scope = rememberCoroutineScope()
    val localView = LocalView.current
    val isUsingNanoBackend = settings.localLlmBackend == LocalLlmBackend.GEMINI_NANO
    var expanded by rememberSaveable { mutableStateOf(isUsingNanoBackend) }
    var isTestingNano by remember { mutableStateOf(false) }
    var quickTestResult by remember { mutableStateOf<GeminiNanoBenchmarkResult?>(null) }
    var quickTestError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(expanded) {
        if (expanded) {
            GeminiNanoManager.refreshStatuses(force = false)
        }
    }

    DisposableEffect(isTestingNano) {
        val prevKeepOn = localView.keepScreenOn
        if (isTestingNano) {
            localView.keepScreenOn = true
        }
        onDispose {
            localView.keepScreenOn = prevKeepOn
        }
    }

    val selectedVariantStatus = nanoCatalog.statusOf(settings.geminiNanoVariant)
    val badgeText = when {
        nanoCatalog.anyDownloading -> "Downloading…"
        isUsingNanoBackend && settings.gemmaEnabled -> "Active · ${settings.geminiNanoVariant.shortLabel}"
        isUsingNanoBackend -> "Selected · ${settings.geminiNanoVariant.shortLabel}"
        nanoCatalog.isRefreshing -> "Checking…"
        nanoCatalog.anyAvailable -> "Available"
        nanoCatalog.anyDownloadable -> "Downloadable"
        else -> "Unavailable"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (isUsingNanoBackend && settings.gemmaEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Gemini Nano (System AICore)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "On-device Google AI · 0 MB app storage · Full & Fast models",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when {
                    isUsingNanoBackend && settings.gemmaEnabled -> MaterialTheme.colorScheme.primaryContainer
                    nanoCatalog.anyAvailable -> MaterialTheme.colorScheme.secondaryContainer
                    nanoCatalog.anyDownloadable -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surface
                }
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        isUsingNanoBackend && settings.gemmaEnabled -> MaterialTheme.colorScheme.onPrimaryContainer
                        nanoCatalog.anyAvailable -> MaterialTheme.colorScheme.onSecondaryContainer
                        nanoCatalog.anyDownloadable -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse Gemini Nano" else "Expand Gemini Nano",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Managed by Android AICore (no weights stored inside OpenFlow). Probes both Full & Fast across Stable & Preview tracks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    OutlinedButton(
                        enabled = !nanoCatalog.isRefreshing,
                        onClick = { GeminiNanoManager.refreshStatusesAsync(force = true) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        if (nanoCatalog.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh AICore status",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Check", maxLines = 1)
                    }
                }

                GeminiNanoVariant.entries.forEach { variant ->
                    val status = nanoCatalog.statusOf(variant)
                    val isVariantSelected = isUsingNanoBackend && settings.geminiNanoVariant == variant
                    GeminiNanoVariantCard(
                        variant = variant,
                        status = status,
                        selected = isVariantSelected,
                        onSelect = {
                            if (status.isAvailable || isVariantSelected) {
                                onPersistSettings(
                                    settings.copy(
                                        llmProvider = LlmProviderMode.LOCAL,
                                        localLlmBackend = LocalLlmBackend.GEMINI_NANO,
                                        geminiNanoVariant = variant,
                                        gemmaEnabled = if (status.isAvailable) true else settings.gemmaEnabled
                                    )
                                )
                            } else if (status.isDownloadable) {
                                onPersistSettings(
                                    settings.copy(
                                        llmProvider = LlmProviderMode.LOCAL,
                                        localLlmBackend = LocalLlmBackend.GEMINI_NANO,
                                        geminiNanoVariant = variant
                                    )
                                )
                            }
                        },
                        onTriggerDownload = {
                            onPersistSettings(
                                settings.copy(
                                    llmProvider = LlmProviderMode.LOCAL,
                                    localLlmBackend = LocalLlmBackend.GEMINI_NANO,
                                    geminiNanoVariant = variant
                                )
                            )
                            GeminiNanoManager.startDownload(variant) { completed ->
                                if (completed) {
                                    scope.launch(Dispatchers.Main) {
                                        val latest = ModelSettings.load(localView.context)
                                        onPersistSettings(
                                            latest.copy(
                                                llmProvider = LlmProviderMode.LOCAL,
                                                localLlmBackend = LocalLlmBackend.GEMINI_NANO,
                                                geminiNanoVariant = variant,
                                                gemmaEnabled = true
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onCancelDownload = {
                            GeminiNanoManager.cancelDownload(variant)
                        }
                    )
                }

                SettingToggleRow(
                    title = "Auto-Fallback if Overlay/Background Blocked",
                    subtitle = "If Android AICore blocks background/overlay calls over other apps (BACKGROUND_USE_BLOCKED), falls back cleanly to downloaded Gemma 4 E2B or Punct.",
                    checked = settings.geminiNanoAutoFallback,
                    onCheckedChange = { fallback ->
                        onPersistSettings(settings.copy(geminiNanoAutoFallback = fallback))
                    }
                )

                val diagnosticText = nanoCatalog.lastRuntimeWarning ?: nanoCatalog.lastRuntimeSuccessNote
                if (!diagnosticText.isNullOrBlank()) {
                    val isWarning = !nanoCatalog.lastRuntimeWarning.isNullOrBlank()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isWarning) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isWarning) {
                                "AICore Note: $diagnosticText"
                            } else {
                                "Last AICore Run: $diagnosticText"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isWarning) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (selectedVariantStatus.isAvailable || nanoCatalog.anyAvailable) {
                    val testVariant = if (selectedVariantStatus.isAvailable) {
                        settings.geminiNanoVariant
                    } else {
                        GeminiNanoVariant.entries.first { nanoCatalog.statusOf(it).isAvailable }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            enabled = !isTestingNano && !isBusy,
                            onClick = {
                                quickTestError = null
                                isTestingNano = true
                                scope.launch {
                                    try {
                                        val res = GeminiNanoTextProcessor.runQuickBenchmark(
                                            inputText = LlmBenchmarkSample.EXAMPLE_1_CASUAL_FILLERS.sampleText,
                                            variant = testVariant,
                                            preset = settings.gemmaPromptPreset,
                                            customPrompt = settings.gemmaCustomPrompt,
                                            languageCode = settings.preferredLanguage,
                                            customVocabulary = settings.customVocabulary,
                                            disableThinking = settings.gemmaDisableThinking
                                        )
                                        quickTestResult = res
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (t: Throwable) {
                                        quickTestError = GeminiNanoManager.describeGenAiException(t)
                                    } finally {
                                        isTestingNano = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isTestingNano) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing ${testVariant.shortLabel}...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test ${testVariant.title} (Foreground)")
                            }
                        }
                    }

                    if (!quickTestError.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = quickTestError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    val testRes = quickTestResult
                    if (testRes != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${testRes.variant.title} (${testRes.stageLabel}${testRes.baseModelName?.let { " · $it" } ?: ""}) • ${testRes.latencyMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                SelectionContainer {
                                    Text(
                                        text = testRes.cleanedOutput,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
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
private fun GeminiNanoVariantCard(
    variant: GeminiNanoVariant,
    status: GeminiNanoVariantStatus,
    selected: Boolean,
    onSelect: () -> Unit,
    onTriggerDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val isInteractive = status.isAvailable || status.isDownloadable || selected
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
    val statusColor = when (status.availability) {
        GeminiNanoAvailability.AVAILABLE -> Color(0xFF2E7D32)
        GeminiNanoAvailability.DOWNLOADABLE -> MaterialTheme.colorScheme.primary
        GeminiNanoAvailability.DOWNLOADING -> Color(0xFFEF6C00)
        GeminiNanoAvailability.CHECKING, GeminiNanoAvailability.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        GeminiNanoAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
            .clickable(enabled = isInteractive) { onSelect() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    enabled = status.isAvailable || selected,
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
                            text = variant.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (variant == GeminiNanoVariant.FAST) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ) {
                            Text(
                                text = variant.badgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (variant == GeminiNanoVariant.FAST) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = variant.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val stageTag = status.stageLabel?.let { " · $it" } ?: ""
                        Text(
                            text = "${status.availability.label}$stageTag",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                    }
                    if (status.isAvailable) {
                        val caps = buildList {
                            status.baseModelName?.let { add(it) }
                            status.tokenLimit?.let { add("$it tok") }
                            if (status.supportsSystemPrompt) add("System Prompt")
                            if (status.supportsThinkingMode) add("Thinking")
                            if (status.supportsPrefixCaching) add("Prefix Cache")
                        }
                        if (caps.isNotEmpty()) {
                            Text(
                                text = caps.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (status.isDownloadable) {
                Button(
                    onClick = onTriggerDownload,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download ${variant.title} via AICore")
                }
            } else if (status.isDownloading) {
                if (status.bytesToDownload > 0L) {
                    LinearProgressIndicator(
                        progress = { status.progressFraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val progressText = when {
                        status.bytesToDownload > 0L ->
                            "${(status.progressFraction * 100).roundToInt()}% (${DownloadSessionState.formatBytesMb(status.bytesDownloaded)} / ${DownloadSessionState.formatBytesMb(status.bytesToDownload)})"
                        status.bytesDownloaded > 0L ->
                            "Downloaded ${DownloadSessionState.formatBytesMb(status.bytesDownloaded)}..."
                        else -> "Requesting AICore model download..."
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onCancelDownload,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Cancel", maxLines = 1)
                    }
                }
            }

            if (!status.errorMessage.isNullOrBlank()) {
                Text(
                    text = status.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
