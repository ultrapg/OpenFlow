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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.speech.OpenAiCompatibleClient
import com.voicebubble.openflow.core.speech.OpenAiConnectionTestResult
import com.voicebubble.openflow.settings.LlmThinkingEffort
import com.voicebubble.openflow.settings.ModelSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reusable clickable switch row shared across Audio and AI post-processing settings cards.
 */
@Composable
internal fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
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
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Reusable chip selector for LLM reasoning depth / thinking effort.
 */
/**
 * Dropdown menu selector for [LlmThinkingEffort] with optional inline custom string entry
 * when [allowCustom] is enabled and [LlmThinkingEffort.CUSTOM] is selected.
 */
@Composable
internal fun ThinkingEffortDropdown(
    title: String,
    subtitle: String,
    options: List<LlmThinkingEffort>,
    selected: LlmThinkingEffort,
    onSelect: (LlmThinkingEffort) -> Unit,
    allowCustom: Boolean = false,
    customValue: String = "",
    onCustomValueChange: (String) -> Unit = {},
    showApiBadges: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var draftCustomValue by rememberSaveable { mutableStateOf(customValue) }
    LaunchedEffect(customValue) {
        if (customValue != draftCustomValue.trim()) {
            draftCustomValue = customValue
        }
    }

    LaunchedEffect(allowCustom, selected, draftCustomValue) {
        if (!allowCustom || selected != LlmThinkingEffort.CUSTOM) return@LaunchedEffect
        val trimmedDraft = draftCustomValue.trim()
        if (trimmedDraft == customValue.trim()) return@LaunchedEffect
        delay(200)
        onCustomValueChange(trimmedDraft)
    }

    val effectiveBadge = when {
        !showApiBadges -> null
        selected == LlmThinkingEffort.CUSTOM ->
            draftCustomValue.trim().takeIf { it.isNotEmpty() }?.let { "reasoning_effort: $it" } ?: "auto (blank)"
        selected.apiValue != null -> "reasoning_effort: ${selected.apiValue}"
        else -> "server default"
    }
    val selectedTitle = if (selected == LlmThinkingEffort.CUSTOM && draftCustomValue.trim().isNotEmpty()) {
        "Custom (${draftCustomValue.trim()})"
    } else {
        selected.title
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = selectedTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (effectiveBadge != null) {
                                val isHeavy = selected == LlmThinkingEffort.MAX || selected == LlmThinkingEffort.XHIGH
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isHeavy) {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    }
                                ) {
                                    Text(
                                        text = effectiveBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isHeavy) {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = selected.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Choose $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.86f)
            ) {
                options.forEach { effort ->
                    val isSelected = effort == selected
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = effort.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    val badgeText = when {
                                        !showApiBadges -> null
                                        effort == LlmThinkingEffort.CUSTOM -> "custom"
                                        effort.apiValue != null -> effort.apiValue
                                        else -> "auto"
                                    }
                                    if (badgeText != null) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            }
                                        ) {
                                            Text(
                                                text = badgeText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = effort.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingIcon = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(effort)
                        }
                    )
                }
            }
        }

        AnimatedVisibility(visible = allowCustom && selected == LlmThinkingEffort.CUSTOM) {
            val trimmedDraft = draftCustomValue.trim()
            OutlinedTextField(
                value = draftCustomValue,
                onValueChange = { next ->
                    draftCustomValue = next
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                label = { Text("Custom reasoning_effort Value") },
                placeholder = { Text("e.g. xhigh, minimal, disabled, 0, budget_2048") },
                supportingText = {
                    Text(
                        text = if (trimmedDraft.isEmpty()) {
                            "Leave blank for server default (omits reasoning_effort)."
                        } else {
                            "Sends \"reasoning_effort\": \"$trimmedDraft\" (falls back automatically if rejected)."
                        }
                    )
                },
                singleLine = true
            )
        }
    }
}

