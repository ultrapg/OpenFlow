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
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import com.voicebubble.openflow.overlay.FloatingBubbleService
import com.voicebubble.openflow.settings.DesertAntModelStorage
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.ui.onboarding.OnboardingScreen
import com.voicebubble.openflow.ui.settings.SpeechRecognitionSettingsSection
import com.voicebubble.openflow.ui.theme.OpenFlowTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val prefs = remember { getSharedPreferences("openflow_settings", Context.MODE_PRIVATE) }

            var modelSettings by remember { mutableStateOf(ModelSettings.load(context)) }
            var isCheckingModels by remember { mutableStateOf(true) }
            var requiresOnboarding by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val allReady = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DesertAntModelStorage.areAllEnabledModelsDownloaded(context, modelSettings)
                }
                requiresOnboarding = !allReady
                isCheckingModels = false
            }

            var showOnlyOnInputs by remember { mutableStateOf(prefs.getBoolean("show_only_on_inputs", true)) }
            var hapticFeedback by remember { mutableStateOf(prefs.getBoolean("haptic_feedback", true)) }
            var bubbleSize by remember { mutableFloatStateOf(prefs.getFloat("bubble_size", 52f)) }

            val restartService = {
                val intent = Intent(context, FloatingBubbleService::class.java)
                context.stopService(intent)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
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
                        }
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                "OpenFlow Settings",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
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

                            Button(
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
                                Text("Accessibility Service (System Settings)")
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            SpeechRecognitionSettingsSection(
                                settings = modelSettings,
                                onSettingsChanged = { updated ->
                                    modelSettings = updated
                                }
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                "Floating Bubble Settings",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = showOnlyOnInputs,
                                    onCheckedChange = {
                                        showOnlyOnInputs = it
                                        prefs.edit().putBoolean("show_only_on_inputs", it).apply()
                                    }
                                )
                                Text(
                                    "Only show bubble when a text field is focused",
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = hapticFeedback,
                                    onCheckedChange = {
                                        hapticFeedback = it
                                        prefs.edit().putBoolean("haptic_feedback", it).apply()
                                    }
                                )
                                Text(
                                    "Haptic feedback (vibration)",
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
                                        val intent = Intent(context, FloatingBubbleService::class.java)
                                        context.stopService(intent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Stop Service")
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
                                        restartService()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Start / Restart Service")
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
