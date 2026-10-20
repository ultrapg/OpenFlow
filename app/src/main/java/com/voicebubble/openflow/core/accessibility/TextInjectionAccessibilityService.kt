package com.voicebubble.openflow.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
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
    private var lastWatchdogCheckMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        isServiceConnected.value = true

        // Automatically start the floating bubble service on phone boot or after process crash recovery
        FloatingBubbleService.ensureRunningIfEnabled(this, reason = "accessibility_onServiceConnected")

        startPollingLoop()
    }

    private fun startPollingLoop() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                refreshKeyboardVisibilityState()
                val now = SystemClock.elapsedRealtime()
                if (now - lastWatchdogCheckMs >= 8_000L) {
                    lastWatchdogCheckMs = now
                    if (!FloatingBubbleService.isRunning.value) {
                        FloatingBubbleService.ensureRunningIfEnabled(
                            this@TextInjectionAccessibilityService,
                            reason = "accessibility_watchdog"
                        )
                    }
                }
                delay(180L)
            }
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
        instance = null
        isServiceConnected.value = false
        isInputFieldFocused.value = false
        pollingJob?.cancel()
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            refreshKeyboardVisibilityState()
        }
    }

    override fun onInterrupt() {}

    /**
     * Strictly checks whether the on-screen soft keyboard (`TYPE_INPUT_METHOD` window) is currently
     * visible on screen while the screen is interactive and unlocked.
     */
    private fun refreshKeyboardVisibilityState() {
        try {
            isInputFieldFocused.value = isSoftKeyboardWindowVisible()
        } catch (_: Throwable) {
        }
    }

    private fun isSoftKeyboardWindowVisible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            if (!isScreenInteractive.value) return false
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) return false
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

            val allWindows = windows ?: return false
            val dm = resources.displayMetrics
            val screenHeight = dm.heightPixels
            val screenWidth = dm.widthPixels
            // A real on-screen keyboard is at least 110dp tall and spans most of the screen width
            val minKeyboardHeightPx = (110f * dm.density).toInt().coerceAtLeast(110)
            val minKeyboardWidthPx = (screenWidth / 2).coerceAtLeast(120)
            var imeVisible = false
            val bounds = Rect()

            for (window in allWindows) {
                try {
                    if (!imeVisible && window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        window.getBoundsInScreen(bounds)
                        val hasValidGeometry = bounds.height() >= minKeyboardHeightPx &&
                            bounds.width() >= minKeyboardWidthPx &&
                            bounds.bottom > (screenHeight * 2 / 3)

                        if (hasValidGeometry) {
                            val imeRoot = try {
                                window.root
                            } catch (_: Throwable) {
                                null
                            }
                            val rootVisible = if (imeRoot != null) {
                                val vis = imeRoot.isVisibleToUser
                                try {
                                    @Suppress("DEPRECATION")
                                    imeRoot.recycle()
                                } catch (_: Throwable) {
                                }
                                vis
                            } else {
                                true
                            }
                            if (rootVisible) {
                                imeVisible = true
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try {
                        @Suppress("DEPRECATION")
                        window.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }
            imeVisible
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
                        val root = window.root
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
                    try {
                        @Suppress("DEPRECATION")
                        window.recycle()
                    } catch (_: Throwable) {
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
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlow", textToInsert))
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
                }
            } catch (_: Throwable) {
            }
        }
        injected
    }
}
