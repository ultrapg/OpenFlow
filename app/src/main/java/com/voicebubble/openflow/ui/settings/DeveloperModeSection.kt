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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicebubble.openflow.core.debug.DebugLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class LogViewFilter(val label: String) {
    ALL("All Logs (Crash + Debug)"),
    CRASH_ONLY("Crash & OOM Only"),
    DEBUG_ONLY("Debug & Memory Only")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeveloperModeSection(
    onHideDeveloperMode: () -> Unit
) {
    val context = LocalContext.current
    val isLoggingEnabled by DebugLogManager.isLoggingEnabled.collectAsState()
    val logUpdateTick by DebugLogManager.logUpdateTick.collectAsState()

    var manualRefreshTick by remember { mutableIntStateOf(0) }
    var selectedFilter by remember { mutableStateOf(LogViewFilter.ALL) }
    var liveMemoryText by remember { mutableStateOf(DebugLogManager.formatMemorySummary(context)) }
    var logSizeSummary by remember { mutableStateOf(DebugLogManager.getLogFilesSizeSummary(context)) }
    var fullReportText by remember { mutableStateOf("Loading diagnostic logs...") }

    // Periodically refresh live memory stats while Developer Mode card is visible
    LaunchedEffect(Unit) {
        while (true) {
            liveMemoryText = DebugLogManager.formatMemorySummary(context)
            delay(2_000L)
        }
    }

    LaunchedEffect(logUpdateTick, manualRefreshTick, selectedFilter) {
        withContext(Dispatchers.IO) {
            DebugLogManager.inspectPreviousProcessExitReasons(context)
            val combined = DebugLogManager.readCombinedLogs(context)
            val sizeInfo = DebugLogManager.getLogFilesSizeSummary(context)
            val memInfo = DebugLogManager.formatMemorySummary(context)
            withContext(Dispatchers.Main) {
                fullReportText = combined
                logSizeSummary = sizeInfo
                liveMemoryText = memInfo
            }
        }
    }

    val displayedLogText = remember(fullReportText, selectedFilter) {
        when (selectedFilter) {
            LogViewFilter.ALL -> fullReportText
            LogViewFilter.CRASH_ONLY -> {
                val crashStart = fullReportText.indexOf("--- CRASH & OS KILL LOG")
                val debugStart = fullReportText.indexOf("--- DEBUG & MEMORY TELEMETRY LOG")
                if (crashStart in 0 until debugStart) {
                    fullReportText.substring(0, debugStart).trimEnd()
                } else {
                    fullReportText
                }
            }
            LogViewFilter.DEBUG_ONLY -> {
                val debugStart = fullReportText.indexOf("--- DEBUG & MEMORY TELEMETRY LOG")
                if (debugStart >= 0) {
                    fullReportText.substring(debugStart).trimEnd()
                } else {
                    fullReportText
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Developer Mode • Crash & Debug Logs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tracks OOM kills, native LiteRT/OpenCL exits, & stage RAM telemetry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Live Memory Snapshot Box
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Live Memory & Heap Status",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = liveMemoryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Saved Log Footprint: $logSizeSummary",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Persistent Debug Log Writer Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Write Debug & Memory Telemetry to Disk",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Flushes model loads, inference stages, and heap usage to debug_log.txt before OOM kills can occur",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isLoggingEnabled,
                    onCheckedChange = { enabled ->
                        DebugLogManager.setLoggingEnabled(context, enabled)
                        manualRefreshTick++
                    }
                )
            }

            HorizontalDivider()

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogViewFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) }
                    )
                }
            }

            // Monospace Log Viewer Box
            val verticalLogScroll = rememberScrollState()
            val horizontalLogScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp)
                    .verticalScroll(verticalLogScroll)
                    .horizontalScroll(horizontalLogScroll)
            ) {
                SelectionContainer {
                    Text(
                        text = displayedLogText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Action Buttons: Copy, Share, Snapshot, Clear, Hide
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlow Logs", displayedLogText))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Logs")
                }

                OutlinedButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "OpenFlow Crash & Diagnostic Logs")
                            putExtra(Intent.EXTRA_TEXT, displayedLogText)
                        }
                        try {
                            context.startActivity(Intent.createChooser(sendIntent, "Share OpenFlow Logs"))
                        } catch (_: Throwable) {
                            Toast.makeText(context, "No app available to share logs", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }

                OutlinedButton(
                    onClick = {
                        DebugLogManager.logMemorySnapshot("Manual Snapshot")
                        manualRefreshTick++
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Log RAM Now")
                }

                OutlinedButton(
                    onClick = {
                        DebugLogManager.clearLogs(context)
                        manualRefreshTick++
                        Toast.makeText(context, "Crash & debug logs cleared", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Logs")
                }

                OutlinedButton(
                    onClick = {
                        DebugLogManager.setUnlocked(context, false)
                        onHideDeveloperMode()
                        Toast.makeText(context, "Developer Mode hidden", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Hide Dev Mode")
                }
            }
        }
    }
}
