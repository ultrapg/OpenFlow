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
import android.text.InputType
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.voicebubble.openflow.core.debug.DebugLogManager
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
import java.util.ArrayDeque

class TextInjectionAccessibilityService : AccessibilityService() {

    enum class TextInjectionResult {
        INJECTED,
        COPIED_TO_CLIPBOARD_ONLY,
        NOT_INJECTED_NO_CLIPBOARD
    }

    data class SplicedTextResult(
        val newText: String,
        val newCursorPosition: Int
    )

    companion object {
        private const val TAG = "TextInjectionA11y"
        private const val MAX_DRILL_NODES = 192
        private const val MAX_BFS_NODES = 320
        private const val MAX_PINNED_NODE_AGE_MS = 10 * 60_000L

        private val NO_SPACE_AFTER_CHARS = charArrayOf(
            '(', '[', '{', '"', '“', '‘', '/', '@', '#', '$', '-', '–', '—', '\n', '\r', '\t'
        )
        private val NO_SPACE_BEFORE_CHARS = charArrayOf(
            '.', ',', '!', '?', ';', ':', ')', ']', '}', '…', '\n', '\r', '\t'
        )
        private val NO_TRAILING_SPACE_BEFORE_CHARS = charArrayOf(
            '.', ',', '!', '?', ';', ':', ')', ']', '}', '…', '\'', '’', '"', '”', '\n', '\r', '\t'
        )

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

        /**
         * Pure helper for splicing [textToInsert] into [currentText] at `[selectionStart, selectionEnd]`
         * with smart boundary spacing and accurate post-insertion cursor placement.
         */
        internal fun spliceTextForSetText(
            currentText: String,
            selectionStart: Int,
            selectionEnd: Int,
            textToInsert: String
        ): SplicedTextResult {
            if (textToInsert.isEmpty()) {
                val safePos = selectionEnd.coerceIn(0, currentText.length)
                return SplicedTextResult(currentText, safePos)
            }
            if (currentText.isEmpty()) {
                return SplicedTextResult(textToInsert, textToInsert.length)
            }

            if (selectionStart in 0..currentText.length && selectionEnd in 0..currentText.length) {
                val realStart = minOf(selectionStart, selectionEnd)
                val realEnd = maxOf(selectionStart, selectionEnd)
                val before = currentText.substring(0, realStart)
                val after = currentText.substring(realEnd)

                val needLeadingSpace = before.isNotEmpty() &&
                    !before.last().isWhitespace() &&
                    before.last() !in NO_SPACE_AFTER_CHARS &&
                    !textToInsert.first().isWhitespace() &&
                    textToInsert.first() !in NO_SPACE_BEFORE_CHARS

                val needTrailingSpace = after.isNotEmpty() &&
                    !after.first().isWhitespace() &&
                    after.first() !in NO_TRAILING_SPACE_BEFORE_CHARS &&
                    !textToInsert.last().isWhitespace()

                val prefix = if (needLeadingSpace) " " else ""
                val suffix = if (needTrailingSpace) " " else ""
                val newText = before + prefix + textToInsert + suffix + after
                val newCursor = before.length + prefix.length + textToInsert.length
                return SplicedTextResult(newText, newCursor)
            }

            val needSpace = !currentText.last().isWhitespace() &&
                currentText.last() !in NO_SPACE_AFTER_CHARS &&
                !textToInsert.first().isWhitespace() &&
                textToInsert.first() !in NO_SPACE_BEFORE_CHARS
            val appended = if (needSpace) "$currentText $textToInsert" else "$currentText$textToInsert"
            return SplicedTextResult(appended, appended.length)
        }

        /**
         * Strips disguised placeholder, hint, label, and Material `TextInputLayout` / Compose `DecorationBox`
         * decoration strings from `AccessibilityNodeInfo.getText()` so screen-reader text is never mistaken
         * for user-typed editable content.
         */
        internal fun sanitizeEditableNodeText(
            rawText: String,
            isShowingHintText: Boolean,
            isPassword: Boolean,
            selectionStart: Int,
            selectionEnd: Int,
            hintText: String? = null,
            contentDescription: String? = null,
            tooltipText: String? = null,
            paneTitle: String? = null,
            nonEditableChildTexts: List<String> = emptyList()
        ): String {
            if (isPassword || isShowingHintText || rawText.isEmpty()) {
                return ""
            }

            // Focused editable buffers (`EditText`, Compose `TextField`, `<input>`/`<textarea>`) maintain a
            // non-negative selection span (`0..length`). When both selection bounds are negative (`-1`), `rawText`
            // comes from a static/placeholder/container accessibility property rather than an active editable buffer.
            if (selectionStart < 0 && selectionEnd < 0) {
                return ""
            }

            val rawTrimmed = rawText.trim()
            if (rawTrimmed.isEmpty()) {
                return rawText
            }

            val caretAtStartOrEmpty = selectionStart <= 0 && selectionEnd <= 0
            if (caretAtStartOrEmpty) {
                val metadataCandidates = listOfNotNull(hintText, contentDescription, tooltipText, paneTitle)
                for (candidate in metadataCandidates) {
                    val clean = candidate.trim()
                    if (clean.isEmpty()) continue
                    if (rawTrimmed.equals(clean, ignoreCase = true)) {
                        return ""
                    }
                    // Material TextInputLayout empty-field combined format ("Label, Placeholder" / "Placeholder, Label")
                    if (rawTrimmed.startsWith("$clean, ", ignoreCase = true) ||
                        rawTrimmed.endsWith(", $clean", ignoreCase = true)
                    ) {
                        return ""
                    }
                }

                if (nonEditableChildTexts.isNotEmpty()) {
                    val trimmedChildren = nonEditableChildTexts.map { it.trim() }.filter { it.isNotEmpty() }
                    if (trimmedChildren.any { rawTrimmed.equals(it, ignoreCase = true) }) {
                        return ""
                    }
                    if (trimmedChildren.size > 1) {
                        val commaJoined = trimmedChildren.joinToString(", ")
                        val spaceJoined = trimmedChildren.joinToString(" ")
                        if (rawTrimmed.equals(commaJoined, ignoreCase = true) ||
                            rawTrimmed.equals(spaceJoined, ignoreCase = true)
                        ) {
                            return ""
                        }
                    }
                }

                // Custom chat/search/AI fields frequently render gray prompt hints ("Type a message...", "Search…")
                // without setting `isShowingHintText = true` while the real editable buffer is empty (`0..0`).
                if ((rawTrimmed.endsWith("...") || rawTrimmed.endsWith("…")) && rawTrimmed.length <= 80) {
                    return ""
                }
            } else if (!hintText.isNullOrBlank()) {
                // Strip Material TextInputLayout ", <hint>" suffix appended after real user text when non-empty
                val hintSuffix = ", ${hintText.trim()}"
                if (rawText.endsWith(hintSuffix, ignoreCase = true)) {
                    val strippedLen = rawText.length - hintSuffix.length
                    if (strippedLen >= 0 && selectionStart <= strippedLen && selectionEnd <= strippedLen) {
                        return rawText.substring(0, strippedLen)
                    }
                }
            }

            return rawText
        }

        internal fun isNoAutoSpaceInputType(inputType: Int, currentText: String): Boolean {
            if (inputType == 0) return false
            val cls = inputType and InputType.TYPE_MASK_CLASS
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) {
                return true
            }
            if (cls == InputType.TYPE_CLASS_TEXT) {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ) {
                    return true
                }
                // Browser omniboxes use TYPE_TEXT_VARIATION_URI for both URLs ("https://...") and search queries.
                // Suppress auto-spacing when the existing text looks like a spaceless URL/domain/path.
                if (variation == InputType.TYPE_TEXT_VARIATION_URI &&
                    currentText.isNotEmpty() &&
                    !currentText.contains(' ') &&
                    (currentText.contains("://") || currentText.contains('.') || currentText.contains('/'))
                ) {
                    return true
                }
            }
            return false
        }

        /**
         * Formats [textToInsert] for widget-native `ACTION_PASTE` with smart leading/trailing word spacing
         * strictly when a real non-zero cursor/selection (`selectionStart > 0 || selectionEnd > 0`) exists inside
         * [sanitizedCurrentText]. Empty/placeholder fields (`0..0` or `-1..-1`) always receive [textToInsert]
         * verbatim with zero added whitespace.
         */
        internal fun computeSmartPastePayload(
            sanitizedCurrentText: String,
            selectionStart: Int,
            selectionEnd: Int,
            textToInsert: String,
            inputType: Int = InputType.TYPE_CLASS_TEXT
        ): String {
            if (textToInsert.isEmpty() || sanitizedCurrentText.isEmpty()) {
                return textToInsert
            }
            if (selectionStart < 0 || selectionEnd < 0 || (selectionStart == 0 && selectionEnd == 0)) {
                return textToInsert
            }
            if (selectionStart > sanitizedCurrentText.length || selectionEnd > sanitizedCurrentText.length) {
                return textToInsert
            }
            if (isNoAutoSpaceInputType(inputType, sanitizedCurrentText)) {
                return textToInsert
            }

            val realStart = minOf(selectionStart, selectionEnd)
            val realEnd = maxOf(selectionStart, selectionEnd)
            val before = sanitizedCurrentText.substring(0, realStart)
            val after = sanitizedCurrentText.substring(realEnd)

            val needLeadingSpace = before.isNotEmpty() &&
                !before.last().isWhitespace() &&
                before.last() !in NO_SPACE_AFTER_CHARS &&
                !textToInsert.first().isWhitespace() &&
                textToInsert.first() !in NO_SPACE_BEFORE_CHARS

            val needTrailingSpace = after.isNotEmpty() &&
                !after.first().isWhitespace() &&
                after.first() !in NO_TRAILING_SPACE_BEFORE_CHARS &&
                !textToInsert.last().isWhitespace()

            val prefix = if (needLeadingSpace) " " else ""
            val suffix = if (needTrailingSpace) " " else ""
            return prefix + textToInsert + suffix
        }

        /**
         * Verifies whether an Accessibility `ACTION_PASTE` or `ACTION_SET_TEXT` call actually modified
         * the target input node's text, placeholder-hint state, or selection range.
         *
         * Prevents false-positive `injected = true` results when custom wrappers, containers, or WebViews
         * return `true` from `performAction(...)` without committing text to the editor.
         */
        internal fun hasConfirmedTextInjection(
            wasShowingHint: Boolean,
            rawBeforeText: String,
            beforeSelStart: Int,
            beforeSelEnd: Int,
            afterShowingHint: Boolean,
            rawAfterText: String,
            afterSelStart: Int,
            afterSelEnd: Int
        ): Boolean {
            // 1. Placeholder hint ("Message", "Search...") disappeared and non-empty text is now present
            if (wasShowingHint && !afterShowingHint && rawAfterText.isNotEmpty()) {
                return true
            }
            // 2. Visible/masked text content changed (while not merely showing placeholder hint text)
            if (!afterShowingHint && rawAfterText != rawBeforeText) {
                return true
            }
            // 3. Caret advanced forward (covers masked password fields, custom span buffers, or dictating text identical to a disguised placeholder)
            if (beforeSelStart >= 0 && afterSelStart > beforeSelStart) {
                return true
            }
            // 4. Editor selection initialized from -1 to > 0 upon paste (e.g. WebView contenteditable / Flutter)
            if (beforeSelStart < 0 && afterSelStart > 0 && !afterShowingHint) {
                return true
            }
            // 5. Non-empty text selection was replaced in-place and collapsed to a caret
            val hadRangeSelection = beforeSelStart >= 0 && beforeSelEnd >= 0 && beforeSelStart != beforeSelEnd
            if (hadRangeSelection && afterSelStart >= 0 && afterSelStart == afterSelEnd && !afterShowingHint) {
                return true
            }
            return false
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null
    private var postEventCheckJob: Job? = null
    private var lastWatchdogCheckMs = 0L
    @Volatile
    private var lastKeyboardCheckMs = 0L
    private var screenReceiverRegistered = false

    // IMPORTANT: Never call recycle() on AccessibilityNodeInfo / AccessibilityWindowInfo instances!
    // Recycling cached AccessibilityCache instances mutates them in place and breaks subsequent lookups.
    @Volatile
    private var lastFocusedInputNode: AccessibilityNodeInfo? = null
    @Volatile
    private var lastFocusedInputAtMs: Long = 0L
    @Volatile
    private var pinnedDictationNode: AccessibilityNodeInfo? = null
    @Volatile
    private var pinnedDictationAtMs: Long = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    postEventCheckJob?.cancel()
                    isScreenInteractive.value = false
                    isInputFieldFocused.value = false
                    pinnedDictationNode = null
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive.value = true
                    FloatingBubbleService.ensureRunningIfEnabled(
                        this@TextInjectionAccessibilityService,
                        reason = "accessibility_screen_on"
                    )
                    schedulePostEventKeyboardCheck()
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

                val bubbleRunning = FloatingBubbleService.isRunning.value
                if (!bubbleRunning) {
                    refreshKeyboardVisibilityState()
                }

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

                // When FloatingBubbleService is active, its adaptive Main-Looper heartbeat already calls
                // refreshNow() — avoid duplicate getWindows() IPC polling from both services simultaneously.
                val sleepMs = when {
                    bubbleRunning -> 3_500L
                    isInputFieldFocused.value -> 650L
                    else -> 1_400L
                }
                delay(sleepMs)
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

    fun refreshNow(force: Boolean = false, minIntervalMs: Long = 160L) {
        if (pollingJob?.isActive != true) {
            startPollingLoop()
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastKeyboardCheckMs < minIntervalMs) {
            return
        }
        refreshKeyboardVisibilityState()
    }

    /**
     * Ultra-fast (`< 2 ms`, zero tree BFS) target pin when the user taps the floating dictation bubble,
     * preserving the exact focused editable node even if an OEM ROM momentarily clears `FOCUS_INPUT`
     * while touching the overlay window.
     */
    fun captureTargetNodeForDictation() {
        try {
            val live = resolveBestTargetInputNode(
                ownPkg = packageName,
                allowPinnedFallback = false,
                allowTreeBfs = false
            )
            if (live != null) {
                val now = SystemClock.elapsedRealtime()
                pinnedDictationNode = live
                pinnedDictationAtMs = now
                lastFocusedInputNode = live
                lastFocusedInputAtMs = now
            } else {
                val recent = lastFocusedInputNode
                if (recent != null && isCachedNodeStillUsable(recent, lastFocusedInputAtMs, emptySet())) {
                    pinnedDictationNode = recent
                    pinnedDictationAtMs = SystemClock.elapsedRealtime()
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterScreenReceiver()
        instance = null
        lastFocusedInputNode = null
        pinnedDictationNode = null
        isServiceConnected.value = false
        isInputFieldFocused.value = false
        postEventCheckJob?.cancel()
        pollingJob?.cancel()
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isScreenInteractive.value) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val interactiveNow = try { pm?.isInteractive ?: true } catch (_: Throwable) { true }
            if (!interactiveNow) return
            isScreenInteractive.value = true
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                captureEventSourceIfInput(event)
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                refreshKeyboardVisibilityState()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                refreshKeyboardVisibilityState()
                // Catch OEM soft-keyboard slide-in animations across fast (80ms) and slower One UI (350-600ms) transitions
                schedulePostEventKeyboardCheck()
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val elapsed = SystemClock.elapsedRealtime() - lastKeyboardCheckMs
                if (!isInputFieldFocused.value || elapsed >= 400L) {
                    refreshKeyboardVisibilityState()
                }
            }
        }
    }

    /**
     * Lightweight `O(1)` event-source check (never walks deep window subtrees on `Dispatchers.Main`).
     */
    private fun captureEventSourceIfInput(event: AccessibilityEvent) {
        try {
            val src = event.source ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val winType = try { src.window?.type } catch (_: Throwable) { null }
                val srcPkg = src.packageName?.toString()
                if (winType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    winType == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    (winType == AccessibilityWindowInfo.TYPE_SYSTEM && srcPkg == packageName)
                ) {
                    return
                }
            }
            val candidate = when {
                isDirectEditableNode(src, packageName) -> src
                else -> findImmediateFocusedEditableChild(src, packageName)
            }
            if (candidate != null) {
                val now = SystemClock.elapsedRealtime()
                lastFocusedInputNode = candidate
                lastFocusedInputAtMs = now
            }
        } catch (_: Throwable) {
        }
    }

    private fun findImmediateFocusedEditableChild(
        parent: AccessibilityNodeInfo,
        ownPkg: String
    ): AccessibilityNodeInfo? {
        val count = (try { parent.childCount } catch (_: Throwable) { 0 }).coerceAtMost(8)
        var inspected = 0
        for (i in 0 until count) {
            val child = try { parent.getChild(i) } catch (_: Throwable) { null } ?: continue
            inspected++
            val focused = try { child.isFocused } catch (_: Throwable) { false }
            if (focused && isDirectEditableNode(child, ownPkg)) {
                return child
            }
            // Material TextInputLayout wraps TextInputEditText inside an intermediate FrameLayout (depth 2)
            if (inspected < 12) {
                val subCount = (try { child.childCount } catch (_: Throwable) { 0 }).coerceAtMost(4)
                for (j in 0 until subCount) {
                    if (inspected >= 12) break
                    val grandChild = try { child.getChild(j) } catch (_: Throwable) { null } ?: continue
                    inspected++
                    val gcFocused = try { grandChild.isFocused } catch (_: Throwable) { false }
                    if (gcFocused && isDirectEditableNode(grandChild, ownPkg)) {
                        return grandChild
                    }
                }
            }
        }
        return null
    }

    private fun schedulePostEventKeyboardCheck() {
        postEventCheckJob?.cancel()
        postEventCheckJob = serviceScope.launch {
            for (stepDelayMs in longArrayOf(80L, 110L, 180L, 260L)) {
                delay(stepDelayMs)
                if (!isScreenInteractive.value) break
                refreshKeyboardVisibilityState()
            }
        }
    }

    override fun onInterrupt() {}

    /**
     * Strictly checks whether the on-screen soft keyboard (`TYPE_INPUT_METHOD` window) is currently
     * visible on screen while the screen is interactive and unlocked.
     *
     * IMPORTANT: Must remain 100% free of Accessibility node-tree walks so `Looper.getMainLooper()`
     * never stalls during recording or UI interactions!
     */
    private fun refreshKeyboardVisibilityState() {
        try {
            lastKeyboardCheckMs = SystemClock.elapsedRealtime()
            isInputFieldFocused.value = isSoftKeyboardWindowVisible()
        } catch (_: Throwable) {
        }
    }

    private fun hasDirectFocusedInputFast(): Boolean {
        return try {
            val activeRoot = rootInActiveWindow ?: return false
            val focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            focused.isEnabled && (focused.isEditable || supportsSetText(focused) || supportsPaste(focused))
        } catch (_: Throwable) {
            false
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
                if (!hasDirectFocusedInputFast()) {
                    return false
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

            // Fallback when `windows` is unavailable/empty on specific OEM ROMs: fast 1-call FOCUS_INPUT check
            hasDirectFocusedInputFast()
        } catch (_: Throwable) {
            false
        }
    }

    private fun supportsSetText(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            node.actionList?.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
            } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun supportsPaste(node: AccessibilityNodeInfo): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val legacyMask = (node.actions and AccessibilityNodeInfo.ACTION_PASTE) != 0
            if (legacyMask) return true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_PASTE } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isWebViewClassName(node: AccessibilityNodeInfo): Boolean {
        val cls = try { node.className?.toString().orEmpty() } catch (_: Throwable) { "" }
        return cls.contains("WebView", ignoreCase = true) || cls.contains("org.chromium", ignoreCase = true)
    }

    private fun isDirectEditableNode(node: AccessibilityNodeInfo, ownPkg: String): Boolean {
        return try {
            if (!node.isEnabled) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val winType = try { node.window?.type } catch (_: Throwable) { null }
                val pkg = node.packageName?.toString()
                if (winType == AccessibilityWindowInfo.TYPE_SYSTEM && pkg == ownPkg) return false
            }
            if (node.isEditable) return true
            if (supportsSetText(node)) return true
            val cls = node.className?.toString().orEmpty()
            cls.contains("EditText", ignoreCase = true) ||
                cls.contains("AutoCompleteTextView", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isTextInputNode(node: AccessibilityNodeInfo, ownPkg: String): Boolean {
        if (isDirectEditableNode(node, ownPkg)) return true
        return try {
            if (!node.isEnabled) return false
            val nodePkg = node.packageName?.toString()
            if (nodePkg == ownPkg) return false
            supportsPaste(node) || isWebViewClassName(node)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPasswordField(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputType = node.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        return false
    }

    /**
     * When `findFocus(FOCUS_INPUT)` returns a wrapper view (such as `WebView` or `AndroidComposeView`)
     * rather than the inner focused `<input>` / `contenteditable` / Compose `TextField` semantics node,
     * drills into that focused wrapper's subtree to locate the actual focused input descendant.
     *
     * Never guesses an unfocused editable child from a generic layout container!
     */
    private fun drillDownToBestInputChild(
        root: AccessibilityNodeInfo,
        ownPkg: String,
        allowSubtreeWalk: Boolean
    ): AccessibilityNodeInfo? {
        if (isDirectEditableNode(root, ownPkg)) {
            return root
        }
        if (!allowSubtreeWalk) {
            return if (isTextInputNode(root, ownPkg)) root else null
        }

        val rootIsWebView = isWebViewClassName(root)
        var focusedPasteChild: AccessibilityNodeInfo? = null
        var singleWebViewEditable: AccessibilityNodeInfo? = null
        var webViewEditableCount = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_DRILL_NODES) {
            val current = queue.removeFirst()
            visited++
            if (current !== root) {
                val enabled = try { current.isEnabled } catch (_: Throwable) { false }
                if (enabled) {
                    val directEditable = isDirectEditableNode(current, ownPkg)
                    val focused = try {
                        current.isFocused || current.isAccessibilityFocused
                    } catch (_: Throwable) {
                        false
                    }
                    if (focused && directEditable) {
                        return current
                    }
                    if (focused && focusedPasteChild == null && isTextInputNode(current, ownPkg)) {
                        focusedPasteChild = current
                    }
                    if (rootIsWebView && directEditable && (try { current.isVisibleToUser } catch (_: Throwable) { false })) {
                        webViewEditableCount++
                        if (webViewEditableCount == 1) {
                            singleWebViewEditable = current
                        } else {
                            singleWebViewEditable = null
                        }
                    }
                }
            }

            val childCount = try { current.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until childCount) {
                if (visited + queue.size >= MAX_DRILL_NODES) break
                val child = try { current.getChild(i) } catch (_: Throwable) { null }
                if (child != null) {
                    queue.addLast(child)
                }
            }
        }

        if (focusedPasteChild != null) return focusedPasteChild
        if (rootIsWebView && webViewEditableCount == 1 && singleWebViewEditable != null) {
            return singleWebViewEditable
        }
        return if (isTextInputNode(root, ownPkg)) root else null
    }

    /**
     * Bounded paste-time BFS over a focused application window root strictly looking for a node
     * with `node.isFocused == true` AND [isDirectEditableNode] (handles Jetpack Compose `TextField` /
     * `BasicTextField` semantics trees when `root.findFocus(FOCUS_INPUT)` returns `null`).
     */
    private fun searchWindowTreeForFocusedInputNode(
        windowRoot: AccessibilityNodeInfo,
        ownPkg: String
    ): AccessibilityNodeInfo? {
        var focusedFallback: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(windowRoot)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_BFS_NODES) {
            val node = queue.removeFirst()
            visited++

            val focused = try { node.isFocused } catch (_: Throwable) { false }
            if (focused) {
                val visible = try { node.isVisibleToUser } catch (_: Throwable) { true }
                if (visible && isDirectEditableNode(node, ownPkg)) {
                    return node
                }
                if (visible && focusedFallback == null && isTextInputNode(node, ownPkg)) {
                    focusedFallback = node
                }
            }

            val childCount = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until childCount) {
                if (visited + queue.size >= MAX_BFS_NODES) break
                val child = try { node.getChild(i) } catch (_: Throwable) { null }
                if (child != null) {
                    queue.addLast(child)
                }
            }
        }
        return focusedFallback
    }

    private fun isCachedNodeStillUsable(
        node: AccessibilityNodeInfo,
        capturedAtMs: Long,
        activeAppPackages: Set<String>
    ): Boolean {
        val ageMs = SystemClock.elapsedRealtime() - capturedAtMs
        if (ageMs < 0L || ageMs > MAX_PINNED_NODE_AGE_MS) return false
        return try {
            val refreshed = node.refresh()
            if (!refreshed) return false
            if (!node.isEnabled || !node.isVisibleToUser) return false
            if (!isTextInputNode(node, packageName)) return false
            val pkg = node.packageName?.toString()
            activeAppPackages.isEmpty() || pkg == null || pkg in activeAppPackages
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resolves the currently focused input node using a strict, false-positive-free priority cascade:
     * 1. Fast path: `rootInActiveWindow.findFocus(FOCUS_INPUT)` when `rootInActiveWindow` belongs to
     *    a foreground app window (returns in 2 IPC calls for 95% of Android apps).
     * 2. Multi-window `findFocus(FOCUS_INPUT)` across interactive `TYPE_APPLICATION` windows (drilling
     *    into `WebView` / `AndroidComposeView` wrappers when [allowTreeBfs] is `true`).
     * 3. Paste-time-only bounded BFS (`allowTreeBfs = true`) strictly for `node.isFocused && isDirectEditableNode`.
     * 4. `pinnedDictationNode` captured when recording started, then `lastFocusedInputNode` from user focus/click.
     */
    fun resolveBestTargetInputNode(
        ownPkg: String = packageName,
        allowPinnedFallback: Boolean = true,
        allowTreeBfs: Boolean = false
    ): AccessibilityNodeInfo? {
        val candidateRoots = ArrayList<AccessibilityNodeInfo>(6)
        val activeAppPackages = LinkedHashSet<String>(4)

        // Fast Path: Check rootInActiveWindow first (2 IPC calls in standard apps)
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try { activeRoot.window?.type } catch (_: Throwable) { null }
                } else {
                    null
                }
                val pkg = activeRoot.packageName?.toString()
                val isOwnOverlayWindow = winType == AccessibilityWindowInfo.TYPE_SYSTEM && pkg == ownPkg
                if (winType != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    winType != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    !isOwnOverlayWindow
                ) {
                    if (!pkg.isNullOrEmpty()) {
                        activeAppPackages.add(pkg)
                    }
                    val directFocus = try {
                        activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    } catch (_: Throwable) {
                        null
                    }
                    if (directFocus != null && isDirectEditableNode(directFocus, ownPkg)) {
                        return directFocus
                    }
                    candidateRoots.add(activeRoot)
                }
            }
        } catch (_: Throwable) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val allWindows = windows
                if (!allWindows.isNullOrEmpty()) {
                    val appWindows = allWindows
                        .filter { win ->
                            val type = try { win.type } catch (_: Throwable) { -1 }
                            type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                                (type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                                    (try { win.isFocused } catch (_: Throwable) { false }))
                        }
                        .sortedWith(
                            compareByDescending<AccessibilityWindowInfo> {
                                try { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } catch (_: Throwable) { false }
                            }.thenByDescending {
                                try { it.isFocused } catch (_: Throwable) { false }
                            }.thenByDescending {
                                try { it.isActive } catch (_: Throwable) { false }
                            }.thenByDescending {
                                try { it.layer } catch (_: Throwable) { 0 }
                            }
                        )

                    for (win in appWindows) {
                        val root = try { win.root } catch (_: Throwable) { null } ?: continue
                        val rootPkg = root.packageName?.toString()
                        val winType = try { win.type } catch (_: Throwable) { -1 }
                        if (winType == AccessibilityWindowInfo.TYPE_SYSTEM && rootPkg == ownPkg) {
                            continue
                        }
                        if (!rootPkg.isNullOrEmpty()) {
                            activeAppPackages.add(rootPkg)
                        }
                        if (candidateRoots.none { it == root }) {
                            candidateRoots.add(root)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Tier 1: Direct FOCUS_INPUT lookup (+ subtree drill-down when allowTreeBfs == true)
        var nonEditableFocusFallback: AccessibilityNodeInfo? = null
        for (root in candidateRoots) {
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    val resolved = drillDownToBestInputChild(
                        root = focused,
                        ownPkg = ownPkg,
                        allowSubtreeWalk = allowTreeBfs
                    )
                    if (resolved != null) {
                        if (isDirectEditableNode(resolved, ownPkg)) {
                            return resolved
                        }
                        if (nonEditableFocusFallback == null) {
                            nonEditableFocusFallback = resolved
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Tier 2 (paste-time only): Bounded BFS for focused Compose / WebView semantics nodes
        if (allowTreeBfs) {
            for (root in candidateRoots.take(2)) {
                val bfsFocused = searchWindowTreeForFocusedInputNode(
                    windowRoot = root,
                    ownPkg = ownPkg
                )
                if (bfsFocused != null && isDirectEditableNode(bfsFocused, ownPkg)) {
                    return bfsFocused
                }
                if (bfsFocused != null && nonEditableFocusFallback == null) {
                    nonEditableFocusFallback = bfsFocused
                }
            }
        }

        // Tier 3: Pinned editable node from dictation start, then recent user-focused editable node
        if (allowPinnedFallback) {
            val pinned = pinnedDictationNode
            if (pinned != null && isCachedNodeStillUsable(pinned, pinnedDictationAtMs, activeAppPackages)) {
                return pinned
            }
            val recent = lastFocusedInputNode
            if (recent != null && isCachedNodeStillUsable(recent, lastFocusedInputAtMs, activeAppPackages)) {
                return recent
            }
        }

        return nonEditableFocusFallback
    }

    /**
     * Extracts genuine user-typed text from [node], stripping password masks, `isShowingHintText`,
     * and disguised gray placeholder/hint/label decoration strings exposed by Jetpack Compose
     * `DecorationBox`, Material `TextInputLayout`, WebViews, and custom `AccessibilityDelegate`s.
     */
    fun extractSanitizedEditableText(node: AccessibilityNodeInfo): String {
        val isPassword = try { isPasswordField(node) } catch (_: Throwable) { false }
        if (isPassword) return ""

        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (try { node.isShowingHintText } catch (_: Throwable) { false })
        if (showingHint) return ""

        val rawText = try { node.text?.toString().orEmpty() } catch (_: Throwable) { "" }
        if (rawText.isEmpty()) return ""

        val selStart = try { node.textSelectionStart } catch (_: Throwable) { -1 }
        val selEnd = try { node.textSelectionEnd } catch (_: Throwable) { -1 }
        if (selStart < 0 && selEnd < 0) return ""

        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { node.hintText?.toString() } catch (_: Throwable) { null }
        } else {
            null
        }
        val contentDesc = try { node.contentDescription?.toString() } catch (_: Throwable) { null }
        val tooltip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { node.tooltipText?.toString() } catch (_: Throwable) { null }
        } else {
            null
        }
        val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { node.paneTitle?.toString() } catch (_: Throwable) { null }
        } else {
            null
        }

        val childTexts = if (selStart <= 0 && selEnd <= 0) {
            val collected = ArrayList<String>(6)
            val childCount = (try { node.childCount } catch (_: Throwable) { 0 }).coerceAtMost(6)
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                val childEditable = try { child.isEditable } catch (_: Throwable) { false }
                if (!childEditable) {
                    val ct = try { child.text?.toString() } catch (_: Throwable) { null }
                    if (!ct.isNullOrBlank()) collected.add(ct)
                    val cd = try { child.contentDescription?.toString() } catch (_: Throwable) { null }
                    if (!cd.isNullOrBlank()) collected.add(cd)
                }
            }
            collected
        } else {
            emptyList()
        }

        return sanitizeEditableNodeText(
            rawText = rawText,
            isShowingHintText = false,
            isPassword = false,
            selectionStart = selStart,
            selectionEnd = selEnd,
            hintText = hintText,
            contentDescription = contentDesc,
            tooltipText = tooltip,
            paneTitle = paneTitle,
            nonEditableChildTexts = childTexts
        )
    }

    /**
     * Copies the smart-spaced dictation payload to the system clipboard **strictly once** and injects it
     * into the focused text field, returning [TextInjectionResult.INJECTED] ONLY when insertion into the
     * target node is positively verified.
     *
     * Uses widget-native `ACTION_PASTE` first so gray placeholder / hint strings in `AccessibilityNodeInfo.getText()`
     * are never typed out into the field and rich-text spans / Undo history are preserved, with a sanitized
     * `ACTION_SET_TEXT` fallback when `ACTION_PASTE` is unsupported.
     */
    suspend fun injectTextSuspend(textToInsert: String): TextInjectionResult = withContext(Dispatchers.Main) {
        if (textToInsert.isEmpty()) return@withContext TextInjectionResult.NOT_INJECTED_NO_CLIPBOARD

        val firstTarget = resolveBestTargetInputNode(
            ownPkg = packageName,
            allowPinnedFallback = true,
            allowTreeBfs = true
        )

        val pastePayload = if (firstTarget != null) {
            try {
                firstTarget.refresh()
            } catch (_: Throwable) {
            }
            val focusedNow = try { firstTarget.isFocused } catch (_: Throwable) { false }
            if (!focusedNow) {
                val focusedOk = try {
                    firstTarget.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                } catch (_: Throwable) {
                    false
                }
                if (focusedOk) {
                    delay(35L)
                    try {
                        firstTarget.refresh()
                    } catch (_: Throwable) {
                    }
                }
            }
            val sanitizedCurrent = extractSanitizedEditableText(firstTarget)
            val selStart = try { firstTarget.textSelectionStart } catch (_: Throwable) { -1 }
            val selEnd = try { firstTarget.textSelectionEnd } catch (_: Throwable) { -1 }
            val inputType = try { firstTarget.inputType } catch (_: Throwable) { InputType.TYPE_CLASS_TEXT }
            computeSmartPastePayload(
                sanitizedCurrentText = sanitizedCurrent,
                selectionStart = selStart,
                selectionEnd = selEnd,
                textToInsert = textToInsert,
                inputType = inputType
            )
        } else {
            textToInsert
        }

        // Write to the system clipboard STRICTLY ONCE per dictation (prevents duplicate clipboard entries/toasts
        // while ensuring Compose/TextView `hasPrimaryClip()` is true right before `ACTION_PASTE`).
        val clipboardReady = copyPlainTextToClipboard(pastePayload)
        if (clipboardReady) {
            delay(35L)
        }

        var opaqueNodeAlreadyPasted: AccessibilityNodeInfo? = null
        if (firstTarget != null) {
            val result = tryInjectIntoTargetNode(
                node = firstTarget,
                textToInsert = textToInsert,
                clipboardReady = clipboardReady,
                forceFocusFirst = false
            )
            if (result == InjectionAttemptOutcome.CONFIRMED) {
                pinnedDictationNode = null
                return@withContext TextInjectionResult.INJECTED
            }
            if (result == InjectionAttemptOutcome.OPAQUE_UNVERIFIED_PASTE_SENT) {
                opaqueNodeAlreadyPasted = firstTarget
            }
        }

        // Transient focus/window transition retry: wait briefly, re-resolve target, explicitly restore
        // input focus on the target node, and try once more using the already-copied clipboard.
        delay(75L)
        val retryTarget = resolveBestTargetInputNode(
            ownPkg = packageName,
            allowPinnedFallback = true,
            allowTreeBfs = true
        ) ?: firstTarget ?: return@withContext if (clipboardReady) {
            TextInjectionResult.COPIED_TO_CLIPBOARD_ONLY
        } else {
            TextInjectionResult.NOT_INJECTED_NO_CLIPBOARD
        }

        if (opaqueNodeAlreadyPasted != null && retryTarget == opaqueNodeAlreadyPasted) {
            // Avoid sending a duplicate ACTION_PASTE to an opaque non-editable view that already accepted one
            return@withContext if (clipboardReady) {
                TextInjectionResult.COPIED_TO_CLIPBOARD_ONLY
            } else {
                TextInjectionResult.NOT_INJECTED_NO_CLIPBOARD
            }
        }

        val retryOutcome = tryInjectIntoTargetNode(
            node = retryTarget,
            textToInsert = textToInsert,
            clipboardReady = clipboardReady,
            forceFocusFirst = true
        )
        if (retryOutcome == InjectionAttemptOutcome.CONFIRMED) {
            pinnedDictationNode = null
            return@withContext TextInjectionResult.INJECTED
        }

        if (clipboardReady) {
            TextInjectionResult.COPIED_TO_CLIPBOARD_ONLY
        } else {
            TextInjectionResult.NOT_INJECTED_NO_CLIPBOARD
        }
    }

    private enum class InjectionAttemptOutcome {
        CONFIRMED,
        OPAQUE_UNVERIFIED_PASTE_SENT,
        FAILED
    }

    private fun copyPlainTextToClipboard(text: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlow", text))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private data class NodeTextSnapshot(
        val showingHint: Boolean,
        val rawText: String,
        val selStart: Int,
        val selEnd: Int
    )

    private fun captureNodeSnapshot(node: AccessibilityNodeInfo): NodeTextSnapshot {
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (try { node.isShowingHintText } catch (_: Throwable) { false })
        val rawText = try { node.text?.toString().orEmpty() } catch (_: Throwable) { "" }
        val selStart = try { node.textSelectionStart } catch (_: Throwable) { -1 }
        val selEnd = try { node.textSelectionEnd } catch (_: Throwable) { -1 }
        return NodeTextSnapshot(
            showingHint = showingHint,
            rawText = rawText,
            selStart = selStart,
            selEnd = selEnd
        )
    }

    private suspend fun verifyNodeChangedAfterAction(
        node: AccessibilityNodeInfo,
        before: NodeTextSnapshot
    ): Boolean {
        delay(45L)
        try {
            node.refresh()
        } catch (_: Throwable) {
        }
        val firstAfter = captureNodeSnapshot(node)
        if (hasConfirmedTextInjection(
                wasShowingHint = before.showingHint,
                rawBeforeText = before.rawText,
                beforeSelStart = before.selStart,
                beforeSelEnd = before.selEnd,
                afterShowingHint = firstAfter.showingHint,
                rawAfterText = firstAfter.rawText,
                afterSelStart = firstAfter.selStart,
                afterSelEnd = firstAfter.selEnd
            )
        ) {
            return true
        }

        // Second check (+65ms) catches asynchronous WebView / Blink / Compose / React Native DOM commits
        delay(65L)
        try {
            node.refresh()
        } catch (_: Throwable) {
        }
        val secondAfter = captureNodeSnapshot(node)
        return hasConfirmedTextInjection(
            wasShowingHint = before.showingHint,
            rawBeforeText = before.rawText,
            beforeSelStart = before.selStart,
            beforeSelEnd = before.selEnd,
            afterShowingHint = secondAfter.showingHint,
            rawAfterText = secondAfter.rawText,
            afterSelStart = secondAfter.selStart,
            afterSelEnd = secondAfter.selEnd
        )
    }

    private suspend fun tryInjectIntoTargetNode(
        node: AccessibilityNodeInfo,
        textToInsert: String,
        clipboardReady: Boolean,
        forceFocusFirst: Boolean
    ): InjectionAttemptOutcome {
        try {
            node.refresh()
        } catch (_: Throwable) {
        }

        val focusedInitially = try { node.isFocused } catch (_: Throwable) { false }
        if (forceFocusFirst || !focusedInitially) {
            val focusedOk = try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Throwable) {
                false
            }
            if (focusedOk) {
                delay(35L)
                try {
                    node.refresh()
                } catch (_: Throwable) {
                }
            }
        }

        val isPassword = isPasswordField(node)
        val before = captureNodeSnapshot(node)
        val directEditable = isDirectEditableNode(node, packageName)
        val canSetText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && directEditable && !isPassword

        // Primary strategy: Widget-native `ACTION_PASTE`.
        // Executes directly against the target widget's real internal editable buffer (`EditText.Editable`,
        // Compose `TextFieldValue`/`TextFieldState`, Chromium DOM), automatically dismissing gray placeholder
        // hints without ever reading `AccessibilityNodeInfo.getText()` and preserving spans + Undo history.
        if (clipboardReady) {
            val pasteOutcome = performVerifiedPaste(node, before)
            if (pasteOutcome == InjectionAttemptOutcome.CONFIRMED) {
                DebugLogManager.logInfo(TAG, "Confirmed text injection via ACTION_PASTE (${textToInsert.length} chars)")
                return InjectionAttemptOutcome.CONFIRMED
            }
            if (canSetText) {
                val latestBefore = captureNodeSnapshot(node)
                val sanitizedBeforeText = extractSanitizedEditableText(node)
                if (performVerifiedSetText(node, latestBefore, sanitizedBeforeText, textToInsert)) {
                    DebugLogManager.logInfo(TAG, "Confirmed text injection via sanitized fallback ACTION_SET_TEXT (${textToInsert.length} chars)")
                    return InjectionAttemptOutcome.CONFIRMED
                }
            }
            if (pasteOutcome == InjectionAttemptOutcome.OPAQUE_UNVERIFIED_PASTE_SENT) {
                return InjectionAttemptOutcome.OPAQUE_UNVERIFIED_PASTE_SENT
            }
            return InjectionAttemptOutcome.FAILED
        }

        if (canSetText) {
            val sanitizedBeforeText = extractSanitizedEditableText(node)
            if (performVerifiedSetText(node, before, sanitizedBeforeText, textToInsert)) {
                DebugLogManager.logInfo(TAG, "Confirmed text injection via sanitized ACTION_SET_TEXT (${textToInsert.length} chars)")
                return InjectionAttemptOutcome.CONFIRMED
            }
        }
        return InjectionAttemptOutcome.FAILED
    }

    private suspend fun performVerifiedSetText(
        node: AccessibilityNodeInfo,
        before: NodeTextSnapshot,
        sanitizedBeforeText: String,
        textToInsert: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val setDispatched = try {
            val inputType = try { node.inputType } catch (_: Throwable) { InputType.TYPE_CLASS_TEXT }
            val spliced = if (sanitizedBeforeText.isNotEmpty() && isNoAutoSpaceInputType(inputType, sanitizedBeforeText)) {
                if (before.selStart in 0..sanitizedBeforeText.length && before.selEnd in 0..sanitizedBeforeText.length) {
                    val realStart = minOf(before.selStart, before.selEnd)
                    val realEnd = maxOf(before.selStart, before.selEnd)
                    val combined = sanitizedBeforeText.substring(0, realStart) + textToInsert + sanitizedBeforeText.substring(realEnd)
                    SplicedTextResult(combined, realStart + textToInsert.length)
                } else {
                    val combined = sanitizedBeforeText + textToInsert
                    SplicedTextResult(combined, combined.length)
                }
            } else {
                spliceTextForSetText(
                    currentText = sanitizedBeforeText,
                    selectionStart = before.selStart,
                    selectionEnd = before.selEnd,
                    textToInsert = textToInsert
                )
            }
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    spliced.newText
                )
            }
            val setOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (setOk) {
                val selectionArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, spliced.newCursorPosition)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, spliced.newCursorPosition)
                }
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                } catch (_: Throwable) {
                }
            }
            setOk
        } catch (_: Throwable) {
            false
        }
        if (!setDispatched) return false
        return verifyNodeChangedAfterAction(node, before)
    }

    private suspend fun performVerifiedPaste(
        node: AccessibilityNodeInfo,
        before: NodeTextSnapshot
    ): InjectionAttemptOutcome {
        val pastedDispatched = try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) {
            false
        }
        if (!pastedDispatched) return InjectionAttemptOutcome.FAILED
        if (verifyNodeChangedAfterAction(node, before)) {
            return InjectionAttemptOutcome.CONFIRMED
        }
        // If an opaque non-editable container (e.g. raw WebView / custom view with empty node.text) returned true
        // from ACTION_PASTE, record that paste was already sent once so retry doesn't double-paste while still
        // notifying the user that the clipboard also holds the text.
        val isOpaqueNonEditable = !isDirectEditableNode(node, packageName) &&
            before.rawText.isEmpty() &&
            before.selStart < 0
        return if (isOpaqueNonEditable) {
            InjectionAttemptOutcome.OPAQUE_UNVERIFIED_PASTE_SENT
        } else {
            InjectionAttemptOutcome.FAILED
        }
    }
}
