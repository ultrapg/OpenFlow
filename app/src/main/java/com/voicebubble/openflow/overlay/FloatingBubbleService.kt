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

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService
import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.database.AppDatabase
import com.voicebubble.openflow.core.database.TranscriptionRecord
import com.voicebubble.openflow.core.speech.PipelineStatus
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.settings.ModelSettings
import com.voicebubble.openflow.ui.theme.OpenFlowTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class OverlayBubbleState {
    IDLE,
    RECORDING,
    PROCESSING,
    SUCCESS
}

class FloatingBubbleService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        const val ACTION_REFRESH_SETTINGS = "com.voicebubble.openflow.ACTION_REFRESH_SETTINGS"
        const val KEY_SHOW_ONLY_ON_INPUTS = "show_only_on_inputs_v3"
        const val LEGACY_KEY_SHOW_ONLY_ON_INPUTS = "show_only_on_inputs_v2"
        const val KEY_BUBBLE_DISAPPEAR_DELAY_MS = "bubble_disappear_delay_ms"
        const val DEFAULT_BUBBLE_DISAPPEAR_DELAY_MS = 500L
        private const val CHANNEL_ID = "bubble_service_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var isOverlayAttached = false

    private val audioController = AudioRecordingController()
    private lateinit var smartPipeline: SmartPipeline

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var dictationWakeLock: PowerManager.WakeLock? = null

    private val bubbleState = MutableStateFlow(OverlayBubbleState.IDLE)
    private val processingProgress = MutableStateFlow("")

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private val modelPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updatedSettings = ModelSettings.load(this@FloatingBubbleService)
        serviceScope.launch(Dispatchers.Default) {
            smartPipeline.reloadSettings(updatedSettings)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        smartPipeline = SmartPipeline.getInstance(this)

        // Start foreground immediately in onCreate to satisfy Android 8-15 strict FGS timeout
        startCustomForeground(smartPipeline.pipelineStatus.value)

        setupOverlayView()
        initializePipeline()

        val modelPrefs = ModelSettings.getPreferences(this)
        modelPrefs.registerOnSharedPreferenceChangeListener(modelPrefsListener)

        // Keep status-bar notification updated with live model load & accelerator status
        serviceScope.launch {
            smartPipeline.pipelineStatus.collect { status ->
                updateForegroundNotification(status)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        _isRunning.value = true
        startCustomForeground(smartPipeline.pipelineStatus.value)

        if (!isOverlayAttached && Settings.canDrawOverlays(this)) {
            setupOverlayView()
        }

        if (intent?.action == ACTION_REFRESH_SETTINGS) {
            clampBubblePositionToScreen()
            serviceScope.launch(Dispatchers.Default) {
                smartPipeline.reloadSettings(ModelSettings.load(this@FloatingBubbleService))
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        clampBubblePositionToScreen()
    }

    private fun clampBubblePositionToScreen() {
        if (!isOverlayAttached || !::layoutParams.isInitialized || !::composeView.isInitialized) return
        try {
            val prefs = getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
            val bubbleSizeDp = prefs.getFloat("bubble_size", 52f).coerceIn(36f, 80f)
            val dm = resources.displayMetrics
            val bubblePx = (bubbleSizeDp * dm.density).roundToInt().coerceAtLeast(96)
            val maxX = (dm.widthPixels - bubblePx).coerceAtLeast(0)
            val maxY = (dm.heightPixels - bubblePx).coerceAtLeast(0)
            layoutParams.x = layoutParams.x.coerceIn(0, maxX)
            layoutParams.y = layoutParams.y.coerceIn(0, maxY)
            if (composeView.isAttachedToWindow) {
                windowManager.updateViewLayout(composeView, layoutParams)
            }
        } catch (_: Throwable) {
        }
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
        initPipelineJob = serviceScope.launch(Dispatchers.Default) {
            previousJob?.cancelAndJoin()
            try {
                val loadedSettings = ModelSettings.load(this@FloatingBubbleService)
                val ready = smartPipeline.initialize(warmUp = true)
                withContext(Dispatchers.Main) {
                    if (!ready && !com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDownloaded(
                            this@FloatingBubbleService,
                            com.voicebubble.openflow.settings.DesertAntModel.VOZ
                        )
                    ) {
                        android.widget.Toast.makeText(
                            this@FloatingBubbleService,
                            "Please download the Voz speech model in OpenFlow first.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun buildForegroundNotification(status: PipelineStatus): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Dictation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.voicebubble.openflow.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (status.isReady) {
            "OpenFlow Active • ${status.loadedCount}/${status.enabledCount} Models in RAM"
        } else {
            "OpenFlow Active • ${status.phase.label}"
        }
        val subtitle = "${status.activeAcceleratorLabel} — Tap floating bubble to dictate"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(com.voicebubble.openflow.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startCustomForeground(status: PipelineStatus) {
        val notification = buildForegroundNotification(status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasMicPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val fgsType = if (hasMicPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } catch (_: Throwable) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun updateForegroundNotification(status: PipelineStatus) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildForegroundNotification(status))
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun setupOverlayView() {
        if (isOverlayAttached) return
        if (!Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(
                this,
                "Overlay permission is required to display the floating bubble.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        val prefs = getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
        val metrics = resources.displayMetrics
        val savedX = prefs.getInt("bubble_x", 80).coerceIn(0, (metrics.widthPixels - 140).coerceAtLeast(0))
        val savedY = prefs.getInt("bubble_y", 400).coerceIn(0, (metrics.heightPixels - 180).coerceAtLeast(0))
        val initialShowOnlyOnKeyboard = prefs.getBoolean(KEY_SHOW_ONLY_ON_INPUTS, true)
        val initiallyVisible = !initialShowOnlyOnKeyboard || TextInjectionAccessibilityService.isInputFieldFocused.value

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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (initiallyVisible) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = savedX
            y = savedY
            alpha = if (initiallyVisible) 1.0f else 0.0f
        }

        val themeContext = android.view.ContextThemeWrapper(this, com.voicebubble.openflow.R.style.Theme_OpenFlow)
        composeView = ComposeView(themeContext).apply {
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)

            setContent {
                OpenFlowTheme {
                    val currentState by bubbleState.collectAsState()
                    val isAccessibilityConnected by TextInjectionAccessibilityService.isServiceConnected.collectAsState()
                    val isAccessibilityImeVisible by TextInjectionAccessibilityService.isInputFieldFocused.collectAsState()
                    val pipelineStatus by smartPipeline.pipelineStatus.collectAsState()

                    val settingsPrefs = androidx.compose.runtime.remember {
                        getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
                    }
                    var showOnlyOnInputs by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            settingsPrefs.getBoolean(KEY_SHOW_ONLY_ON_INPUTS, true)
                        )
                    }
                    var disappearDelayMs by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            settingsPrefs.getLong(KEY_BUBBLE_DISAPPEAR_DELAY_MS, DEFAULT_BUBBLE_DISAPPEAR_DELAY_MS)
                                .coerceIn(0L, 10_000L)
                        )
                    }
                    var bubbleSize by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            settingsPrefs.getFloat("bubble_size", 52f).coerceIn(36f, 80f)
                        )
                    }
                    var isWindowInsetsImeVisible by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(false)
                    }

                    // Poll WindowInsetsCompat.Type.ime() on API 30+ as a direct fallback if Accessibility Service is not yet enabled
                    androidx.compose.runtime.LaunchedEffect(isAccessibilityConnected) {
                        while (!isAccessibilityConnected) {
                            isWindowInsetsImeVisible = checkWindowInsetsImeVisible()
                            delay(180L)
                        }
                    }

                    val listener = androidx.compose.runtime.remember {
                        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                            if (sharedPreferences != null && key != null) {
                                when (key) {
                                    KEY_SHOW_ONLY_ON_INPUTS -> {
                                        showOnlyOnInputs = sharedPreferences.getBoolean(key, true)
                                    }
                                    KEY_BUBBLE_DISAPPEAR_DELAY_MS -> {
                                        disappearDelayMs = sharedPreferences.getLong(
                                            key,
                                            DEFAULT_BUBBLE_DISAPPEAR_DELAY_MS
                                        ).coerceIn(0L, 10_000L)
                                    }
                                    "bubble_size" -> {
                                        bubbleSize = sharedPreferences.getFloat(key, 52f).coerceIn(36f, 80f)
                                    }
                                }
                            }
                        }
                    }

                    androidx.compose.runtime.DisposableEffect(settingsPrefs, listener) {
                        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
                        onDispose {
                            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                        }
                    }

                    val isKeyboardCurrentlyOnScreen = if (isAccessibilityConnected) {
                        isAccessibilityImeVisible
                    } else {
                        isWindowInsetsImeVisible
                    }
                    val rawShouldShow = !showOnlyOnInputs ||
                        isKeyboardCurrentlyOnScreen ||
                        currentState != OverlayBubbleState.IDLE

                    var isBubbleVisible by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(rawShouldShow)
                    }

                    androidx.compose.runtime.LaunchedEffect(rawShouldShow, disappearDelayMs) {
                        if (rawShouldShow) {
                            setOverlayWindowVisibleAndTouchable(visible = true, touchable = true)
                            isBubbleVisible = true
                        } else {
                            if (disappearDelayMs > 0L) {
                                delay(disappearDelayMs)
                            }
                            isBubbleVisible = false
                            // Immediately disable touch interception while exit animation plays
                            setOverlayWindowVisibleAndTouchable(visible = true, touchable = false)
                            delay(160L)
                            // Completely hide the WindowManager surface so SurfaceFlinger never leaves a frozen frame
                            setOverlayWindowVisibleAndTouchable(visible = false, touchable = false)
                        }
                    }

                    BubbleOverlayComponent(
                        audioController = audioController,
                        bubbleState = currentState,
                        isInputFieldFocused = isBubbleVisible,
                        processingProgress = processingProgress.collectAsState(initial = "").value,
                        bubbleSize = bubbleSize,
                        pipelinePhase = pipelineStatus.phase,
                        isModelLoaded = pipelineStatus.isReady,
                        onDragDelta = { dx, dy ->
                            if (dx.isNaN() || dy.isNaN()) return@BubbleOverlayComponent
                            val dm = resources.displayMetrics
                            val bubblePx = (bubbleSize * dm.density).roundToInt().coerceAtLeast(96)
                            val maxX = (dm.widthPixels - bubblePx).coerceAtLeast(0)
                            val maxY = (dm.heightPixels - bubblePx).coerceAtLeast(0)

                            this@FloatingBubbleService.layoutParams.x =
                                (this@FloatingBubbleService.layoutParams.x + dx.roundToInt()).coerceIn(0, maxX)
                            this@FloatingBubbleService.layoutParams.y =
                                (this@FloatingBubbleService.layoutParams.y + dy.roundToInt()).coerceIn(0, maxY)

                            try {
                                if (::composeView.isInitialized && composeView.isAttachedToWindow) {
                                    windowManager.updateViewLayout(
                                        composeView,
                                        this@FloatingBubbleService.layoutParams
                                    )
                                    settingsPrefs.edit()
                                        .putInt("bubble_x", this@FloatingBubbleService.layoutParams.x)
                                        .putInt("bubble_y", this@FloatingBubbleService.layoutParams.y)
                                        .apply()
                                }
                            } catch (t: Throwable) {
                                t.printStackTrace()
                            }
                        },
                        onStartRecording = {
                            if (ContextCompat.checkSelfPermission(
                                    this@FloatingBubbleService,
                                    Manifest.permission.RECORD_AUDIO
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                android.widget.Toast.makeText(
                                    this@FloatingBubbleService,
                                    "Microphone permission is required to record speech.",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@BubbleOverlayComponent
                            }
                            acquireDictationWakeLock()
                            bubbleState.value = OverlayBubbleState.RECORDING
                            if (settingsPrefs.getBoolean("haptic_feedback", true)) vibrate(20, 180)
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
            isOverlayAttached = true
        } catch (t: Throwable) {
            t.printStackTrace()
            isOverlayAttached = false
            stopSelf()
        }
    }

    private fun checkWindowInsetsImeVisible(): Boolean {
        if (!::composeView.isInitialized || !composeView.isAttachedToWindow) return false
        return try {
            val rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(composeView) ?: return false
            rootInsets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
        } catch (_: Throwable) {
            false
        }
    }

    private fun setKeepScreenOn(keep: Boolean) {
        if (!::layoutParams.isInitialized || !::composeView.isInitialized) return
        if (keep) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            if (composeView.isAttachedToWindow) {
                windowManager.updateViewLayout(composeView, layoutParams)
            }
        } catch (_: Throwable) {
        }
    }

    private fun setOverlayWindowVisibleAndTouchable(visible: Boolean, touchable: Boolean) {
        if (!::layoutParams.isInitialized || !::composeView.isInitialized) return
        val newFlags = if (touchable) {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val newAlpha = if (visible) 1.0f else 0.0f
        if (newFlags == layoutParams.flags && layoutParams.alpha == newAlpha) return
        layoutParams.flags = newFlags
        layoutParams.alpha = newAlpha
        try {
            if (composeView.isAttachedToWindow) {
                windowManager.updateViewLayout(composeView, layoutParams)
            }
        } catch (_: Throwable) {
        }
    }

    private fun acquireDictationWakeLock() {
        try {
            if (dictationWakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            dictationWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OpenFlow::DictationWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 1000L) // 3-minute safety timeout per dictation
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseDictationWakeLock() {
        try {
            dictationWakeLock?.let {
                if (it.isHeld) it.release()
            }
            dictationWakeLock = null
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
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
                    val autoPasteEnabled = prefs.getBoolean("auto_paste", true)
                    val accessibilityService = TextInjectionAccessibilityService.getSharedInstance()
                    val injected = if (autoPasteEnabled && accessibilityService != null) {
                        accessibilityService.injectTextSuspend(finalText)
                    } else {
                        false
                    }

                    if (!injected) {
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
                                wasInjectedSuccessfully = injected
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                bubbleState.value = OverlayBubbleState.SUCCESS
                delay(1200)
                processingProgress.value = ""
                bubbleState.value = OverlayBubbleState.IDLE
            } catch (t: Throwable) {
                t.printStackTrace()
                processingProgress.value = ""
                bubbleState.value = OverlayBubbleState.IDLE
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@FloatingBubbleService,
                        "Transcription error: ${t.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                releaseDictationWakeLock()
            }
        }
    }

    override fun onDestroy() {
        _isRunning.value = false
        releaseDictationWakeLock()
        try {
            val modelPrefs = ModelSettings.getPreferences(this)
            modelPrefs.unregisterOnSharedPreferenceChangeListener(modelPrefsListener)
        } catch (_: Throwable) {
        }
        audioController.releaseNow()
        serviceScope.cancel()
        if (::composeView.isInitialized && isOverlayAttached) {
            try {
                windowManager.removeView(composeView)
            } catch (_: Throwable) {
            }
            isOverlayAttached = false
        }
        super.onDestroy()
    }
}
