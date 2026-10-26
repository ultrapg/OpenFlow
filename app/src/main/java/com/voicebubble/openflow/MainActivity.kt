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

package com.voicebubble.openflow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*
import com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.overlay.FloatingBubbleService
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.ui.onboarding.OnboardingScreen
import com.voicebubble.openflow.ui.settings.DeveloperModeSection
import com.voicebubble.openflow.ui.settings.DictationChunkingSettingsSection
import com.voicebubble.openflow.ui.settings.SpeechRecognitionSettingsSection
import com.voicebubble.openflow.ui.theme.OpenFlowTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme }
                )
                onDispose {}
            }
            val context = LocalContext.current
            val prefs = remember { getSharedPreferences("openflow_settings", Context.MODE_PRIVATE) }

            var modelSettings by remember { mutableStateOf(ModelSettings.load(context)) }
            var isCheckingModels by remember { mutableStateOf(true) }
            var requiresOnboarding by remember { mutableStateOf(false) }

            val isBubbleServiceRunning by FloatingBubbleService.isRunning.collectAsState()
            val isAccessibilityConnected by TextInjectionAccessibilityService.isServiceConnected.collectAsState()

            LaunchedEffect(Unit) {
                val allReady = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DesertAntModelStorage.areAllEnabledModelsDownloaded(context, modelSettings)
                }
                requiresOnboarding = !allReady
                isCheckingModels = false
                if (allReady) {
                    SmartPipeline.getInstance(context).initializeAsync(warmUp = true)
                    FloatingBubbleService.ensureRunningIfEnabled(context, reason = "main_activity_launch")
                }
            }

            var showOnlyOnInputs by remember {
                mutableStateOf(prefs.getBoolean(FloatingBubbleService.KEY_SHOW_ONLY_ON_INPUTS, true))
            }
            var disappearDelayMs by remember {
                mutableLongStateOf(
                    prefs.getLong(
                        FloatingBubbleService.KEY_BUBBLE_DISAPPEAR_DELAY_MS,
                        FloatingBubbleService.DEFAULT_BUBBLE_DISAPPEAR_DELAY_MS
                    ).coerceIn(0L, 5_000L)
                )
            }
            var hapticFeedback by remember { mutableStateOf(prefs.getBoolean("haptic_feedback", true)) }
            var autoStartBubble by remember {
                mutableStateOf(FloatingBubbleService.isAutoStartEnabled(context))
            }
            var bubbleSize by remember { mutableFloatStateOf(prefs.getFloat("bubble_size", 52f)) }

            val startOrRefreshBubbleService = {
                FloatingBubbleService.setBubbleEnabledByUser(context, true)
                val intent = Intent(context, FloatingBubbleService::class.java).apply {
                    if (isBubbleServiceRunning) {
                        action = FloatingBubbleService.ACTION_REFRESH_SETTINGS
                    }
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(
                        context,
                        "Could not start bubble service: ${e.localizedMessage ?: "check permissions"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            OpenFlowTheme {
                if (isCheckingModels) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Initializing Desert Ant speech models...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                } else if (requiresOnboarding) {
                    OnboardingScreen(
                        initialSettings = modelSettings,
                        onOnboardingComplete = { completedSettings ->
                            modelSettings = completedSettings
                            requiresOnboarding = false
                            SmartPipeline.getInstance(context).reloadSettingsAsync(completedSettings)
                        }
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            val isDeveloperModeUnlocked by DebugLogManager.isUnlocked.collectAsState()
                            val appVersionName = remember {
                                try {
                                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2.7.2"
                                } catch (_: Throwable) {
                                    "2.7.2"
                                }
                            }
                            var versionTapCount by remember { mutableIntStateOf(0) }
                            var lastVersionTapMs by remember { mutableLongStateOf(0L) }
                            var activeToast by remember { mutableStateOf<android.widget.Toast?>(null) }

                            Text(
                                "OpenFlow Settings",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isDeveloperModeUnlocked) {
                                    "Version $appVersionName (Developer Mode)"
                                } else {
                                    "Version $appVersionName"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDeveloperModeUnlocked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastVersionTapMs > 1_200L) {
                                            versionTapCount = 1
                                        } else {
                                            versionTapCount++
                                        }
                                        lastVersionTapMs = now

                                        if (versionTapCount >= 5) {
                                            versionTapCount = 0
                                            activeToast?.cancel()
                                            if (!isDeveloperModeUnlocked) {
                                                DebugLogManager.setUnlocked(context, true)
                                                activeToast = android.widget.Toast.makeText(
                                                    context,
                                                    "Developer Mode & Crash/Debug Logger activated!",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).also { it.show() }
                                            } else {
                                                activeToast = android.widget.Toast.makeText(
                                                    context,
                                                    "Developer Mode is already active below.",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).also { it.show() }
                                            }
                                        } else if (!isDeveloperModeUnlocked && versionTapCount >= 3) {
                                            val remaining = 5 - versionTapCount
                                            activeToast?.cancel()
                                            activeToast = android.widget.Toast.makeText(
                                                context,
                                                "Tap $remaining more time${if (remaining == 1) "" else "s"} to unlock Developer Mode",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).also { it.show() }
                                        }
                                    }
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            val micPermissionState = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)

                            var isIgnoringBattery by remember { mutableStateOf(false) }
                            var canDrawOverlaysState by remember { mutableStateOf(false) }
                            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

                            DisposableEffect(lifecycleOwner) {
                                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                        isIgnoringBattery = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                            pm.isIgnoringBatteryOptimizations(context.packageName)
                                        canDrawOverlaysState = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                            Settings.canDrawOverlays(context)
                                        SmartPipeline.getInstance(context).refreshStatusSnapshot()
                                    }
                                }
                                lifecycleOwner.lifecycle.addObserver(observer)
                                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBattery) {
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        intent.data = Uri.parse("package:$packageName")
                                        try {
                                            startActivity(intent)
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Setting not found on this device.",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Disable Battery Optimization (Important)")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            } else {
                                Text("✓ Battery Optimization Disabled", color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (!micPermissionState.status.isGranted) {
                                Button(
                                    onClick = { micPermissionState.launchPermissionRequest() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Grant Microphone Permission")
                                }
                            } else {
                                Text("✓ Microphone Permission Granted", color = MaterialTheme.colorScheme.primary)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canDrawOverlaysState) {
                                Button(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                        try {
                                            startActivity(intent)
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                "Setting not found on this device.",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Grant Overlay Permission")
                                }
                            } else {
                                Text("✓ Overlay Permission Granted", color = MaterialTheme.colorScheme.primary)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (isAccessibilityConnected) {
                                Text(
                                    "✓ Accessibility Service Active (Direct Text Injection Ready)",
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    try {
                                        startActivity(intent)
                                    } catch (_: android.content.ActivityNotFoundException) {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Setting not found on this device.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (isAccessibilityConnected) {
                                        "Manage Accessibility Service (Connected)"
                                    } else {
                                        "Enable Accessibility Service (For Auto-Paste)"
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            SpeechRecognitionSettingsSection(
                                settings = modelSettings,
                                onSettingsChanged = { updated ->
                                    modelSettings = updated
                                }
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            DictationChunkingSettingsSection(
                                earEnabled = modelSettings.earEnabled
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                "Floating Bubble Settings",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isBubbleServiceRunning) {
                                    "● Floating Bubble Service is active in background"
                                } else {
                                    "○ Floating Bubble Service is stopped"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBubbleServiceRunning) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = showOnlyOnInputs,
                                    onCheckedChange = {
                                        showOnlyOnInputs = it
                                        prefs.edit()
                                            .putBoolean(FloatingBubbleService.KEY_SHOW_ONLY_ON_INPUTS, it)
                                            .putBoolean(FloatingBubbleService.LEGACY_KEY_SHOW_ONLY_ON_INPUTS, it)
                                            .apply()
                                        if (isBubbleServiceRunning) {
                                            startOrRefreshBubbleService()
                                        }
                                    }
                                )
                                Text(
                                    "Only show bubble when keyboard is on screen",
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            if (showOnlyOnInputs && !isAccessibilityConnected) {
                                Text(
                                    text = "Tip: Enable Accessibility Service above for instant keyboard detection across all apps and automatic text pasting.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                                )
                            }
                            if (showOnlyOnInputs) {
                                Spacer(modifier = Modifier.height(6.dp))
                                val delaySecondsFormatted = if (disappearDelayMs == 0L) {
                                    "Instant (0.0s)"
                                } else {
                                    String.format(java.util.Locale.US, "%.2fs (%d ms)", disappearDelayMs / 1000.0, disappearDelayMs)
                                }
                                Text(
                                    text = "Disappear Delay After Keyboard Closes: $delaySecondsFormatted",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                                Slider(
                                    value = disappearDelayMs.toFloat(),
                                    onValueChange = { raw ->
                                        val snapped = ((raw / 250f).toInt() * 250L).coerceIn(0L, 5_000L)
                                        disappearDelayMs = snapped
                                    },
                                    onValueChangeFinished = {
                                        prefs.edit()
                                            .putLong(FloatingBubbleService.KEY_BUBBLE_DISAPPEAR_DELAY_MS, disappearDelayMs)
                                            .apply()
                                        if (isBubbleServiceRunning) {
                                            startOrRefreshBubbleService()
                                        }
                                    },
                                    valueRange = 0f..5_000f,
                                    steps = 19,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = hapticFeedback,
                                    onCheckedChange = {
                                        hapticFeedback = it
                                        prefs.edit().putBoolean("haptic_feedback", it).apply()
                                        if (isBubbleServiceRunning) {
                                            startOrRefreshBubbleService()
                                        }
                                    }
                                )
                                Text(
                                    "Haptic feedback (vibration)",
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = autoStartBubble,
                                    onCheckedChange = {
                                        autoStartBubble = it
                                        FloatingBubbleService.setAutoStartEnabled(context, it)
                                    }
                                )
                                Text(
                                    "Auto-start on phone boot & auto-restart after crash",
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Bubble Size: ${bubbleSize.toInt()} dp",
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Slider(
                                    value = bubbleSize,
                                    onValueChange = { bubbleSize = it },
                                    onValueChangeFinished = {
                                        prefs.edit().putFloat("bubble_size", bubbleSize).apply()
                                        if (isBubbleServiceRunning) {
                                            startOrRefreshBubbleService()
                                        }
                                    },
                                    valueRange = 36f..80f,
                                    steps = 10,
                                    modifier = Modifier.weight(1f)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(bubbleSize.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(
                                            android.R.drawable.ic_btn_speak_now
                                        ),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size((bubbleSize * 0.5f).dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        FloatingBubbleService.setBubbleEnabledByUser(context, false)
                                        val stopIntent = Intent(context, FloatingBubbleService::class.java).apply {
                                            action = FloatingBubbleService.ACTION_EXPLICIT_STOP
                                        }
                                        try {
                                            context.startService(stopIntent)
                                        } catch (_: Throwable) {
                                        }
                                        context.stopService(Intent(context, FloatingBubbleService::class.java))
                                    },
                                    enabled = isBubbleServiceRunning,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Stop Bubble")
                                }

                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                            !Settings.canDrawOverlays(context)
                                        ) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Please grant Overlay Permission first!",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                            return@Button
                                        }
                                        if (!micPermissionState.status.isGranted) {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Please grant Microphone Permission first!",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                            return@Button
                                        }
                                        startOrRefreshBubbleService()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isBubbleServiceRunning) "Refresh Bubble" else "Start Bubble")
                                }
                            }

                            if (isDeveloperModeUnlocked) {
                                Spacer(modifier = Modifier.height(24.dp))
                                DeveloperModeSection(
                                    onHideDeveloperMode = {}
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
