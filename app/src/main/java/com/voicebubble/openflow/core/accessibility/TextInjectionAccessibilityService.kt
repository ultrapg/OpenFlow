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
import android.os.PersistableBundle
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

    data class SplicedTextResult(
        val newText: String,
        val newCursorPosition: Int
    )

    companion object {
        private const val TAG = "TextInjectionA11y"
        private const val MAX_BFS_NODES = 420
        private const val MAX_PINNED_NODE_AGE_MS = 10 * 60_000L
        private const val CLIPBOARD_RESTORE_DELAY_MS = 750L

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
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null
    private var postEventCheckJob: Job? = null
    private var clipboardRestoreJob: Job? = null
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
     * Captures and pins the currently focused editable node right when the user taps the floating
     * dictation bubble (`t = 0 ms`), guaranteeing we can recover the exact input target even if
     * an OEM ROM momentarily drops `FOCUS_INPUT` while interacting with the overlay.
     */
    fun captureTargetNodeForDictation() {
        try {
            val live = resolveBestTargetInputNode(ownPkg = packageName, allowPinnedFallback = false)
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
        clipboardRestoreJob?.cancel()
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
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
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
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val elapsed = SystemClock.elapsedRealtime() - lastKeyboardCheckMs
                if (!isInputFieldFocused.value || elapsed >= 400L) {
                    refreshKeyboardVisibilityState()
                }
            }
        }
    }

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
            val resolved = if (src.isEditable && src.isEnabled) {
                src
            } else {
                drillDownToBestInputChild(src, packageName)
            }
            if (resolved != null && isTextInputNode(resolved, packageName)) {
                val now = SystemClock.elapsedRealtime()
                lastFocusedInputNode = resolved
                lastFocusedInputAtMs = now
            }
        } catch (_: Throwable) {
        }
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
     */
    private fun refreshKeyboardVisibilityState() {
        try {
            lastKeyboardCheckMs = SystemClock.elapsedRealtime()
            val visible = isSoftKeyboardWindowVisible()
            isInputFieldFocused.value = visible
            if (visible) {
                val live = resolveBestTargetInputNode(ownPkg = packageName, allowPinnedFallback = false)
                if (live != null) {
                    lastFocusedInputNode = live
                    lastFocusedInputAtMs = SystemClock.elapsedRealtime()
                }
            }
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
                val focusedOnLockscreen = resolveBestTargetInputNode(ownPkg = packageName, allowPinnedFallback = false)
                if (focusedOnLockscreen == null) {
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

            // Fallback when `windows` is unavailable/empty on specific OEM ROMs: check if an editable input node holds focus
            resolveBestTargetInputNode(ownPkg = packageName, allowPinnedFallback = false) != null
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

    private fun isTextInputNode(node: AccessibilityNodeInfo, ownPkg: String): Boolean {
        return try {
            if (!node.isEnabled) return false
            val nodePkg = node.packageName?.toString()
            if (node.isEditable) return true
            if (supportsSetText(node) || supportsPaste(node)) {
                // Reject non-editable views inside OpenFlow's own overlay; allow real Compose inputs inside OpenFlow MainActivity
                if (nodePkg == ownPkg && !supportsSetText(node)) return false
                return true
            }
            val cls = node.className?.toString().orEmpty()
            cls.contains("EditText", ignoreCase = true) ||
                cls.contains("AutoCompleteTextView", ignoreCase = true)
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
     * When `findFocus(FOCUS_INPUT)` returns a container view (such as `WebView` or `AndroidComposeView`)
     * rather than the inner `<input>` / `contenteditable` / Compose semantics node, drills into its
     * subtree via bounded BFS to locate the real focused/editable child.
     */
    private fun drillDownToBestInputChild(root: AccessibilityNodeInfo, ownPkg: String): AccessibilityNodeInfo? {
        if (try { root.isEditable && root.isEnabled } catch (_: Throwable) { false }) {
            return root
        }

        var focusedTextChild: AccessibilityNodeInfo? = null
        var singleVisibleEditableChild: AccessibilityNodeInfo? = null
        var visibleEditableCount = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_BFS_NODES) {
            val current = queue.removeFirst()
            visited++
            if (current !== root) {
                val enabled = try { current.isEnabled } catch (_: Throwable) { false }
                if (enabled) {
                    val editable = try { current.isEditable } catch (_: Throwable) { false }
                    val focused = try {
                        current.isFocused || current.isAccessibilityFocused
                    } catch (_: Throwable) {
                        false
                    }
                    if (focused && editable) {
                        return current
                    }
                    if (focused && focusedTextChild == null && isTextInputNode(current, ownPkg)) {
                        focusedTextChild = current
                    }
                    if (editable && (try { current.isVisibleToUser } catch (_: Throwable) { false })) {
                        visibleEditableCount++
                        if (visibleEditableCount == 1) {
                            singleVisibleEditableChild = current
                        } else {
                            singleVisibleEditableChild = null
                        }
                    }
                }
            }

            val childCount = try { current.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until childCount) {
                if (visited + queue.size >= MAX_BFS_NODES) break
                val child = try { current.getChild(i) } catch (_: Throwable) { null }
                if (child != null) {
                    queue.addLast(child)
                }
            }
        }

        if (focusedTextChild != null) return focusedTextChild
        if (visibleEditableCount == 1 && singleVisibleEditableChild != null) return singleVisibleEditableChild
        return if (isTextInputNode(root, ownPkg)) root else null
    }

    /**
     * Bounded BFS over an application window root to locate:
     * 1. Any node with `isFocused` + `isEditable` (catches Jetpack Compose & WebView virtual nodes
     *    even when `root.findFocus(FOCUS_INPUT)` returns `null`).
     * 2. Any node with `isFocused` + `isTextInputNode`.
     * 3. Optionally, if [allowSingleEditableWhenKeyboardOpen] is `true`, the sole visible enabled
     *    editable field in the window.
     */
    private fun searchWindowTreeForInputNode(
        windowRoot: AccessibilityNodeInfo,
        ownPkg: String,
        allowSingleEditableWhenKeyboardOpen: Boolean
    ): AccessibilityNodeInfo? {
        var focusedInputFallback: AccessibilityNodeInfo? = null
        var singleEditable: AccessibilityNodeInfo? = null
        var editableCount = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(windowRoot)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_BFS_NODES) {
            val node = queue.removeFirst()
            visited++

            val enabled = try { node.isEnabled } catch (_: Throwable) { false }
            if (enabled) {
                val editable = try { node.isEditable } catch (_: Throwable) { false }
                val setTextCapable = editable || supportsSetText(node)
                val focused = try { node.isFocused } catch (_: Throwable) { false }
                val visible = try { node.isVisibleToUser } catch (_: Throwable) { true }

                if (focused && editable && visible) {
                    return node
                }
                if (focused && focusedInputFallback == null && isTextInputNode(node, ownPkg) && visible) {
                    focusedInputFallback = node
                }
                if (allowSingleEditableWhenKeyboardOpen && setTextCapable && visible) {
                    val pkg = node.packageName?.toString()
                    if (pkg != ownPkg || editable) {
                        editableCount++
                        if (editableCount == 1) {
                            singleEditable = node
                        } else {
                            singleEditable = null
                        }
                    }
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

        if (focusedInputFallback != null) return focusedInputFallback
        if (allowSingleEditableWhenKeyboardOpen && editableCount == 1 && singleEditable != null) {
            return singleEditable
        }
        return null
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
     * Resolves the best target input node for dictation context inspection and auto-paste using a
     * 5-tier priority cascade:
     * 1. `findFocus(FOCUS_INPUT)` in focused/active `TYPE_APPLICATION` windows (drilling down into
     *    `WebView` / `AndroidComposeView` subtrees so container wrappers never mask the real input).
     * 2. Bounded BFS in focused/active `TYPE_APPLICATION` windows for `node.isFocused && isTextInputNode`
     *    (fixes Jetpack Compose `TextField`/`BasicTextField` & Chromium virtual accessibility trees).
     * 3. `pinnedDictationNode` captured when the user tapped Start Recording (if still refreshed & visible
     *    in the foreground app).
     * 4. `lastFocusedInputNode` captured from recent user focus/click/selection events.
     * 5. Single visible editable input in the focused app window when the soft keyboard is open.
     */
    fun resolveBestTargetInputNode(
        ownPkg: String = packageName,
        allowPinnedFallback: Boolean = true
    ): AccessibilityNodeInfo? {
        val candidateRoots = ArrayList<AccessibilityNodeInfo>(6)
        val activeAppPackages = LinkedHashSet<String>(4)

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
                        candidateRoots.add(root)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Also include rootInActiveWindow if it wasn't already covered (e.g., OEM ROMs where getWindows() is empty)
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
                    if (candidateRoots.none { it == activeRoot }) {
                        candidateRoots.add(0, activeRoot)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // Tier 1: Direct FOCUS_INPUT lookup + subtree drill-down for WebView / Compose wrappers
        var nonEditableFocusFallback: AccessibilityNodeInfo? = null
        for (root in candidateRoots) {
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    val resolved = drillDownToBestInputChild(focused, ownPkg)
                    if (resolved != null) {
                        if (resolved.isEditable || supportsSetText(resolved)) {
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

        // Tier 2: Bounded BFS for focused Compose / WebView / custom input semantics nodes
        for (root in candidateRoots) {
            val bfsFocused = searchWindowTreeForInputNode(
                windowRoot = root,
                ownPkg = ownPkg,
                allowSingleEditableWhenKeyboardOpen = false
            )
            if (bfsFocused != null) {
                return bfsFocused
            }
        }

        if (nonEditableFocusFallback != null) {
            return nonEditableFocusFallback
        }

        // Tier 3 & Tier 4: Pinned node from dictation start, then last focused input node from user tap/focus
        if (allowPinnedFallback) {
            val pinned = pinnedDictationNode
            if (pinned != null && isCachedNodeStillUsable(pinned, pinnedDictationAtMs, activeAppPackages)) {
                return pinned
            }
            val recent = lastFocusedInputNode
            if (recent != null && isCachedNodeStillUsable(recent, lastFocusedInputAtMs, activeAppPackages)) {
                return recent
            }

            // Tier 5: If the soft keyboard is visibly open, check if the foreground app window has a single visible input field
            if (isInputFieldFocused.value && candidateRoots.isNotEmpty()) {
                val primaryRoot = candidateRoots.first()
                val soleEditable = searchWindowTreeForInputNode(
                    windowRoot = primaryRoot,
                    ownPkg = ownPkg,
                    allowSingleEditableWhenKeyboardOpen = true
                )
                if (soleEditable != null) {
                    return soleEditable
                }
            }
        }

        return null
    }

    /**
     * Injects [textToInsert] into the focused text field and returns `true` only if the text was
     * actually inserted into a target input node. If no target field is available, returns `false`
     * so the caller can fall back to copying [textToInsert] to the clipboard.
     */
    suspend fun injectTextSuspend(textToInsert: String): Boolean = withContext(Dispatchers.Main) {
        if (textToInsert.isEmpty()) return@withContext false

        val firstTarget = resolveBestTargetInputNode(ownPkg = packageName, allowPinnedFallback = true)
        if (firstTarget != null && tryInjectIntoTargetNode(firstTarget, textToInsert, forceFocusFirst = false)) {
            pinnedDictationNode = null
            return@withContext true
        }

        // Transient focus/window transition retry: wait briefly, re-resolve target, explicitly restore
        // input focus on the target node, and try again before falling back to clipboard.
        delay(90L)
        val retryTarget = resolveBestTargetInputNode(ownPkg = packageName, allowPinnedFallback = true)
            ?: firstTarget
            ?: return@withContext false

        val retrySucceeded = tryInjectIntoTargetNode(retryTarget, textToInsert, forceFocusFirst = true)
        if (retrySucceeded) {
            pinnedDictationNode = null
        }
        retrySucceeded
    }

    private suspend fun tryInjectIntoTargetNode(
        node: AccessibilityNodeInfo,
        textToInsert: String,
        forceFocusFirst: Boolean
    ): Boolean {
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
                delay(45L)
                try {
                    node.refresh()
                } catch (_: Throwable) {
                }
            }
        }

        val isPassword = isPasswordField(node)
        val isShowingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (try { node.isShowingHintText } catch (_: Throwable) { false })

        // Never treat placeholder hint text (e.g., "Message", "Search...") as existing user content!
        val currentText = if (isShowingHint || isPassword) {
            ""
        } else {
            try { node.text?.toString().orEmpty() } catch (_: Throwable) { "" }
        }

        val selStart = try { node.textSelectionStart } catch (_: Throwable) { -1 }
        val selEnd = try { node.textSelectionEnd } catch (_: Throwable) { -1 }
        val hasKnownSelection = selStart in 0..currentText.length && selEnd in 0..currentText.length
        val canSetText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            (node.isEditable || supportsSetText(node))

        // Strategy A: Direct ACTION_SET_TEXT first whenever:
        // - The field is empty / showing placeholder hint (works across EditText, Compose TextField, WebView <input>), OR
        // - The field is non-password and exposes explicit cursor/selection bounds (bypasses Android 12-15
        //   background clipboard restrictions completely and avoids pasting stale clipboard items).
        val preferDirectSetTextFirst = canSetText && !isPassword && (currentText.isEmpty() || hasKnownSelection)

        if (preferDirectSetTextFirst) {
            if (performDirectSetText(node, currentText, selStart, selEnd, textToInsert)) {
                DebugLogManager.logInfo(TAG, "Injected dictated text via direct ACTION_SET_TEXT (${textToInsert.length} chars)")
                return true
            }
            if (performClipboardPaste(node, textToInsert)) {
                DebugLogManager.logInfo(TAG, "Injected dictated text via fallback ACTION_PASTE (${textToInsert.length} chars)")
                return true
            }
            return false
        }

        // Strategy B: Rich contenteditable / password / hidden-cursor non-empty field -> try ACTION_PASTE first,
        // then fall back to ACTION_SET_TEXT if paste is unsupported or rejected.
        if (performClipboardPaste(node, textToInsert)) {
            DebugLogManager.logInfo(TAG, "Injected dictated text via ACTION_PASTE (${textToInsert.length} chars)")
            return true
        }
        if (canSetText && !isPassword && performDirectSetText(node, currentText, selStart, selEnd, textToInsert)) {
            DebugLogManager.logInfo(TAG, "Injected dictated text via fallback ACTION_SET_TEXT (${textToInsert.length} chars)")
            return true
        }
        return false
    }

    private fun performDirectSetText(
        node: AccessibilityNodeInfo,
        currentText: String,
        selStart: Int,
        selEnd: Int,
        textToInsert: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val spliced = spliceTextForSetText(
                currentText = currentText,
                selectionStart = selStart,
                selectionEnd = selEnd,
                textToInsert = textToInsert
            )
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
    }

    private suspend fun performClipboardPaste(
        node: AccessibilityNodeInfo,
        textToInsert: String
    ): Boolean {
        clipboardRestoreJob?.cancel()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val oldClip = try {
            clipboard.primaryClip
        } catch (_: Throwable) {
            null
        }

        val clipSetOk = try {
            val clip = ClipData.newPlainText("OpenFlow", textToInsert)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Hide transient paste buffer from the Android 13+ clipboard preview popup
                clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clipboard.setPrimaryClip(clip)
            true
        } catch (_: Throwable) {
            false
        }
        if (!clipSetOk) return false

        delay(55L)

        val pasted = try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) {
            false
        }

        if (pasted) {
            // Restore previous clipboard asynchronously after giving WebView / Electron / Compose
            // async paste handlers ample time (750ms) to read the primary clip.
            clipboardRestoreJob = serviceScope.launch {
                delay(CLIPBOARD_RESTORE_DELAY_MS)
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
        return pasted
    }
}
