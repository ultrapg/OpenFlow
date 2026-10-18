package com.voicebubble.openflow.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        /**
         * Short grace window so tapping the floating overlay bubble does not cause a momentary
         * hide/flicker if the system briefly transitions window focus.
         */
        private const val IME_GRACE_MS = 650L
        private var instance: WeakReference<TextInjectionAccessibilityService>? = null
        val isInputFieldFocused = MutableStateFlow(false)
        val isServiceConnected = MutableStateFlow(false)

        fun getSharedInstance(): TextInjectionAccessibilityService? {
            return instance?.get()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null

    @Volatile
    private var lastActiveImeOrInputSeenMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        isServiceConnected.value = true

        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                refreshFocusState()
                delay(250L)
            }
        }
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
            refreshFocusState()
        }
    }

    override fun onInterrupt() {}

    /**
     * Determines whether the user is actively typing by checking:
     * 1. Primary signal: Is the on-screen soft keyboard (`TYPE_INPUT_METHOD` window) currently visible on screen?
     *    This works reliably across 100% of apps (native EditText, Jetpack Compose, Chrome/WebViews, Flutter, etc.)
     *    and immediately becomes false when the user closes the keyboard.
     * 2. Secondary signal (for physical hardware keyboards where no soft IME is shown):
     *    Is an editable input node focused in the active application window?
     */
    private fun refreshFocusState() {
        try {
            val now = SystemClock.elapsedRealtime()
            val imeVisible = isSoftKeyboardWindowVisible()
            val hardwareKeyboardActive = isHardwareKeyboardConnected() && hasFocusedEditableNode()

            if (imeVisible || hardwareKeyboardActive) {
                lastActiveImeOrInputSeenMs = now
                isInputFieldFocused.value = true
            } else {
                val withinGrace = (now - lastActiveImeOrInputSeenMs) <= IME_GRACE_MS
                isInputFieldFocused.value = withinGrace
            }
        } catch (_: Throwable) {
        }
    }

    private fun isSoftKeyboardWindowVisible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return hasFocusedEditableNode()
        return try {
            val allWindows = windows ?: return false
            val screenHeight = resources.displayMetrics.heightPixels
            val minKeyboardHeightPx = (48f * resources.displayMetrics.density).toInt().coerceAtLeast(48)
            var imeVisible = false
            val bounds = Rect()

            for (window in allWindows) {
                try {
                    if (!imeVisible && window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        window.getBoundsInScreen(bounds)
                        if (bounds.height() >= minKeyboardHeightPx && bounds.bottom > (screenHeight / 3)) {
                            imeVisible = true
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

    private fun isHardwareKeyboardConnected(): Boolean {
        return try {
            val config = resources.configuration
            config.keyboard != Configuration.KEYBOARD_NOKEYS &&
                config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasFocusedEditableNode(): Boolean {
        val node = findFocusedEditableNode(requireEditableFlag = true)
        if (node != null) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Throwable) {
            }
            return true
        }
        return false
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
