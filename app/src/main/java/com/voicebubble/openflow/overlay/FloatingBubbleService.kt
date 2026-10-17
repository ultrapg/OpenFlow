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

package com.voicebubble.openflow.overlay

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService
import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.database.AppDatabase
import com.voicebubble.openflow.core.database.TranscriptionRecord
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.ui.theme.OpenFlowTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

enum class OverlayBubbleState {
    IDLE,
    RECORDING,
    PROCESSING,
    SUCCESS
}

class FloatingBubbleService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val audioController = AudioRecordingController()
    private lateinit var smartPipeline: SmartPipeline

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bubbleState = MutableStateFlow(OverlayBubbleState.IDLE)
    private val processingProgress = MutableStateFlow("")

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private val modelPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updatedSettings = ModelSettings.load(this@FloatingBubbleService)
        serviceScope.launch(Dispatchers.IO) {
            smartPipeline.reloadSettings(updatedSettings)
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        smartPipeline = SmartPipeline.getInstance(this)
        initializePipeline()
        setupOverlayView()
        val modelPrefs = ModelSettings.getPreferences(this)
        modelPrefs.registerOnSharedPreferenceChangeListener(modelPrefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startCustomForeground()
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::smartPipeline.isInitialized) {
            smartPipeline.onTrimMemory(level)
        }
    }

    private var initPipelineJob: Job? = null

    private fun initializePipeline() {
        val previousJob = initPipelineJob
        initPipelineJob = serviceScope.launch(Dispatchers.IO) {
            previousJob?.cancelAndJoin()
            try {
                smartPipeline.reloadSettings(ModelSettings.load(this@FloatingBubbleService))
                val ready = smartPipeline.initialize()
                withContext(Dispatchers.Main) {
                    if (ready) {
                        android.widget.Toast.makeText(
                            this@FloatingBubbleService,
                            "Desert Ant audio pipeline ready",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            this@FloatingBubbleService,
                            "Please download the Voz speech model in the app first.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@FloatingBubbleService,
                        "Initialization error: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startCustomForeground() {
        val channelId = "bubble_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Voice Dictation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.voicebubble.openflow.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OpenFlow Active")
            .setContentText("Tap the floating bubble to dictate.")
            .setSmallIcon(com.voicebubble.openflow.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    1001,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun setupOverlayView() {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 80
            y = 400
        }

        val themeContext = android.view.ContextThemeWrapper(this, com.voicebubble.openflow.R.style.Theme_OpenFlow)
        composeView = ComposeView(themeContext).apply {
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)

            setContent {
                OpenFlowTheme {
                    val currentState by bubbleState.collectAsState()
                    val isFocused by TextInjectionAccessibilityService.isInputFieldFocused.collectAsState()
                    val prefs = androidx.compose.runtime.remember {
                        getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
                    }
                    var showOnlyOnInputs by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(prefs.getBoolean("show_only_on_inputs", true))
                    }
                    var bubbleSize by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(prefs.getFloat("bubble_size", 52f))
                    }

                    val listener = androidx.compose.runtime.remember {
                        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                            if (sharedPreferences != null && key != null) {
                                when (key) {
                                    "show_only_on_inputs" -> showOnlyOnInputs = sharedPreferences.getBoolean(key, true)
                                    "bubble_size" -> bubbleSize = sharedPreferences.getFloat(key, 52f)
                                }
                            }
                        }
                    }

                    androidx.compose.runtime.DisposableEffect(prefs, listener) {
                        prefs.registerOnSharedPreferenceChangeListener(listener)
                        onDispose {
                            prefs.unregisterOnSharedPreferenceChangeListener(listener)
                        }
                    }
                    val isEffectivelyFocused = if (showOnlyOnInputs) isFocused else true

                    BubbleOverlayComponent(
                        audioController = audioController,
                        bubbleState = currentState,
                        isInputFieldFocused = isEffectivelyFocused,
                        processingProgress = processingProgress.collectAsState(initial = "").value,
                        bubbleSize = bubbleSize,
                        onDragDelta = { dx, dy ->
                            this@FloatingBubbleService.layoutParams.x += dx.toInt()
                            this@FloatingBubbleService.layoutParams.y += dy.toInt()
                            try {
                                windowManager.updateViewLayout(
                                    composeView,
                                    this@FloatingBubbleService.layoutParams
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        onStartRecording = {
                            bubbleState.value = OverlayBubbleState.RECORDING
                            if (prefs.getBoolean("haptic_feedback", true)) vibrate(20, 180)
                            setKeepScreenOn(true)
                            serviceScope.launch {
                                audioController.startRecording(this)
                            }
                        },
                        onStopRecording = {
                            bubbleState.value = OverlayBubbleState.PROCESSING
                            setKeepScreenOn(false)
                            serviceScope.launch {
                                val samples = audioController.stopRecording()
                                processAudio(samples)
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(composeView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(
                this,
                "Error: Missing overlay permission!",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun setKeepScreenOn(keep: Boolean) {
        if (keep) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            if (composeView.isAttachedToWindow) {
                windowManager.updateViewLayout(composeView, layoutParams)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun vibrate(duration: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(duration)
            }
        } catch (_: Exception) {
        }
    }

    private fun processAudio(samples: FloatArray) {
        val prefs = getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)

        serviceScope.launch {
            try {
                processingProgress.value = ""

                val chunkText = smartPipeline.transcribe(samples) { stageLabel ->
                    processingProgress.value = stageLabel
                }

                val finalText = chunkText.trim()
                    .replace(Regex(" +([.,!?:;%€])"), "$1")
                    .replace("O K", "OK")
                    .replace("o k", "ok")
                    .replace(Regex("\\s+"), " ")

                if (finalText.isNotBlank()) {
                    val isFocused = TextInjectionAccessibilityService.isInputFieldFocused.value
                    if (isFocused) {
                        val accessibilityService = TextInjectionAccessibilityService.getSharedInstance()
                        accessibilityService?.injectTextDirectly(finalText)
                    } else {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlow", finalText))
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                this@FloatingBubbleService,
                                "Copied to clipboard",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    if (prefs.getBoolean("haptic_feedback", true)) vibrate(15, 120)

                    try {
                        val dao = AppDatabase.getDatabase(this@FloatingBubbleService).transcriptionDao()
                        dao.insert(
                            TranscriptionRecord(
                                transcribedText = finalText,
                                durationMs = (samples.size / 16f).toLong(),
                                audioSamplesCount = samples.size,
                                modelIdentifier = "desert-ant-voz-4.0.0",
                                wasInjectedSuccessfully = isFocused
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                processingProgress.value = ""
                bubbleState.value = OverlayBubbleState.SUCCESS
                delay(1500)
                bubbleState.value = OverlayBubbleState.IDLE
            } catch (e: Exception) {
                e.printStackTrace()
                processingProgress.value = ""
                bubbleState.value = OverlayBubbleState.IDLE
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@FloatingBubbleService,
                        "Transcription error: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val modelPrefs = ModelSettings.getPreferences(this)
        modelPrefs.unregisterOnSharedPreferenceChangeListener(modelPrefsListener)
        GlobalScope.launch { audioController.release() }
        GlobalScope.launch { smartPipeline.shutdown() }
        serviceScope.cancel()
        if (::composeView.isInitialized) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
