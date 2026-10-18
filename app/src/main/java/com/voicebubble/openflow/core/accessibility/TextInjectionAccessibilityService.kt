package com.voicebubble.openflow.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        private const val FOCUS_GRACE_MS = 2_000L
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
    private var lastEditableFocusSeenMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        isServiceConnected.value = true

        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                refreshFocusState()
                delay(450L)
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
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            val source = event.source
            if (source != null) {
                try {
                    if (source.isEditable && source.isFocused) {
                        lastEditableFocusSeenMs = SystemClock.elapsedRealtime()
                        isInputFieldFocused.value = true
                    }
                } catch (_: Throwable) {
                } finally {
                    try {
                        @Suppress("DEPRECATION")
                        source.recycle()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun refreshFocusState() {
        try {
            val editableNode = findFocusedEditableNode()
            val now = SystemClock.elapsedRealtime()
            if (editableNode != null) {
                try {
                    @Suppress("DEPRECATION")
                    editableNode.recycle()
                } catch (_: Throwable) {
                }
                lastEditableFocusSeenMs = now
                isInputFieldFocused.value = true
            } else {
                // Keep focus true during brief 2s window/IME transitions when tapping the overlay bubble
                val withinGrace = (now - lastEditableFocusSeenMs) <= FOCUS_GRACE_MS
                isInputFieldFocused.value = withinGrace
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Searches `rootInActiveWindow` first, then falls back to all interactive application windows
     * so focus is still found even when the floating overlay window was just tapped.
     */
    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && focused.isEditable) {
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
                var found: AccessibilityNodeInfo? = null
                for (window in allWindows) {
                    if (found == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val root = window.root
                        if (root != null) {
                            val candidate = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            if (candidate != null && candidate.isEditable) {
                                found = candidate
                            } else {
                                try {
                                    @Suppress("DEPRECATION")
                                    candidate?.recycle()
                                } catch (_: Throwable) {
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
                return found
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

        val focusedNode = findFocusedEditableNode()
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
            restoreClipboard(clipboard, oldClip)
        }
        injected
    }

    fun injectTextDirectly(textToInsert: String): Boolean {
        serviceScope.launch {
            injectTextSuspend(textToInsert)
        }
        return true
    }

    private fun restoreClipboard(clipboard: ClipboardManager, oldClip: ClipData?) {
        try {
            if (oldClip != null) {
                clipboard.setPrimaryClip(oldClip)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        } catch (_: Throwable) {
        }
    }
}