/**
 * Shared OpenAI-compatible endpoint editor (presets, URL, API key, model ID + Fetch dropdown,
 * timeout selector, and Test Connection banner) used for Chat Completions as well as Remote Voz / Ear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteEndpointFieldsBlock(
    baseUrl: String,
    apiKey: String,
    model: String,
    timeoutSeconds: Int,
    presets: List<OpenAiEndpointPresetCompat>,
    urlPlaceholder: String,
    modelPlaceholder: String,
    urlSupportingDefault: String,
    resolveEndpointPreview: (String) -> String,
    includeAudioModelsInFetch: Boolean,
    timeoutOptions: List<Int>,
    testButtonLabel: String = "Test Connection",
    testingButtonLabel: String = "Testing Connection...",
    showEndpointFields: Boolean = true,
    effectiveBaseUrlForActions: String = baseUrl,
    effectiveApiKeyForActions: String = apiKey,
    effectiveTimeoutForActions: Int = timeoutSeconds,
    onFieldsCommit: (baseUrl: String, apiKey: String, model: String) -> Unit,
    onTimeoutChange: (Int) -> Unit,
    onTestConnection: suspend (baseUrl: String, apiKey: String, model: String, timeoutSeconds: Int) -> OpenAiConnectionTestResult,
    footerTip: String? = null
) {
    val scope = rememberCoroutineScope()
    var urlDraft by rememberSaveable { mutableStateOf(baseUrl) }
    var apiKeyDraft by rememberSaveable { mutableStateOf(apiKey) }
    var modelDraft by rememberSaveable { mutableStateOf(model) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }

    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelMenu by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf<OpenAiConnectionTestResult?>(null) }

    LaunchedEffect(baseUrl) {
        if (urlDraft.trim() != baseUrl) urlDraft = baseUrl
    }
    LaunchedEffect(apiKey) {
        if (apiKeyDraft.trim() != apiKey) apiKeyDraft = apiKey
    }
    LaunchedEffect(model) {
        if (modelDraft.trim() != model) modelDraft = model
    }

    LaunchedEffect(urlDraft, apiKeyDraft, modelDraft) {
        val cleanUrl = urlDraft.trim()
        val cleanKey = apiKeyDraft.trim()
        val cleanModel = modelDraft.trim()
        if (cleanUrl == baseUrl && cleanKey == apiKey && cleanModel == model) {
            return@LaunchedEffect
        }
        delay(350)
        onFieldsCommit(cleanUrl, cleanKey, cleanModel)
    }

    val activeUrlForActions = if (showEndpointFields) urlDraft.trim() else effectiveBaseUrlForActions.trim()
    val activeKeyForActions = if (showEndpointFields) apiKeyDraft.trim() else effectiveApiKeyForActions.trim()
    val activeTimeoutForActions = if (showEndpointFields) timeoutSeconds else effectiveTimeoutForActions
    val urlValid = OpenAiCompatibleClient.isValidHttpUrl(activeUrlForActions)
    val ownUrlValid = OpenAiCompatibleClient.isValidHttpUrl(urlDraft)
    val modelReady = modelDraft.trim().isNotEmpty()
    val resolvedUrlPreview = remember(urlDraft) {
        if (urlDraft.isBlank()) "" else resolveEndpointPreview(urlDraft)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showEndpointFields) {
                Text(
                    text = "Quick Endpoint Presets",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { preset ->
                        val isSelectedPreset = urlDraft.trim().trimEnd('/').equals(
                            preset.baseUrl.trimEnd('/'),
                            ignoreCase = true
                        )
                        FilterChip(
                            selected = isSelectedPreset,
                            onClick = {
                                urlDraft = preset.baseUrl
                                modelDraft = preset.defaultModel
                                fetchedModels = emptyList()
                                fetchError = null
                                connectionResult = null
                                onFieldsCommit(preset.baseUrl, apiKeyDraft.trim(), preset.defaultModel)
                            },
                            label = { Text(preset.label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = {
                        urlDraft = it
                        fetchedModels = emptyList()
                        connectionResult = null
                    },
                    label = { Text("Base URL") },
                    placeholder = { Text(urlPlaceholder) },
                    isError = urlDraft.isNotBlank() && !ownUrlValid,
                    supportingText = {
                        Text(
                            text = when {
                                urlDraft.isBlank() -> urlSupportingDefault
                                !ownUrlValid -> "Enter a valid http:// or https:// URL."
                                else -> "Endpoint: $resolvedUrlPreview"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = {
                        apiKeyDraft = it
                        fetchedModels = emptyList()
                        connectionResult = null
                    },
                    label = { Text("API Key (optional for local servers)") },
                    placeholder = { Text("sk-...") },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (apiKeyVisible) "Hide API Key" else "Show API Key"
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = if (urlValid) {
                        "Sharing Remote Voz endpoint: ${resolveEndpointPreview(activeUrlForActions)}"
                    } else {
                        "Configure a valid Remote Voz URL above to share its endpoint."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (urlValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = {
                        modelDraft = it
                        connectionResult = null
                    },
                    label = { Text("Model ID") },
                    placeholder = { Text(modelPlaceholder) },
                    trailingIcon = if (fetchedModels.isNotEmpty()) {
                        {
                            IconButton(onClick = { showModelMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Choose fetched model"
                                )
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Box(modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(
                        enabled = urlValid && !isFetchingModels,
                        onClick = {
                            isFetchingModels = true
                            fetchError = null
                            onFieldsCommit(urlDraft.trim(), apiKeyDraft.trim(), modelDraft.trim())
                            scope.launch {
                                val res = OpenAiCompatibleClient.fetchAvailableModels(
                                    baseUrl = activeUrlForActions,
                                    apiKey = activeKeyForActions,
                                    timeoutSeconds = activeTimeoutForActions,
                                    includeAudioModels = includeAudioModelsInFetch
                                )
                                isFetchingModels = false
                                res.onSuccess { list ->
                                    fetchedModels = list
                                    if (modelDraft.isBlank() && list.isNotEmpty()) {
                                        modelDraft = list.first()
                                        onFieldsCommit(urlDraft.trim(), apiKeyDraft.trim(), modelDraft)
                                    }
                                    showModelMenu = true
                                }.onFailure { err ->
                                    fetchError = err.message ?: "Failed to fetch models"
                                }
                            }
                        }
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Fetch")
                        }
                    }

                    DropdownMenu(
                        expanded = showModelMenu && fetchedModels.isNotEmpty(),
                        onDismissRequest = { showModelMenu = false }
                    ) {
                        fetchedModels.take(40).forEach { candidateModel ->
                            DropdownMenuItem(
                                text = { Text(candidateModel) },
                                onClick = {
                                    modelDraft = candidateModel
                                    showModelMenu = false
                                    connectionResult = null
                                    onFieldsCommit(urlDraft.trim(), apiKeyDraft.trim(), candidateModel)
                                }
                            )
                        }
                    }
                }
            }

            if (!fetchError.isNullOrBlank()) {
                Text(
                    text = fetchError.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (showEndpointFields) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Timeout",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        timeoutOptions.forEach { seconds ->
                            FilterChip(
                                selected = timeoutSeconds == seconds,
                                onClick = { onTimeoutChange(seconds) },
                                label = {
                                    Text(
                                        text = "${seconds}s",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Button(
                enabled = urlValid && modelReady && !isTestingConnection,
                onClick = {
                    isTestingConnection = true
                    connectionResult = null
                    fetchError = null
                    onFieldsCommit(urlDraft.trim(), apiKeyDraft.trim(), modelDraft.trim())
                    scope.launch {
                        val result = onTestConnection(
                            activeUrlForActions,
                            activeKeyForActions,
                            modelDraft.trim(),
                            activeTimeoutForActions
                        )
                        connectionResult = result
                        isTestingConnection = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(testingButtonLabel)
                } else {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(testButtonLabel)
                }
            }

            connectionResult?.let { result ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (result.success) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.success) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                    }
                }
            }

            if (urlValid && modelReady && !footerTip.isNullOrBlank()) {
                Text(
                    text = footerTip,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Lightweight preset adapter so endpoint fields avoid tight coupling. */
