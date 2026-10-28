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
import android.os.SystemClock
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
import com.voicebubble.openflow.core.speech.PipelinePhase
import com.voicebubble.openflow.core.speech.PipelineStatus
import com.voicebubble.openflow.core.speech.SmartPipeline
import com.voicebubble.openflow.core.speech.StreamingDictationSession
import com.voicebubble.openflow.settings.DictationChunkSettings
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
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

        private const val LEGACY_CHANNEL_ID = "bubble_service_channel"
        private const val CHANNEL_ID = "bubble_service_channel_min"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CODE_RESTORE_NOTIFICATION = 1002
        private const val DICTATION_WAKE_LOCK_LEASE_MS = 15 * 60 * 1000L
        private const val SCREEN_WAKE_LOCK_LEASE_MS = 15 * 60 * 1000L
        private const val SCREEN_WAKE_LOCK_REFRESH_INTERVAL_MS = 60_000L

        private val SPACE_BEFORE_PUNCT_REGEX = Regex(" +([.,!?:;%€])")
        private val WHITESPACE_RUN_REGEX = Regex("\\s+")
        private val HORIZONTAL_WHITESPACE_RUN_REGEX = Regex("[ \\t]{2,}")
        private val TRAILING_LINE_SPACES_REGEX = Regex("[ \\t]+\\n")
        private val EXCESS_BLANK_LINES_REGEX = Regex("\\n{3,}")

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

        internal fun computeShouldShowBubble(
            isScreenInteractive: Boolean,
            showOnlyOnInputs: Boolean,
            isKeyboardOnScreen: Boolean,
            bubbleState: OverlayBubbleState,
            isDeviceLocked: Boolean
        ): Boolean {
            if (!isScreenInteractive) return false
            if (isKeyboardOnScreen || bubbleState != OverlayBubbleState.IDLE) return true
            if (!showOnlyOnInputs) return !isDeviceLocked
            return false
        }

        /**
         * Determines whether the display should be prevented from dimming or turning off.
         * Keeps the screen awake whenever the dictation bubble is visible on screen, while recording,
         * or while transcribing speech (`Ear` / `Voz` / `Punct`) or running an on-device LLM (`LiteRT-LM`).
         * Always returns `false` when the screen is already non-interactive so hardware Power-off is respected.
         */
        internal fun computeShouldKeepScreenOn(
            isScreenInteractive: Boolean,
            isBubbleVisible: Boolean,
            bubbleState: OverlayBubbleState,
            pipelinePhase: PipelinePhase,
            isPipelineActivelyProcessing: Boolean = false
        ): Boolean {
            if (!isScreenInteractive) return false
            return isBubbleVisible ||
                bubbleState != OverlayBubbleState.IDLE ||
                isPipelineActivelyProcessing ||
                pipelinePhase == PipelinePhase.TRANSCRIBING
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
    private var screenKeepAwakeWakeLock: PowerManager.WakeLock? = null
    private var lastScreenWakeLockRefreshMs: Long = 0L

    /** Active "transcribe while recording" session, or `null` when live transcription is off / not recording. */
    private var streamingSession: StreamingDictationSession? = null
    private val recordingCycleId = AtomicLong(0L)

    private val bubbleState = MutableStateFlow(OverlayBubbleState.IDLE)
    private val processingProgress = MutableStateFlow("")
    private val isScreenInteractive = MutableStateFlow(true)
    private val isBubbleVisibleFlow = MutableStateFlow(false)
    private val bubbleSizeFlow = MutableStateFlow(52f)
    private var hideDelayJob: Job? = null
    private var screenWakeRefreshJob: Job? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    onScreenTurnedOff()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    onScreenTurnedOn()
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

    private val settingsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (prefs == null || key == null) return@OnSharedPreferenceChangeListener
        when (key) {
            "bubble_size" -> {
                bubbleSizeFlow.value = prefs.getFloat("bubble_size", 52f).coerceIn(36f, 80f)
                clampBubblePositionToScreen()
            }
            KEY_SHOW_ONLY_ON_INPUTS, KEY_BUBBLE_DISAPPEAR_DELAY_MS -> {
                syncBubbleVisibility()
            }
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
        val interactive = checkScreenInteractive()
        isScreenInteractive.value = interactive
        TextInjectionAccessibilityService.isScreenInteractive.value = interactive
        smartPipeline.onScreenInteractiveChanged(interactive)

        val settingsPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bubbleSizeFlow.value = settingsPrefs.getFloat("bubble_size", 52f).coerceIn(36f, 80f)
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsPrefsListener)

        // Start foreground immediately in onCreate to satisfy Android 8-15 strict FGS timeout
        startCustomForeground(smartPipeline.pipelineStatus.value)

        registerScreenStateReceiver()
        setupOverlayView()
        syncBubbleVisibility()
        prepareStandbyPipeline()

        val modelPrefs = ModelSettings.getPreferences(this)
        modelPrefs.registerOnSharedPreferenceChangeListener(modelPrefsListener)

        // React immediately on Main thread when Accessibility IME state, connection, or bubble recording state changes
        serviceScope.launch {
            TextInjectionAccessibilityService.isInputFieldFocused.collect {
                syncBubbleVisibility()
            }
        }
        serviceScope.launch {
            TextInjectionAccessibilityService.isServiceConnected.collect {
                syncBubbleVisibility()
            }
        }
        serviceScope.launch {
            bubbleState.collect {
                syncBubbleVisibility()
                syncScreenKeepAwakeState()
            }
        }
        serviceScope.launch {
            smartPipeline.pipelineStatus.collect {
                syncBubbleVisibility()
                syncScreenKeepAwakeState()
            }
        }

        // Battery-optimized main-thread heartbeat (independent of Compose Choreographer VSYNC):
        // - Suspends completely (0 CPU wakeups) whenever the display is OFF; screen-on BroadcastReceiver wakes it immediately.
        // - While the display is ON, refreshes IME window visibility on the Main Looper (required by Samsung One UI /
        //   Android 14-15 AccessibilityService.getWindows()), keeps the screen-awake lock refreshed, and self-heals
        //   overlay attachment & foreground notification every 6 seconds.
        serviceScope.launch {
            var lastHealMs = SystemClock.elapsedRealtime()
            while (isActive) {
                if (!checkScreenInteractive()) {
                    if (isScreenInteractive.value || isBubbleVisibleFlow.value ||
                        screenKeepAwakeWakeLock?.isHeld == true ||
                        (::layoutParams.isInitialized && layoutParams.alpha > 0f)
                    ) {
                        onScreenTurnedOff()
                    }
                    // True 0% CPU sleep until ACTION_SCREEN_ON / ACTION_USER_PRESENT flips isScreenInteractive to true
                    isScreenInteractive.first { it }
                    continue
                }

                val heartbeatMs = when {
                    bubbleState.value != OverlayBubbleState.IDLE -> 300L
                    isBubbleVisibleFlow.value -> 550L
                    else -> 1_100L
                }
                delay(heartbeatMs)

                val stillInteractive = checkScreenInteractive()
                if (!stillInteractive) {
                    onScreenTurnedOff()
                    continue
                }

                if (!isScreenInteractive.value) {
                    onScreenTurnedOn()
                } else {
                    // Refresh IME window visibility on the Main Looper at an adaptive cadence so OEM ROMs
                    // (Samsung One UI, etc.) stay in sync while minimizing background IPC & battery drain.
                    TextInjectionAccessibilityService.getSharedInstance()?.refreshNow()
                    syncBubbleVisibility()
                    syncScreenKeepAwakeState()
                }

                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastHealMs >= 6_000L) {
                    lastHealMs = nowMs
                    ensureOverlayAttached()
                    ensureNotificationPresent()
                }
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

        val interactive = checkScreenInteractive()
        isScreenInteractive.value = interactive
        TextInjectionAccessibilityService.isScreenInteractive.value = interactive
        smartPipeline.onScreenInteractiveChanged(interactive)
        ensureOverlayAttached()
        syncBubbleVisibility()

        if (action == ACTION_REFRESH_SETTINGS) {
            clampBubblePositionToScreen()
            syncBubbleVisibility()
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

    private fun checkScreenInteractive(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Immediately hides the floating bubble surface when the screen turns off,
     * bypassing Compose VSYNC delays (since Choreographer stops ticking when the display is off).
     */
    private fun onScreenTurnedOff() {
        screenWakeRefreshJob?.cancel()
        screenWakeRefreshJob = null
        hideDelayJob?.cancel()
        hideDelayJob = null

        isScreenInteractive.value = false
        TextInjectionAccessibilityService.isScreenInteractive.value = false
        TextInjectionAccessibilityService.isInputFieldFocused.value = false
        isBubbleVisibleFlow.value = false

        if (bubbleState.value == OverlayBubbleState.RECORDING) {
            recordingCycleId.incrementAndGet()
            bubbleState.value = OverlayBubbleState.IDLE
            processingProgress.value = ""
            smartPipeline.onRecordingStopped()
            releaseDictationWakeLock()
            streamingSession?.cancel()
            streamingSession = null
            serviceScope.launch {
                try {
                    audioController.stopRecording()
                } catch (_: Throwable) {
                }
            }
        }

        // Release screen wake lock immediately so we never contest a manual hardware Power-button press
        releaseScreenWakeLock()

        // Synchronously hide WindowManager overlay surface and clear FLAG_KEEP_SCREEN_ON with 0ms delay
        setOverlayWindowVisibleAndTouchable(visible = false, touchable = false, forceUpdate = true)

        if (::smartPipeline.isInitialized) {
            smartPipeline.onScreenInteractiveChanged(false)
        }
    }

    /**
     * Restores interactive screen tracking when the screen turns on or the user unlocks the device,
     * and triggers immediate + staggered keyboard checks so the bubble appears as soon as an input is active.
     */
    private fun onScreenTurnedOn() {
        isScreenInteractive.value = true
        TextInjectionAccessibilityService.isScreenInteractive.value = true
        if (::smartPipeline.isInitialized) {
            smartPipeline.onScreenInteractiveChanged(true)
        }
        ensureOverlayAttached()
        TextInjectionAccessibilityService.getSharedInstance()?.refreshNow(force = true)
        syncBubbleVisibility()

        screenWakeRefreshJob?.cancel()
        screenWakeRefreshJob = serviceScope.launch {
            for (waitMs in longArrayOf(80L, 180L, 350L, 650L, 1_100L)) {
                delay(waitMs)
                if (!checkScreenInteractive()) break
                isScreenInteractive.value = true
                TextInjectionAccessibilityService.isScreenInteractive.value = true
                TextInjectionAccessibilityService.getSharedInstance()?.refreshNow(force = true)
                syncBubbleVisibility()
            }
        }
    }

    private fun shouldShowBubbleNow(): Boolean {
        val interactive = isScreenInteractive.value && checkScreenInteractive()
        if (!interactive) return false
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val showOnlyOnInputs = prefs.getBoolean(KEY_SHOW_ONLY_ON_INPUTS, true)
        val isKeyboardOnScreen = if (TextInjectionAccessibilityService.isServiceConnected.value) {
            TextInjectionAccessibilityService.isInputFieldFocused.value || checkWindowInsetsImeVisible()
        } else {
            checkWindowInsetsImeVisible()
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val deviceLocked = km?.isDeviceLocked ?: false
        return computeShouldShowBubble(
            isScreenInteractive = true,
            showOnlyOnInputs = showOnlyOnInputs,
            isKeyboardOnScreen = isKeyboardOnScreen,
            bubbleState = bubbleState.value,
            isDeviceLocked = deviceLocked
        )
    }

    /**
     * Authoritative main-thread visibility controller (runs on [Dispatchers.Main] via `Looper.getMainLooper()`,
     * never blocked by Compose `Choreographer` VSYNC suspension across screen-off/screen-on cycles).
     */
    private fun syncBubbleVisibility() {
        val interactive = checkScreenInteractive()
        if (!interactive || !isScreenInteractive.value) {
            hideDelayJob?.cancel()
            hideDelayJob = null
            isBubbleVisibleFlow.value = false
            setOverlayWindowVisibleAndTouchable(visible = false, touchable = false)
            return
        }

        if (shouldShowBubbleNow()) {
            hideDelayJob?.cancel()
            hideDelayJob = null
            ensureOverlayAttached()
            val wasHidden = !isBubbleVisibleFlow.value
            isBubbleVisibleFlow.value = true
            setOverlayWindowVisibleAndTouchable(
                visible = true,
                touchable = true,
                forceUpdate = wasHidden
            )
        } else {
            if (!isBubbleVisibleFlow.value) {
                if (hideDelayJob == null) {
                    setOverlayWindowVisibleAndTouchable(visible = false, touchable = false)
                }
            } else if (hideDelayJob == null) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val disappearDelayMs = prefs.getLong(
                    KEY_BUBBLE_DISAPPEAR_DELAY_MS,
                    DEFAULT_BUBBLE_DISAPPEAR_DELAY_MS
                ).coerceIn(0L, 5_000L)
                hideDelayJob = serviceScope.launch {
                    try {
                        if (disappearDelayMs > 0L) {
                            delay(disappearDelayMs)
                        }
                        if (shouldShowBubbleNow()) return@launch
                        isBubbleVisibleFlow.value = false
                        // Immediately disable touch interception while exit animation plays
                        setOverlayWindowVisibleAndTouchable(visible = true, touchable = false)
                        delay(160L)
                        if (!shouldShowBubbleNow()) {
                            setOverlayWindowVisibleAndTouchable(visible = false, touchable = false)
                        }
                    } finally {
                        hideDelayJob = null
                        syncScreenKeepAwakeState()
                    }
                }
            }
        }
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
        if ((level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) &&
            ::smartPipeline.isInitialized
        ) {
            smartPipeline.onTrimMemory(level)
        }
    }

    private var initPipelineJob: Job? = null

    /**
     * Keeps all speech and LLM models unloaded at 0 MB in RAM on service startup for maximum
     * background battery efficiency. Models are loaded lazily on demand when the bubble is tapped.
     */
    private fun prepareStandbyPipeline() {
        smartPipeline.refreshStatusSnapshot()
        val previousJob = initPipelineJob
        initPipelineJob = serviceScope.launch(Dispatchers.IO) {
            previousJob?.cancelAndJoin()
            try {
                val vozDownloaded = com.voicebubble.openflow.settings.DesertAntModelStorage.isModelDownloaded(
                    this@FloatingBubbleService,
                    com.voicebubble.openflow.settings.DesertAntModel.VOZ
                )
                if (!vozDownloaded) {
                    withContext(Dispatchers.Main) {
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
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            try {
                nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            } catch (_: Throwable) {
            }
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Dictation Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            nm.createNotificationChannel(channel)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenFlow Service Active")
            .setSmallIcon(com.voicebubble.openflow.R.drawable.ic_notification)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
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
        bubbleSizeFlow.value = prefs.getFloat("bubble_size", 52f).coerceIn(36f, 80f)
        val interactive = checkScreenInteractive()
        isScreenInteractive.value = interactive
        TextInjectionAccessibilityService.isScreenInteractive.value = interactive
        val initiallyVisible = shouldShowBubbleNow()
        isBubbleVisibleFlow.value = initiallyVisible
        val initiallyKeepScreenOn = shouldKeepScreenOnNow()

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
                (if (initiallyVisible) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                (if (initiallyKeepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = savedX
            y = savedY
            alpha = if (initiallyVisible) 1.0f else 0.0f
        }

        val themeContext = android.view.ContextThemeWrapper(this, com.voicebubble.openflow.R.style.Theme_OpenFlow)
        composeView = ComposeView(themeContext).apply {
            keepScreenOn = initiallyKeepScreenOn
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)

            setContent {
                OpenFlowTheme {
                    val currentState by bubbleState.collectAsState()
                    val isBubbleVisible by isBubbleVisibleFlow.collectAsState()
                    val bubbleSize by bubbleSizeFlow.collectAsState()
                    val pipelineStatus by smartPipeline.pipelineStatus.collectAsState()
                    val progressText by processingProgress.collectAsState()
                    val settingsPrefs = androidx.compose.runtime.remember {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    }

                    BubbleOverlayComponent(
                        audioController = audioController,
                        bubbleState = currentState,
                        isInputFieldFocused = isBubbleVisible,
                        processingProgress = progressText,
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
                            if (bubbleState.value == OverlayBubbleState.RECORDING ||
                                bubbleState.value == OverlayBubbleState.PROCESSING
                            ) {
                                return@BubbleOverlayComponent
                            }
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

                            val cycleId = recordingCycleId.incrementAndGet()
                            processingProgress.value = ""
                            bubbleState.value = OverlayBubbleState.RECORDING
                            if (settingsPrefs.getBoolean("haptic_feedback", true)) vibrate(20, 180)
                            acquireDictationWakeLock()
                            syncScreenKeepAwakeState(forceRefreshWakeLock = true)

                            // Ensure MICROPHONE foreground service type is active on user tap (if started on boot as SPECIAL_USE)
                            startCustomForeground(smartPipeline.pipelineStatus.value)

                            // Fast (<2ms, zero-BFS) pin of the currently focused editable field
                            TextInjectionAccessibilityService.getSharedInstance()?.captureTargetNodeForDictation()

                            // "Transcribe while recording": create the session now, start it before the mic so a very
                            // quick stop-tap can never race a not-yet-started session (poller just sees an empty buffer)
                            streamingSession?.cancel()
                            val chunkSettings = DictationChunkSettings.load(this@FloatingBubbleService)
                            val session = if (chunkSettings.liveActive) {
                                StreamingDictationSession(
                                    scope = serviceScope,
                                    audio = audioController,
                                    pipeline = smartPipeline,
                                    config = chunkSettings,
                                    onStageProgress = { stageLabel -> processingProgress.value = stageLabel }
                                ).also {
                                    streamingSession = it
                                    it.start()
                                }
                            } else {
                                streamingSession = null
                                null
                            }
                            serviceScope.launch {
                                val started = audioController.startRecording(serviceScope)
                                if (!started) {
                                    if (recordingCycleId.get() == cycleId) {
                                        smartPipeline.onRecordingStopped()
                                        session?.cancel()
                                        if (streamingSession === session) streamingSession = null
                                        bubbleState.value = OverlayBubbleState.IDLE
                                        releaseDictationWakeLock()
                                        syncBubbleVisibility()
                                        syncScreenKeepAwakeState()
                                    }
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            this@FloatingBubbleService,
                                            "Could not access microphone (may be in use by another app).",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else if (recordingCycleId.get() == cycleId &&
                                    bubbleState.value == OverlayBubbleState.RECORDING
                                ) {
                                    // Load Ear + Voz immediately once AudioRecord is actively capturing
                                    smartPipeline.onRecordingStartedAsync()
                                }
                            }
                        },
                        onStopRecording = {
                            if (bubbleState.value != OverlayBubbleState.RECORDING) {
                                return@BubbleOverlayComponent
                            }
                            val cycleId = recordingCycleId.incrementAndGet()
                            smartPipeline.onRecordingStopped()
                            acquireDictationWakeLock()
                            processingProgress.value = if (smartPipeline.isVozReady) {
                                "Transcribing..."
                            } else {
                                "Loading Voz..."
                            }
                            bubbleState.value = OverlayBubbleState.PROCESSING
                            // Keep the screen awake throughout Voz transcription and LLM post-processing!
                            syncScreenKeepAwakeState(forceRefreshWakeLock = true)
                            val session = streamingSession
                            streamingSession = null
                            serviceScope.launch {
                                if (session != null) {
                                    // Stop looking for new pauses first so the tail below is the very last piece
                                    session.stopPolling()
                                    val tail = audioController.stopRecording()
                                    processAudio(tail, session, cycleId)
                                } else {
                                    val samples = audioController.stopRecording()
                                    processAudio(samples, null, cycleId)
                                }
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(composeView, layoutParams)
            isOverlayAttached = true
            syncScreenKeepAwakeState()
        } catch (t: Throwable) {
            t.printStackTrace()
            isOverlayAttached = false
        }
    }

    private fun checkWindowInsetsImeVisible(): Boolean {
        if (!checkScreenInteractive()) return false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ::windowManager.isInitialized) {
                val metricsInsets = windowManager.currentWindowMetrics.windowInsets
                val minHeight = (64f * resources.displayMetrics.density).toInt().coerceAtLeast(64)
                val imeBottom = metricsInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                if (metricsInsets.isVisible(android.view.WindowInsets.Type.ime()) || imeBottom >= minHeight) {
                    return true
                }
            }
        } catch (_: Throwable) {
        }
        if (!::composeView.isInitialized || !composeView.isAttachedToWindow) return false
        return try {
            val rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(composeView) ?: return false
            val imeBottom = rootInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val minHeight = (64f * resources.displayMetrics.density).toInt().coerceAtLeast(64)
            rootInsets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) || imeBottom >= minHeight
        } catch (_: Throwable) {
            false
        }
    }

    private fun shouldKeepScreenOnNow(): Boolean {
        val interactive = isScreenInteractive.value && checkScreenInteractive()
        if (!interactive) return false
        val phase = if (::smartPipeline.isInitialized) {
            smartPipeline.pipelineStatus.value.phase
        } else {
            PipelinePhase.UNINITIALIZED
        }
        val activeProcessing = ::smartPipeline.isInitialized && smartPipeline.isActivelyProcessing
        return computeShouldKeepScreenOn(
            isScreenInteractive = true,
            isBubbleVisible = isBubbleVisibleFlow.value,
            bubbleState = bubbleState.value,
            pipelinePhase = phase,
            isPipelineActivelyProcessing = activeProcessing
        )
    }

    @Suppress("DEPRECATION")
    private fun acquireOrRefreshScreenWakeLock(forceRefresh: Boolean = false) {
        try {
            if (!checkScreenInteractive()) {
                releaseScreenWakeLock()
                return
            }
            val nowMs = SystemClock.elapsedRealtime()
            val existing = screenKeepAwakeWakeLock
            if (existing?.isHeld == true &&
                !forceRefresh &&
                nowMs - lastScreenWakeLockRefreshMs < SCREEN_WAKE_LOCK_REFRESH_INTERVAL_MS
            ) {
                return
            }
            val lock = existing ?: run {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                // SCREEN_BRIGHT_WAKE_LOCK (without ACQUIRE_CAUSES_WAKEUP) keeps an already-on display
                // from dimming/sleeping even on OEM ROMs that ignore FLAG_KEEP_SCREEN_ON on FLAG_NOT_FOCUSABLE
                // overlays, while ON_AFTER_RELEASE resets the normal user-activity timeout upon release.
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "OpenFlow::BubbleScreenAwakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            lock.acquire(SCREEN_WAKE_LOCK_LEASE_MS)
            screenKeepAwakeWakeLock = lock
            lastScreenWakeLockRefreshMs = nowMs
        } catch (_: Throwable) {
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            screenKeepAwakeWakeLock?.let {
                if (it.isHeld) it.release()
            }
            screenKeepAwakeWakeLock = null
            lastScreenWakeLockRefreshMs = 0L
        } catch (_: Throwable) {
        }
    }

    /**
     * Synchronizes both WindowManager `FLAG_KEEP_SCREEN_ON` / `View.keepScreenOn` AND
     * [screenKeepAwakeWakeLock] so the display never turns off while the bubble is visible,
     * recording, transcribing (`Ear` / `Voz` / `Punct`), or running an on-device LLM.
     */
    private fun syncScreenKeepAwakeState(forceRefreshWakeLock: Boolean = false) {
        val keepAwake = shouldKeepScreenOnNow()
        if (keepAwake) {
            acquireOrRefreshScreenWakeLock(forceRefresh = forceRefreshWakeLock)
        } else {
            releaseScreenWakeLock()
        }

        if (!::layoutParams.isInitialized || !::composeView.isInitialized) return
        if (composeView.keepScreenOn != keepAwake) {
            composeView.keepScreenOn = keepAwake
        }
        val desiredFlags = if (keepAwake) {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        if (desiredFlags != layoutParams.flags) {
            layoutParams.flags = desiredFlags
            try {
                if (composeView.isAttachedToWindow) {
                    windowManager.updateViewLayout(composeView, layoutParams)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun setOverlayWindowVisibleAndTouchable(
        visible: Boolean,
        touchable: Boolean,
        forceUpdate: Boolean = false
    ) {
        val keepAwake = shouldKeepScreenOnNow()
        if (keepAwake) {
            acquireOrRefreshScreenWakeLock()
        } else {
            releaseScreenWakeLock()
        }
        if (!::layoutParams.isInitialized || !::composeView.isInitialized) return
        if (composeView.keepScreenOn != keepAwake) {
            composeView.keepScreenOn = keepAwake
        }
        val touchFlags = if (touchable) {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val newFlags = if (keepAwake) {
            touchFlags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            touchFlags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        val newAlpha = if (visible) 1.0f else 0.0f
        val changed = newFlags != layoutParams.flags || layoutParams.alpha != newAlpha
        if (!changed && !forceUpdate) return
        layoutParams.flags = newFlags
        layoutParams.alpha = newAlpha
        try {
            if (visible && composeView.visibility != android.view.View.VISIBLE) {
                composeView.visibility = android.view.View.VISIBLE
            }
            if (composeView.isAttachedToWindow) {
                windowManager.updateViewLayout(composeView, layoutParams)
                if (visible) {
                    composeView.requestLayout()
                    composeView.invalidate()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun acquireDictationWakeLock() {
        try {
            val lock = dictationWakeLock ?: run {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OpenFlow::DictationWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            // Refresh lease so long live-streaming dictations + final LLM post-processing never time out
            lock.acquire(DICTATION_WAKE_LOCK_LEASE_MS)
            dictationWakeLock = lock
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

    private fun copyTextToClipboardSafely(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("OpenFlow", text))
        } catch (t: Throwable) {
            DebugLogManager.logWarn("FloatingBubbleService", "Clipboard copy failed: ${t.message}")
        }
    }

    /**
     * Transcribes and injects the dictation. With a live [session] the earlier chunks were already transcribed
     * while recording, so [samples] is just the final tail and only that remains to process.
     */
    private fun processAudio(
        samples: FloatArray,
        session: StreamingDictationSession? = null,
        cycleId: Long = recordingCycleId.get()
    ) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        serviceScope.launch {
            try {
                if (processingProgress.value.isEmpty()) {
                    processingProgress.value = if (smartPipeline.isVozReady) {
                        "Transcribing..."
                    } else {
                        "Loading Voz..."
                    }
                }

                val chunkText = if (session != null) {
                    session.finish(samples).also {
                        if (session.failedChunkCount > 0) {
                            if (it.isBlank()) {
                                throw IllegalStateException("${session.failedChunkCount} audio segment(s) could not be transcribed")
                            }
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    this@FloatingBubbleService,
                                    "${session.failedChunkCount} segment(s) could not be transcribed",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else {
                    smartPipeline.transcribe(
                        samples = samples,
                        onStageProgress = { stageLabel ->
                            processingProgress.value = stageLabel
                        }
                    )
                }
                val totalSamples = session?.totalSamples ?: samples.size.toLong()

                val activeModelSettings = smartPipeline.currentSettings()
                val normalizedBase = chunkText.trim()
                    .replace(SPACE_BEFORE_PUNCT_REGEX, "$1")
                    .replace("O K", "OK")
                    .replace("o k", "ok")
                val finalText = if (activeModelSettings.gemmaEnabled) {
                    normalizedBase
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .replace(HORIZONTAL_WHITESPACE_RUN_REGEX, " ")
                        .replace(TRAILING_LINE_SPACES_REGEX, "\n")
                        .replace(EXCESS_BLANK_LINES_REGEX, "\n\n")
                        .trim()
                } else {
                    normalizedBase.replace(WHITESPACE_RUN_REGEX, " ")
                }

                if (finalText.isBlank()) {
                    DebugLogManager.logInfo(
                        "FloatingBubbleService",
                        "No speech detected in captured audio (samples=$totalSamples)"
                    )
                    if (recordingCycleId.get() == cycleId) {
                        processingProgress.value = ""
                        bubbleState.value = OverlayBubbleState.IDLE
                    }
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@FloatingBubbleService,
                            "No speech detected",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                // Always guarantee the transcribed text is placed on the clipboard as a reliable safety net
                copyTextToClipboardSafely(finalText)

                val autoPasteEnabled = prefs.getBoolean("auto_paste", true)
                val accessibilityService = TextInjectionAccessibilityService.getSharedInstance()
                val injected = if (autoPasteEnabled && accessibilityService != null) {
                    accessibilityService.injectTextSuspend(finalText)
                } else {
                    false
                }

                if (!injected) {
                    copyTextToClipboardSafely(finalText)
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
                    val modelId = buildString {
                        append("desert-ant-voz-4.0.0")
                        if (activeModelSettings.punctEnabled && !activeModelSettings.gemmaEnabled) append("+punct")
                        if (activeModelSettings.gemmaEnabled) append("+${activeModelSettings.selectedLlmModel.id}")
                    }
                    val dao = AppDatabase.getDatabase(this@FloatingBubbleService).transcriptionDao()
                    dao.insert(
                        TranscriptionRecord(
                            transcribedText = finalText,
                            durationMs = totalSamples / 16L,
                            audioSamplesCount = totalSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            modelIdentifier = modelId,
                            wasInjectedSuccessfully = injected
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (recordingCycleId.get() == cycleId) {
                    bubbleState.value = OverlayBubbleState.SUCCESS
                    delay(1200)
                    if (recordingCycleId.get() == cycleId) {
                        processingProgress.value = ""
                        bubbleState.value = OverlayBubbleState.IDLE
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                DebugLogManager.logError("FloatingBubbleService", "processAudio transcription error", t)
                if (recordingCycleId.get() == cycleId) {
                    processingProgress.value = ""
                    bubbleState.value = OverlayBubbleState.IDLE
                }
                val errorDetail = if (t is LinkageError) {
                    "${t.javaClass.simpleName}: ${t.message ?: "initialization error"}"
                } else {
                    t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@FloatingBubbleService,
                        "Transcription error: $errorDetail",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                if (recordingCycleId.get() == cycleId) {
                    releaseDictationWakeLock()
                }
                syncBubbleVisibility()
                syncScreenKeepAwakeState()
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
        streamingSession?.cancel()
        streamingSession = null
        unregisterScreenStateReceiver()
        releaseScreenWakeLock()
        releaseDictationWakeLock()
        try {
            val modelPrefs = ModelSettings.getPreferences(this)
            modelPrefs.unregisterOnSharedPreferenceChangeListener(modelPrefsListener)
        } catch (_: Throwable) {
        }
        try {
            val settingsPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsPrefsListener)
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
