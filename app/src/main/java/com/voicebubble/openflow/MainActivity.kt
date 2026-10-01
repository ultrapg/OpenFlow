package com.voicebubble.openflow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import com.voicebubble.openflow.overlay.FloatingBubbleService
import androidx.compose.foundation.background
import com.voicebubble.openflow.ui.theme.OpenFlowTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            val context = LocalContext.current
            val prefs = remember { getSharedPreferences("openflow_settings", Context.MODE_PRIVATE) }
            
            var showOnlyOnInputs by remember { mutableStateOf(prefs.getBoolean("show_only_on_inputs", true)) }
            var hapticFeedback by remember { mutableStateOf(prefs.getBoolean("haptic_feedback", true)) }
            var bubbleSize by remember { mutableStateOf(prefs.getFloat("bubble_size", 52f)) }

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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                    ) {
                        Text("OpenFlow Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val micPermissionState = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)
                        
                        var isIgnoringBattery by remember { mutableStateOf(false) }
                        var canDrawOverlaysState by remember { mutableStateOf(false) }
                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        
                        DisposableEffect(lifecycleOwner) {
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                    isIgnoringBattery = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isIgnoringBatteryOptimizations(context.packageName)
                                    canDrawOverlaysState = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBattery) {
                            Button(onClick = { 
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.data = Uri.parse("package:$packageName")
                                try { startActivity(intent) } catch (e: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(this@MainActivity, "Einstellung auf diesem Gerät nicht gefunden.", android.widget.Toast.LENGTH_LONG).show() }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Akku-Optimierung deaktivieren (WICHTIG)")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        } else {
                            Text("✓ Akku-Optimierung deaktiviert", color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        if (!micPermissionState.status.isGranted) {
                            Button(onClick = { micPermissionState.launchPermissionRequest() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Mikrofon-Berechtigung erteilen")
                            }
                        } else {
                            Text("✓ Mikrofon-Zugriff Aktiv", color = MaterialTheme.colorScheme.primary)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canDrawOverlaysState) {
                            Button(onClick = { 
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                try { startActivity(intent) } catch (e: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(this@MainActivity, "Einstellung auf diesem Gerät nicht gefunden.", android.widget.Toast.LENGTH_LONG).show() }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Overlay-Berechtigung erteilen")
                            }
                        } else {
                            Text("✓ Overlay-Berechtigung Aktiv", color = MaterialTheme.colorScheme.primary)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            try { startActivity(intent) } catch (e: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(this@MainActivity, "Einstellung auf diesem Gerät nicht gefunden.", android.widget.Toast.LENGTH_LONG).show() }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Accessibility-Dienst (System-Settings)")
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text("Einstellungen", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var language by remember { mutableStateOf(prefs.getString("language", "auto") ?: "auto") }
                        
                        Text("Sprache", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "Auto", "de" to "Deutsch", "en" to "Englisch").forEach { (value, label) ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(if (language == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        language = value
                                        prefs.edit().putString("language", value).apply()
                                    }
                                    .padding(8.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(label, color = if (language == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showOnlyOnInputs,
                                onCheckedChange = { 
                                    showOnlyOnInputs = it
                                    prefs.edit().putBoolean("show_only_on_inputs", it).apply()
                                }
                            )
                            Text("Bubble nur zeigen, wenn Textfeld aktiv ist", color = MaterialTheme.colorScheme.onBackground)
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = hapticFeedback,
                                onCheckedChange = { 
                                    hapticFeedback = it
                                    prefs.edit().putBoolean("haptic_feedback", it).apply()
                                }
                            )
                            Text("Haptisches Feedback (Vibration)", color = MaterialTheme.colorScheme.onBackground)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Bubble Größe: ${bubbleSize.toInt()} dp", color = MaterialTheme.colorScheme.onBackground)
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            
                            // Preview Bubble
                            Box(
                                modifier = Modifier
                                    .size(bubbleSize.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(android.R.drawable.ic_btn_speak_now),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size((bubbleSize * 0.5f).dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val intent = Intent(context, FloatingBubbleService::class.java)
                                    context.stopService(intent)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Beenden")
                            }
                            
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        android.widget.Toast.makeText(context, "Bitte erst Overlay-Berechtigung erteilen!", android.widget.Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    if (!micPermissionState.status.isGranted) {
                                        android.widget.Toast.makeText(context, "Bitte erst Mikrofon-Berechtigung erteilen!", android.widget.Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    restartService()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Neustart / Start")
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
