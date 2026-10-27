package com.voicebubble.openflow.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.voicebubble.openflow.overlay.FloatingBubbleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class TextInjectionAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: WeakReference<TextInjectionAccessibilityService>? = null

        /**
         * Emits `true` strictly when the on-screen soft keyboard (`TYPE_INPUT_METHOD`) is visible
         * on screen, and `false` immediately when the keyboard is hidden.
         * Any disappear delay is applied downstream in `FloatingBubbleService` based on user settings.
         */
        val isInputFieldFocused = MutableStateFlow(false)
        val isServiceConnected = MutableStateFlow(false)
        val isScreenInteractive = MutableStateFlow(true)

        fun getSharedInstance(): TextInjectionAccessibilityService? {
            return instance?.get()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null
    private var postEventCheckJob: Job? = null
    private var lastWatchdogCheckMs = 0L
    @Volatile
    private var lastKeyboardCheckMs = 0L
    private var screenReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    postEventCheckJob?.cancel()
                    isScreenInteractive.value = false
                    isInputFieldFocused.value = false
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive.value = true
                    FloatingBubbleService.ensureRunningIfEnabled(
                        this@TextInjectionAccessibilityService,
                        reason = "accessibility_screen_on"
                    )
                    schedulePostEventKeyboardCheck(delayMs = 120L)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        isServiceConnected.value = true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenInteractive.value = try { pm?.isInteractive ?: true } catch (_: Throwable) { true }
        registerScreenReceiver()

        // Automatically start the floating bubble service on phone boot or after process crash recovery
        FloatingBubbleService.ensureRunningIfEnabled(this, reason = "accessibility_onServiceConnected")

        startPollingLoop()
    }

    private fun registerScreenReceiver() {
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

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Throwable) {
        }
        screenReceiverRegistered = false
    }

    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isActive) {
                val interactiveNow = try {
                    powerManager?.isInteractive ?: true
                } catch (_: Throwable) {
                    true
                }
                if (!interactiveNow) {
                    isScreenInteractive.value = false
                    isInputFieldFocused.value = false
                    // Suspend completely (0% CPU / 0 wakeups) while the display is off
                    isScreenInteractive.first { it }
                    continue
                }

                if (!isScreenInteractive.value) {
                    isScreenInteractive.value = true
                }
                refreshKeyboardVisibilityState()
                val now = SystemClock.elapsedRealtime()
                if (now - lastWatchdogCheckMs >= 15_000L) {
                    lastWatchdogCheckMs = now
                    if (!FloatingBubbleService.isRunning.value) {
                        FloatingBubbleService.ensureRunningIfEnabled(
                            this@TextInjectionAccessibilityService,
                            reason = "accessibility_watchdog"
                        )
                    }
                }
                // Event-driven AccessibilityEvents handle instant keyboard transitions; this loop is only a
                // low-power safety net (1.2s while keyboard is open, 3.5s while closed).
                delay(if (isInputFieldFocused.value) 1_200L else 3_500L)
            }
        }
    }

    /**
     * Restarts the keyboard-visibility poller only if it died; unlike [refreshNow] it never touches
     * the window list, so it is safe to call frequently from the main thread.
     */
    fun ensurePollingActive() {
        if (pollingJob?.isActive != true) {
            startPollingLoop()
        }
    }

    fun refreshNow() {
        if (pollingJob?.isActive != true) {
            startPollingLoop()
        }
        refreshKeyboardVisibilityState()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterScreenReceiver()
        instance = null
        isServiceConnected.value = false
        isInputFieldFocused.value = false
        postEventCheckJob?.cancel()
        pollingJob?.cancel()
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isScreenInteractive.value) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                refreshKeyboardVisibilityState()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                refreshKeyboardVisibilityState()
                // Catch soft-keyboard slide-in animations that finish ~150ms after focus/tap
                schedulePostEventKeyboardCheck(delayMs = 180L)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Avoid traversing windows on every single keystroke when keyboard is already known to be open
                val elapsed = SystemClock.elapsedRealtime() - lastKeyboardCheckMs
                if (!isInputFieldFocused.value || elapsed >= 750L) {
                    refreshKeyboardVisibilityState()
                }
            }
        }
    }

    private fun schedulePostEventKeyboardCheck(delayMs: Long) {
        postEventCheckJob?.cancel()
        postEventCheckJob = serviceScope.launch {
            delay(delayMs)
            if (isScreenInteractive.value) {
                refreshKeyboardVisibilityState()
            }
        }
    }

    override fun onInterrupt() {}

    /**
     * Strictly checks whether the on-screen soft keyboard (`TYPE_INPUT_METHOD` window) is currently
     * visible on screen while the screen is interactive and unlocked.
     */
    private fun refreshKeyboardVisibilityState() {
        try {
            lastKeyboardCheckMs = SystemClock.elapsedRealtime()
            isInputFieldFocused.value = isSoftKeyboardWindowVisible()
        } catch (_: Throwable) {
        }
    }

    private fun isSoftKeyboardWindowVisible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val interactive = pm?.isInteractive ?: isScreenInteractive.value
            if (isScreenInteractive.value != interactive) {
                isScreenInteractive.value = interactive
            }
            if (!interactive) return false

            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km != null && km.isDeviceLocked) {
                val focusedOnLockscreen = findFocusedEditableNode(requireEditableFlag = false)
                if (focusedOnLockscreen == null) {
                    return false
                }
                try {
                    @Suppress("DEPRECATION")
                    focusedOnLockscreen.recycle()
                } catch (_: Throwable) {
                }
            }

            val dm = resources.displayMetrics
            val screenHeight = dm.heightPixels
            val screenWidth = dm.widthPixels
            // Allow standard, compact, one-handed, and split/floating keyboards (>= 64dp height, >= 1/3 width)
            val minKeyboardHeightPx = (64f * dm.density).toInt().coerceAtLeast(64)
            val minKeyboardWidthPx = (screenWidth / 3).coerceAtLeast(100)

            // Signal 1: Android 11+ (API 30+) WindowMetrics IME insets check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    val metricsInsets = wm?.currentWindowMetrics?.windowInsets
                    if (metricsInsets != null) {
                        val imeInsets = metricsInsets.getInsets(WindowInsets.Type.ime())
                        if (metricsInsets.isVisible(WindowInsets.Type.ime()) || imeInsets.bottom >= minKeyboardHeightPx) {
                            return true
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // Signal 2: Accessibility interactive windows list (TYPE_INPUT_METHOD)
            // IMPORTANT: Never call window.recycle() on AccessibilityWindowInfo instances returned by `windows`!
            // `AccessibilityService.getWindows()` returns cached instances from AccessibilityCache; recycling them
            // resets `window.type = -1` and `bounds = (0,0,0,0)` in the cache, making the keyboard invisible to
            // all subsequent polls while it stays open.
            val allWindows = try {
                windows
            } catch (_: Throwable) {
                null
            }

            if (!allWindows.isNullOrEmpty()) {
                val bounds = Rect()
                for (window in allWindows) {
                    try {
                        if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                            window.getBoundsInScreen(bounds)
                            val hasValidGeometry = bounds.height() >= minKeyboardHeightPx &&
                                bounds.width() >= minKeyboardWidthPx &&
                                bounds.bottom > (screenHeight / 2)
                            if (hasValidGeometry) {
                                return true
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
                return false
            }

            // Fallback when `windows` is unavailable/empty on specific OEM ROMs: check if an editable input node holds focus
            val focusedEditable = findFocusedEditableNode(requireEditableFlag = true)
            if (focusedEditable != null) {
                try {
                    @Suppress("DEPRECATION")
                    focusedEditable.recycle()
                } catch (_: Throwable) {
                }
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Searches `rootInActiveWindow` first, then falls back to all interactive application windows
     * so the target input node is still found even when the floating overlay window was just tapped.
     * If [requireEditableFlag] is false (used during text injection), falls back to any node holding
     * `FOCUS_INPUT` so WebViews and custom Compose fields can also receive `ACTION_PASTE`.
     */
    private fun findFocusedEditableNode(requireEditableFlag: Boolean = false): AccessibilityNodeInfo? {
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && (focused.isEditable || !requireEditableFlag)) {
                    try {
                        @Suppress("DEPRECATION")
                        activeRoot.recycle()
                    } catch (_: Throwable) {
                    }
                    return focused
                }
                try {
                    @Suppress("DEPRECATION")
                    focused?.recycle()
                    @Suppress("DEPRECATION")
                    activeRoot.recycle()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val allWindows = windows ?: return null
                var editableCandidate: AccessibilityNodeInfo? = null
                var fallbackInputCandidate: AccessibilityNodeInfo? = null

                for (window in allWindows) {
                    if (editableCandidate == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = try {
                            window.root
                        } catch (_: Throwable) {
                            null
                        }
                        if (root != null) {
                            val candidate = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            if (candidate != null) {
                                if (candidate.isEditable) {
                                    editableCandidate = candidate
                                } else if (!requireEditableFlag && fallbackInputCandidate == null) {
                                    fallbackInputCandidate = candidate
                                } else {
                                    try {
                                        @Suppress("DEPRECATION")
                                        candidate.recycle()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                            try {
                                @Suppress("DEPRECATION")
                                root.recycle()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }

                if (editableCandidate != null) {
                    try {
                        @Suppress("DEPRECATION")
                        fallbackInputCandidate?.recycle()
                    } catch (_: Throwable) {
                    }
                    return editableCandidate
                }
                return fallbackInputCandidate
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Injects [textToInsert] into the currently focused editable field and returns `true` only if
     * the text was actually pasted or set into an editable node. If no editable node is available,
     * leaves [textToInsert] on the clipboard and returns `false` so the user never loses dictated text.
     */
    suspend fun injectTextSuspend(textToInsert: String): Boolean = withContext(Dispatchers.Main) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = try {
            clipboard.primaryClip
        } catch (_: Throwable) {
            null
        }

        try {
            val clip = ClipData.newPlainText("OpenFlow", textToInsert)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Hide the dictated text from the Android 13+ clipboard preview / keyboard clipboard suggestions
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clipboard.setPrimaryClip(clip)
        } catch (_: Throwable) {
        }

        delay(80L)

        val focusedNode = findFocusedEditableNode(requireEditableFlag = false)
            ?: return@withContext false // Keep textToInsert in clipboard!

        var injected = false
        try {
            injected = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!injected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val currentText = focusedNode.text?.toString() ?: ""
                val start = focusedNode.textSelectionStart
                val end = focusedNode.textSelectionEnd

                val newText = if (start in 0..currentText.length && end in 0..currentText.length) {
                    val realStart = minOf(start, end)
                    val realEnd = maxOf(start, end)
                    currentText.substring(0, realStart) + textToInsert + currentText.substring(realEnd)
                } else {
                    if (currentText.isNotEmpty()) "$currentText $textToInsert" else textToInsert
                }

                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                injected = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

                if (injected) {
                    val newCursorPos = if (start in 0..currentText.length) {
                        minOf(start, end) + textToInsert.length
                    } else {
                        newText.length
                    }
                    val selectionArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                    }
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                }
            }
        } catch (_: Throwable) {
            injected = false
        } finally {
            try {
                @Suppress("DEPRECATION")
                focusedNode.recycle()
            } catch (_: Throwable) {
            }
        }

        if (injected) {
            delay(250L)
            try {
                if (oldClip != null) {
                    clipboard.setPrimaryClip(oldClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // The previous clipboard could not be read (background-app restriction on Android 10+).
                    // Never leave the already-pasted dictation sitting in the clipboard / clipboard history.
                    clipboard.clearPrimaryClip()
                }
            } catch (_: Throwable) {
            }
        }
        injected
    }
}