internal data class OpenAiEndpointPresetCompat(
    val label: String,
    val baseUrl: String,
    val defaultModel: String
)

internal fun List<com.voicebubble.openflow.core.speech.OpenAiEndpointPreset>.asCompat(): List<OpenAiEndpointPresetCompat> {
    return map { OpenAiEndpointPresetCompat(it.label, it.baseUrl, it.defaultModel) }
}

/**
 * Collapsible section rendered at the bottom of the Desert Ant Audio Models selector card
 * allowing users to run `Voz` (STT) and/or `Ear` (language ID) remotely via OpenAI-compatible
 * `/audio/transcriptions` endpoints (either sharing one endpoint or on two separate endpoints).
 */
@Composable
internal fun RemoteAudioModelsCollapsibleSection(
    settings: ModelSettings,
    onPersistSettings: (ModelSettings) -> Unit
) {
    val anyRemoteActive = settings.isRemoteVozActive || settings.isRemoteEarActive
    val anySetupNeeded = !anyRemoteActive &&
        (settings.remoteVozEnabled || (settings.earEnabled && settings.remoteEarEnabled))
    var expanded by rememberSaveable { mutableStateOf(anySetupNeeded) }

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
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = if (anyRemoteActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Remote Voz & Ear (OpenAI API)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Host speech-to-text & language ID on one or two remote endpoints",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when {
                    anyRemoteActive -> MaterialTheme.colorScheme.primaryContainer
                    anySetupNeeded -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                }
            ) {
                Text(
                    text = settings.remoteAudioSummaryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        anyRemoteActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        anySetupNeeded -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingToggleRow(
                    title = "Remote Voz (Speech-to-Text API)",
                    subtitle = when {
                        settings.isRemoteVozActive ->
                            "Active · Sending 16 kHz WAV to '${settings.remoteVozModel.trim()}' (0 MB local RAM)"
                        settings.remoteVozEnabled ->
                            "Enter a valid endpoint URL & model ID below."
                        else ->
                            "Offload Voz transcription to a remote/LAN /audio/transcriptions server."
                    },
                    checked = settings.remoteVozEnabled,
                    onCheckedChange = { enabled ->
                        onPersistSettings(settings.copy(remoteVozEnabled = enabled))
                    }
                )

                if (settings.remoteVozEnabled) {
                    key("remote_voz_endpoint") {
                        RemoteEndpointFieldsBlock(
                            baseUrl = settings.remoteVozBaseUrl,
                            apiKey = settings.remoteVozApiKey,
                            model = settings.remoteVozModel,
                            timeoutSeconds = settings.remoteVozTimeoutSeconds,
                            presets = OpenAiCompatibleClient.AUDIO_PRESETS.asCompat(),
                            urlPlaceholder = "http://192.168.1.50:8000/v1",
                            modelPlaceholder = OpenAiCompatibleClient.DEFAULT_REMOTE_VOZ_MODEL,
                            urlSupportingDefault = "Accepts base URL, host:port, or /v1/audio/transcriptions.",
                            resolveEndpointPreview = OpenAiCompatibleClient::resolveAudioTranscriptionsUrl,
                            includeAudioModelsInFetch = true,
                            timeoutOptions = listOf(10, 20, 30, 60),
                            testButtonLabel = "Test Remote Voz",
                            testingButtonLabel = "Testing Remote Voz...",
                            onFieldsCommit = { url, key, model ->
                                onPersistSettings(
                                    settings.copy(
                                        remoteVozBaseUrl = url,
                                        remoteVozApiKey = key,
                                        remoteVozModel = model
                                    )
                                )
                            },
                            onTimeoutChange = { seconds ->
                                onPersistSettings(settings.copy(remoteVozTimeoutSeconds = seconds))
                            },
                            onTestConnection = { url, key, model, timeout ->
                                OpenAiCompatibleClient.testAudioConnection(
                                    baseUrl = url,
                                    apiKey = key,
                                    model = model,
                                    isEarMode = false,
                                    timeoutSeconds = timeout
                                )
                            },
                            footerTip = "Uploads 16 kHz PCM16 WAV segments directly to /audio/transcriptions with Custom Vocabulary hints."
                        )
                    }
                }

                HorizontalDivider()

                val remoteEarSwitchChecked = settings.remoteEarEnabled && settings.earEnabled
                SettingToggleRow(
                    title = "Remote Ear (Language ID API)",
                    subtitle = when {
                        settings.isRemoteEarActive ->
                            "Active · Detecting spoken language via '${settings.remoteEarModel.trim()}' (${if (settings.remoteEarUseSameEndpoint && settings.remoteVozEnabled) "same endpoint" else "separate endpoint"})"
                        remoteEarSwitchChecked ->
                            "Configure the Ear endpoint & model ID below."
                        else ->
                            "Run automatic language detection on an OpenAI-compatible server."
                    },
                    checked = remoteEarSwitchChecked,
                    onCheckedChange = { enabled ->
                        onPersistSettings(
                            settings.copy(
                                earEnabled = if (enabled) true else settings.earEnabled,
                                remoteEarEnabled = enabled
                            )
                        )
                    }
                )

                if (remoteEarSwitchChecked) {
                    if (settings.remoteVozEnabled) {
                        SettingToggleRow(
                            title = "Same endpoint & API key as Remote Voz",
                            subtitle = "On: reuses Remote Voz URL & API key. Off: use a separate server for Ear.",
                            checked = settings.remoteEarUseSameEndpoint,
                            onCheckedChange = { same ->
                                onPersistSettings(settings.copy(remoteEarUseSameEndpoint = same))
                            }
                        )
                    }

                    val shareWithVoz = settings.remoteVozEnabled && settings.remoteEarUseSameEndpoint
                    key("remote_ear_endpoint") {
                        RemoteEndpointFieldsBlock(
                            baseUrl = settings.remoteEarBaseUrl,
                            apiKey = settings.remoteEarApiKey,
                            model = settings.remoteEarModel,
                            timeoutSeconds = settings.remoteEarTimeoutSeconds,
                            presets = OpenAiCompatibleClient.AUDIO_PRESETS.asCompat(),
                            urlPlaceholder = "http://192.168.1.51:8000/v1",
                            modelPlaceholder = OpenAiCompatibleClient.DEFAULT_REMOTE_EAR_MODEL,
                            urlSupportingDefault = "Accepts base URL, host:port, or /v1/audio/transcriptions.",
                            resolveEndpointPreview = OpenAiCompatibleClient::resolveAudioTranscriptionsUrl,
                            includeAudioModelsInFetch = true,
                            timeoutOptions = listOf(10, 20, 30, 60),
                            testButtonLabel = "Test Remote Ear",
                            testingButtonLabel = "Testing Remote Ear...",
                            showEndpointFields = !shareWithVoz,
                            effectiveBaseUrlForActions = settings.effectiveRemoteEarBaseUrl,
                            effectiveApiKeyForActions = settings.effectiveRemoteEarApiKey,
                            effectiveTimeoutForActions = settings.effectiveRemoteEarTimeoutSeconds,
                            onFieldsCommit = { url, key, model ->
                                onPersistSettings(
                                    settings.copy(
                                        remoteEarBaseUrl = url,
                                        remoteEarApiKey = key,
                                        remoteEarModel = model
                                    )
                                )
                            },
                            onTimeoutChange = { seconds ->
                                onPersistSettings(settings.copy(remoteEarTimeoutSeconds = seconds))
                            },
                            onTestConnection = { url, key, model, timeout ->
                                OpenAiCompatibleClient.testAudioConnection(
                                    baseUrl = url,
                                    apiKey = key,
                                    model = model,
                                    isEarMode = true,
                                    timeoutSeconds = timeout
                                )
                            },
                            footerTip = "Detects language from the first <=5.5s speech slice, falling back to your Preferred Language on error."
                        )
                    }
                }
            }
        }
    }
}
