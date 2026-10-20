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
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.voicebubble.openflow.core.debug.DebugLogManager
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
import kotlinx.coroutines.isActive
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
        const val ACTION_RESTORE_NOTIFICATION = "com.voicebubble.openflow.ACTION_RESTORE_NOTIFICATION"
        const val ACTION_EXPLICIT_STOP = "com.voicebubble.openflow.ACTION_EXPLICIT_STOP"

        const val PREFS_NAME = "openflow_settings"
        const val KEY_SHOW_ONLY_ON_INPUTS = "show_only_on_inputs_v3"
        const val LEGACY_KEY_SHOW_ONLY_ON_INPUTS = "show_only_on_inputs_v2"
        const val KEY_BUBBLE_DISAPPEAR_DELAY_MS = "bubble_disappear_delay_ms"
        const val DEFAULT_BUBBLE_DISAPPEAR_DELAY_MS = 500L
        const val KEY_BUBBLE_ENABLED_BY_USER = "bubble_enabled_by_user"
        const val KEY_AUTO_START_BUBBLE = "auto_start_bubble"

        private const val CHANNEL_ID = "bubble_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_RESTORE_NOTIFICATION = 1002

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private fun getDeviceProtectedPrefs(context: Context) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.applicationContext
                    .createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }

        /**
         * Returns `true` if the user wants the floating bubble active (default `true` until the user
         * explicitly taps "Stop Bubble" in Settings).
         */
        fun isBubbleEnabledByUser(context: Context): Boolean {
            return try {
                val dpPrefs = getDeviceProtectedPrefs(context)
                if (dpPrefs.contains(KEY_BUBBLE_ENABLED_BY_USER)) {
                    dpPrefs.getBoolean(KEY_BUBBLE_ENABLED_BY_USER, true)
                } else {
                    val userPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    userPrefs.getBoolean(KEY_BUBBLE_ENABLED_BY_USER, true)
                }
            } catch (_: Throwable) {
                true
            }
        }

        fun setBubbleEnabledByUser(context: Context, enabled: Boolean) {
            try {
                getDeviceProtectedPrefs(context).edit()
                    .putBoolean(KEY_BUBBLE_ENABLED_BY_USER, enabled)
                    .apply()
            } catch (_: Throwable) {
            }
            try {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_BUBBLE_ENABLED_BY_USER, enabled)
                    .apply()
            } catch (_: Throwable) {
            }
        }

        /**
         * Returns `true` if automatic startup on phone boot and automatic restart after crashes is enabled.
         */
        fun isAutoStartEnabled(context: Context): Boolean {
            return try {
                val dpPrefs = getDeviceProtectedPrefs(context)
                if (dpPrefs.contains(KEY_AUTO_START_BUBBLE)) {
                    dpPrefs.getBoolean(KEY_AUTO_START_BUBBLE, true)
                } else {
                    val userPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    userPrefs.getBoolean(KEY_AUTO_START_BUBBLE, true)
                }
            } catch (_: Throwable) {
                true
            }
        }

        fun setAutoStartEnabled(context: Context, enabled: Boolean) {
            try {
                getDeviceProtectedPrefs(context).edit()
                    .putBoolean(KEY_AUTO_START_BUBBLE, enabled)
                    .apply()
            } catch (_: Throwable) {
            }
            try {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_AUTO_START_BUBBLE, enabled)
                    .apply()
            } catch (_: Throwable) {
            }
        }

        /**
         * Checks whether [FloatingBubbleService] should be automatically started/restarted on boot,
         * crash recovery, or notification dismissal.
         */
        fun shouldAutoStart(context: Context): Boolean {
            val appContext = context.applicationContext
            if (!isBubbleEnabledByUser(appContext) || !isAutoStartEnabled(appContext)) return false
            val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(appContext)
            } else {
                true
            }
            return canDraw
        }

        /**
         * Starts [FloatingBubbleService] if [shouldAutoStart] is `true` and the service is not already running.
         */
        fun ensureRunningIfEnabled(context: Context, reason: String = "watchdog"): Boolean {
            val appContext = context.applicationContext
            if (_isRunning.value) return true
            if (!shouldAutoStart(appContext)) return false
            return try {
                val intent = Intent(appContext, FloatingBubbleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                DebugLogManager.logInfo(
                    "FloatingBubbleService",
                    "ensureRunningIfEnabled started service (reason=$reason)"
                )
                true
            } catch (t: Throwable) {
                DebugLogManager.logError(
                    "FloatingBubbleService",
                    "ensureRunningIfEnabled failed (reason=$reason)",
                    t
                )
                false
            }
        }
    }

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var isOverlayAttached = false
    private var stoppedExplicitlyByUser = false
    private var screenReceiverRegistered = false

    private val audioController = AudioRecordingController()
    private lateinit var smartPipeline: SmartPipeline

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var dictationWakeLock: PowerManager.WakeLock? = null

    private val bubbleState = MutableStateFlow(OverlayBubbleState.IDLE)
    private val processingProgress = MutableStateFlow("")
    private val isScreenActiveAndUnlocked = MutableStateFlow(true)

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    onScreenTurnedOffOrLocked()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    val activeAndUnlocked = checkScreenActiveAndUnlocked()
                    isScreenActiveAndUnlocked.value = activeAndUnlocked
                    if (!activeAndUnlocked) {
                        onScreenTurnedOffOrLocked()
                    } else {
                        TextInjectionAccessibilityService.getSharedInstance()?.refreshNow()
                    }
                }
            }
        }
    }

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
        stoppedExplicitlyByUser = false
        setBubbleEnabledByUser(this, true)
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        smartPipeline = SmartPipeline.getInstance(this)
        isScreenActiveAndUnlocked.value = checkScreenActiveAndUnlocked()

        // Start foreground immediately in onCreate to satisfy Android 8-15 strict FGS timeout
        startCustomForeground(smartPipeline.pipelineStatus.value)

        registerScreenStateReceiver()
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

        // Self-healing watchdog: restores dismissed notification on Android 14+, re-attaches overlay if detached,
        // and enforces screen-off / lockscreen hiding
        serviceScope.launch {
            while (isActive) {
                delay(4_000L)
                val activeAndUnlocked = checkScreenActiveAndUnlocked()
                if (isScreenActiveAndUnlocked.value != activeAndUnlocked) {
                    isScreenActiveAndUnlocked.value = activeAndUnlocked
                }
                if (!activeAndUnlocked) {
                    setOverlayWindowVisibleAndTouchable(visible = false, touchable = false)
                } else {
                    ensureOverlayAttached()
                }
                ensureNotificationPresent()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action == ACTION_EXPLICIT_STOP) {
            stoppedExplicitlyByUser = true
            setBubbleEnabledByUser(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        stoppedExplicitlyByUser = false
        setBubbleEnabledByUser(this, true)
        startCustomForeground(smartPipeline.pipelineStatus.value)

        isScreenActiveAndUnlocked.value = checkScreenActiveAndUnlocked()
        ensureOverlayAttached()

        if (action == ACTION_REFRESH_SETTINGS) {
            clampBubblePositionToScreen()
        } else if (action == ACTION_RESTORE_NOTIFICATION) {
            DebugLogManager.logInfo("FloatingBubbleService", "Restoring dismissed foreground notification")
            updateForegroundNotification(smartPipeline.pipelineStatus.value)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DebugLogManager.logInfo("FloatingBubbleService", "onTaskRemoved() called")
        if (!stoppedExplicitlyByUser && shouldAutoStart(this)) {
            startCustomForeground(smartPipeline.pipelineStatus.value)
            ensureOverlayAttached()
            BootAndRestartReceiver.scheduleRestart(this, delayMs = 800L, reason = "onTaskRemoved")
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        clampBubblePositionToScreen()
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Throwable) {
        }
        screenReceiverRegistered = false
    }

    private fun checkScreenActiveAndUnlocked(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isInteractive = pm?.isInteractive ?: true
            val isKeyguardLocked = km?.isKeyguardLocked ?: false
            isInteractive && !isKeyguardLocked
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Immediately hides the floating bubble surface when the screen turns off or the lockscreen engages,
     * bypassing Compose VSYNC delays (since Choreographer stops ticking when the display is off).
     */
    private fun onScreenTurnedOffOrLocked() {
        isScreenActiveAndUnlocked.value = false
        TextInjectionAccessibilityService.isInputFieldFocused.value = false

        if (bubbleState.value == OverlayBubbleState.RECORDING) {
            bubbleState.value = OverlayBubbleState.IDLE
            setKeepScreenOn(false)
            releaseDictationWakeLock()
            serviceScope.launch {
                try {
                    audioController.stopRecording()
                } catch (_: Throwable) {
                }
            }
        }

        // Synchronously hide WindowManager overlay surface with 0ms delay
        setOverlayWindowVisibleAndTouchable(visible = false, touchable = false)
    }

    private fun ensureOverlayAttached() {
        if (!Settings.canDrawOverlays(this)) return
        val viewAttached = isOverlayAttached && ::composeView.isInitialized && composeView.isAttachedToWindow
        if (!viewAttached) {
            if (isOverlayAttached && ::composeView.isInitialized) {
                try {
                    windowManager.removeViewImmediate(composeView)
                } catch (_: Throwable) {
                }
                isOverlayAttached = false
            }
            setupOverlayView()
        }
    }

    private fun ensureNotificationPresent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val hasNotification = nm.activeNotifications.any { it.id == NOTIFICATION_ID }
            if (!hasNotification) {
                startCustomForeground(smartPipeline.pipelineStatus.value)
            }
        } catch (_: Throwable) {
        }
    }

    private fun clampBubblePositionToScreen() {
        if (!isOverlayAttached || !::layoutParams.isInitialized || !::composeView.isInitialized) return
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        DebugLogManager.logTrimMemory("FloatingBubbleService", level)
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
            ).apply {
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.voicebubble.openflow.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // If the user swipes/dismisses the ongoing notification on Android 13/14/15, immediately restore it
        val restoreNotificationIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_RESTORE_NOTIFICATION
        }
        val deletePendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_RESTORE_NOTIFICATION,
            restoreNotificationIntent,
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
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
                    // When started on BOOT_COMPLETED in Android 14/15, MICROPHONE type may be restricted
                    // until first user interaction; SPECIAL_USE succeeds on boot and upgrades on tap.
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } catch (_: Throwable) {
                    startForeground(NOTIFICATION_ID, notification)
                }
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
        if (isOverlayAttached && ::composeView.isInitialized && composeView.isAttachedToWindow) return
        if (!Settings.canDrawOverlays(this)) {
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val metrics = resources.displayMetrics
        val savedX = prefs.getInt("bubble_x", 80).coerceIn(0, (metrics.widthPixels - 140).coerceAtLeast(0))
        val savedY = prefs.getInt("bubble_y", 400).coerceIn(0, (metrics.heightPixels - 180).coerceAtLeast(0))
        val initialShowOnlyOnKeyboard = prefs.getBoolean(KEY_SHOW_ONLY_ON_INPUTS, true)
        val screenActive = checkScreenActiveAndUnlocked()
        isScreenActiveAndUnlocked.value = screenActive
        val initiallyVisible = screenActive && (!initialShowOnlyOnKeyboard || TextInjectionAccessibilityService.isInputFieldFocused.value)

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
                    val screenActiveState by isScreenActiveAndUnlocked.collectAsState()
                    val isAccessibilityConnected by TextInjectionAccessibilityService.isServiceConnected.collectAsState()
                    val isAccessibilityImeVisible by TextInjectionAccessibilityService.isInputFieldFocused.collectAsState()
                    val pipelineStatus by smartPipeline.pipelineStatus.collectAsState()

                    val settingsPrefs = androidx.compose.runtime.remember {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    }
                    var showOnlyOnInputs by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            settingsPrefs.getBoolean(KEY_SHOW_ONLY_ON_INPUTS, true)
                        )
                    }
                    var disappearDelayMs by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            settingsPrefs.getLong(KEY_BUBBLE_DISAPPEAR_DELAY_MS, DEFAULT_BUBBLE_DISAPPEAR_DELAY_MS)
                                .coerceIn(0L, 5_000L)
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
                    androidx.compose.runtime.LaunchedEffect(isAccessibilityConnected, screenActiveState) {
                        while (!isAccessibilityConnected && screenActiveState) {
                            isWindowInsetsImeVisible = checkWindowInsetsImeVisible()
                            delay(180L)
                        }
                        if (!screenActiveState) {
                            isWindowInsetsImeVisible = false
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
                                        ).coerceIn(0L, 5_000L)
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

                    val isKeyboardCurrentlyOnScreen = if (!screenActiveState) {
                        false
                    } else if (isAccessibilityConnected) {
                        isAccessibilityImeVisible
                    } else {
                        isWindowInsetsImeVisible
                    }

                    val rawShouldShow = screenActiveState && (
                        !showOnlyOnInputs ||
                            isKeyboardCurrentlyOnScreen ||
                            currentState != OverlayBubbleState.IDLE
                        )

                    var isBubbleVisible by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(rawShouldShow)
                    }

                    androidx.compose.runtime.LaunchedEffect(rawShouldShow, screenActiveState, disappearDelayMs) {
                        if (!screenActiveState) {
                            // Screen is off or locked: hide immediately without waiting for disappearDelayMs or animation
                            isBubbleVisible = false
                            setOverlayWindowVisibleAndTouchable(visible = false, touchable = false)
                        } else if (rawShouldShow) {
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
                        isInputFieldFocused = isBubbleVisible && screenActiveState,
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
                            // Ensure MICROPHONE foreground service type is active on user tap (if started on boot as SPECIAL_USE)
                            startCustomForeground(smartPipeline.pipelineStatus.value)
                            acquireDictationWakeLock()
                            bubbleState.value = OverlayBubbleState.RECORDING
                            if (settingsPrefs.getBoolean("haptic_feedback", true)) vibrate(20, 180)
                            setKeepScreenOn(true)
                            serviceScope.launch {
                                val started = audioController.startRecording(this)
                                if (!started) {
                                    bubbleState.value = OverlayBubbleState.IDLE
                                    setKeepScreenOn(false)
                                    releaseDictationWakeLock()
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            this@FloatingBubbleService,
                                            "Could not access microphone (may be in use by another app).",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
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
        }
    }

    private fun checkWindowInsetsImeVisible(): Boolean {
        if (!checkScreenActiveAndUnlocked()) return false
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
                DebugLogManager.logError("FloatingBubbleService", "processAudio transcription error", t)
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
        DebugLogManager.logInfo(
            "FloatingBubbleService",
            "FloatingBubbleService onDestroy() (explicitStop=$stoppedExplicitlyByUser)",
            includeMemory = true
        )
        _isRunning.value = false
        unregisterScreenStateReceiver()
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
        if (!stoppedExplicitlyByUser && shouldAutoStart(this)) {
            BootAndRestartReceiver.scheduleRestart(this, delayMs = 1_000L, reason = "onDestroy_unexpected")
        }
        super.onDestroy()
    }
}
