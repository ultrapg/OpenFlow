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

package com.voicebubble.openflow.core.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent crash, OOM, native exit, and pipeline debug logger.
 *
 * Can be secretly unlocked by tapping the version label 5 times fast under the app title.
 * Captures:
 * - Uncaught JVM exceptions & OutOfMemoryErrors
 * - Android 11+ (API 30+) historical [ApplicationExitInfo] records (including `REASON_LOW_MEMORY`
 *   LMKD kills, `REASON_CRASH_NATIVE` SIGSEGV/SIGABRT, and `REASON_ANR` from previous process deaths)
 * - Live JVM heap, native heap (`Debug.getNativeHeapAllocatedSize()`), and system `ActivityManager.MemoryInfo`
 *   snapshots before/after model loads, inference stages, and `onTrimMemory` events
 * - Persistent rolling log files (`debug_log.txt` and `crash_log.txt`) on disk so logs survive OOM kills.
 */
object DebugLogManager {
    private const val TAG = "OpenFlowDebug"
    private const val PREFS_NAME = "openflow_debug_prefs"
    private const val KEY_UNLOCKED = "secret_debug_unlocked"
    private const val KEY_LOGGING_ENABLED = "debug_logging_active"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_recorded_exit_ts"

    private const val DEBUG_LOG_FILE = "debug_log.txt"
    private const val CRASH_LOG_FILE = "crash_log.txt"
    private const val MAX_LOG_BYTES = 512 * 1024L // 512 KB cap per file

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isLoggingEnabled = MutableStateFlow(true)
    val isLoggingEnabled: StateFlow<Boolean> = _isLoggingEnabled.asStateFlow()

    private val _logUpdateTick = MutableStateFlow(0)
    val logUpdateTick: StateFlow<Int> = _logUpdateTick.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isUnlocked.value = prefs.getBoolean(KEY_UNLOCKED, false)
        _isLoggingEnabled.value = prefs.getBoolean(KEY_LOGGING_ENABLED, true)

        logInfo(
            TAG,
            "=== Process Started (PID ${Process.myPid()}) • Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) ==="
        )
        logMemorySnapshot("App Startup")
        inspectPreviousProcessExitReasons(ctx)
    }

    fun setUnlocked(context: Context, unlocked: Boolean) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_UNLOCKED, unlocked)
            .putBoolean(KEY_LOGGING_ENABLED, true)
            .apply()
        _isUnlocked.value = unlocked
        _isLoggingEnabled.value = true
        logInfo(TAG, "Secret debug & crash logger ${if (unlocked) "UNLOCKED" else "HIDDEN"} by user")
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        appContext = ctx
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOGGING_ENABLED, enabled)
            .apply()
        _isLoggingEnabled.value = enabled
        appendRawLog(
            isCrash = false,
            level = "INFO",
            tag = TAG,
            message = "Persistent debug file logging ${if (enabled) "ENABLED" else "PAUSED"} (${formatMemorySummary(ctx)})"
        )
    }

    fun logInfo(tag: String, message: String, includeMemory: Boolean = false) {
        val ctx = appContext
        val fullMsg = if (includeMemory && ctx != null) {
            "$message | ${formatMemorySummary(ctx)}"
        } else {
            message
        }
        Log.i(tag, fullMsg)
        if (_isLoggingEnabled.value) {
            appendRawLog(isCrash = false, level = "INFO", tag = tag, message = fullMsg)
        }
    }

    fun logWarn(tag: String, message: String, throwable: Throwable? = null) {
        val ctx = appContext
        val mem = if (ctx != null) " | ${formatMemorySummary(ctx)}" else ""
        val trace = throwable?.let { "\n" + stackTraceToString(it) } ?: ""
        val fullMsg = "$message$mem$trace"
        Log.w(tag, fullMsg)
        if (_isLoggingEnabled.value) {
            appendRawLog(isCrash = false, level = "WARN", tag = tag, message = fullMsg)
        }
    }

    fun logWarning(tag: String, message: String, throwable: Throwable? = null) {
        logWarn(tag, message, throwable)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val ctx = appContext
        val mem = if (ctx != null) " | ${formatMemorySummary(ctx)}" else ""
        val trace = throwable?.let { "\n" + stackTraceToString(it) } ?: ""
        val fullMsg = "$message$mem$trace"
        Log.e(tag, fullMsg)
        // Always record errors even if verbose logging is paused
        appendRawLog(isCrash = false, level = "ERROR", tag = tag, message = fullMsg)
    }

    fun logCrash(thread: Thread, throwable: Throwable) {
        val ctx = appContext
        val mem = if (ctx != null) formatMemorySummary(ctx) else "Memory snapshot unavailable"
        val isOom = isOutOfMemoryCause(throwable)
        val header = if (isOom) {
            "FATAL OUT-OF-MEMORY (OOM) CRASH on thread '${thread.name}'"
        } else {
            "FATAL UNCAUGHT EXCEPTION on thread '${thread.name}'"
        }
        val body = buildString {
            appendLine(header)
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("Memory State: $mem")
            appendLine("Stack Trace:")
            append(stackTraceToString(throwable))
        }
        Log.e(TAG, body)
        appendRawLog(isCrash = true, level = "CRASH", tag = TAG, message = body)
        appendRawLog(isCrash = false, level = "CRASH", tag = TAG, message = body)
    }

    fun logTrimMemory(source: String, level: Int) {
        val levelName = trimLevelToString(level)
        logWarn(TAG, "OS Memory Pressure [$source]: $levelName (level=$level)")
    }

    fun logMemorySnapshot(stage: String) {
        val ctx = appContext ?: return
        if (!_isLoggingEnabled.value) return
        appendRawLog(
            isCrash = false,
            level = "MEM",
            tag = TAG,
            message = "$stage -> ${formatMemorySummary(ctx)}"
        )
    }

    /**
     * Checks Android 11+ [ActivityManager.getHistoricalProcessExitReasons] to detect silent OOM kills
     * (`REASON_LOW_MEMORY`), native LiteRT/OpenCL crashes (`REASON_CRASH_NATIVE`), or ANRs from the
     * previous session that could not be caught by a JVM `UncaughtExceptionHandler`.
     */
    fun inspectPreviousProcessExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastRecordedTs = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)

            val exitList = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exitList.isEmpty()) return

            var newestTs = lastRecordedTs
            for (exitInfo in exitList.reversed()) {
                val exitTs = exitInfo.timestamp
                if (exitTs <= lastRecordedTs) continue
                if (exitTs > newestTs) newestTs = exitTs

                val reasonCode = exitInfo.reason
                val reasonName = exitReasonToString(reasonCode)
                val pssMb = exitInfo.pss / 1024L // kB -> MB
                val rssMb = exitInfo.rss / 1024L // kB -> MB
                val exitTimeStr = formatTimestamp(exitTs)
                val desc = exitInfo.description ?: "none"

                val summary = buildString {
                    append("Previous Process Exit Detected ($exitTimeStr): ")
                    append("Reason=$reasonName (code=$reasonCode, status=${exitInfo.status}), ")
                    append("Last PSS=${pssMb}MB, Last RSS=${rssMb}MB, ")
                    append("Importance=${exitInfo.importance}, Description=$desc")
                }

                val isAbnormalKill = reasonCode == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    reasonCode == ApplicationExitInfo.REASON_CRASH ||
                    reasonCode == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    reasonCode == ApplicationExitInfo.REASON_ANR ||
                    reasonCode == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                    reasonCode == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE

                if (isAbnormalKill) {
                    appendRawLog(isCrash = true, level = "OS_EXIT", tag = TAG, message = summary)
                }
                appendRawLog(isCrash = false, level = "OS_EXIT", tag = TAG, message = summary)
            }

            if (newestTs > lastRecordedTs) {
                prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, newestTs).apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not inspect historical process exit reasons: ${t.message}")
        }
    }

    fun formatMemorySummary(context: Context): String {
        return try {
            val rt = Runtime.getRuntime()
            val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
            val jvmMaxMb = rt.maxMemory() / (1024L * 1024L)
            val nativeAllocMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            val nativeSizeMb = Debug.getNativeHeapSize() / (1024L * 1024L)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val sysInfo = if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val availMb = memInfo.availMem / (1024L * 1024L)
                val totalMb = memInfo.totalMem / (1024L * 1024L)
                val threshMb = memInfo.threshold / (1024L * 1024L)
                "SysRAM: ${availMb}/${totalMb}MB free (lowMem=${memInfo.lowMemory}, thresh=${threshMb}MB)"
            } else {
                "SysRAM: N/A"
            }

            "JVM: ${jvmUsedMb}/${jvmMaxMb}MB | NativeHeap: ${nativeAllocMb}MB (pool ${nativeSizeMb}MB) | $sysInfo"
        } catch (_: Throwable) {
            "Memory stats unavailable"
        }
    }

    fun readCombinedLogs(context: Context, maxChars: Int = 45_000): String {
        synchronized(lock) {
            val crashFile = File(context.filesDir, CRASH_LOG_FILE)
            val debugFile = File(context.filesDir, DEBUG_LOG_FILE)

            return buildString {
                appendLine("=== OPENFLOW DIAGNOSTIC & CRASH REPORT ===")
                appendLine("Generated: ${formatTimestamp(System.currentTimeMillis())}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT}) • Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("Live Memory: ${formatMemorySummary(context)}")
                appendLine()

                appendLine("--- CRASH & OS KILL LOG (${crashFile.name}) ---")
                if (crashFile.exists() && crashFile.length() > 0L) {
                    appendLine(readTailText(crashFile, maxChars / 2))
                } else {
                    appendLine("No fatal crashes or OS OOM kills recorded.")
                }
                appendLine()

                appendLine("--- DEBUG & MEMORY TELEMETRY LOG (${debugFile.name}) ---")
                if (debugFile.exists() && debugFile.length() > 0L) {
                    appendLine(readTailText(debugFile, maxChars / 2))
                } else {
                    appendLine("No debug log entries recorded yet.")
                }
            }
        }
    }

    fun getLogFilesSizeSummary(context: Context): String {
        val crashLen = File(context.filesDir, CRASH_LOG_FILE).let { if (it.exists()) it.length() else 0L }
        val debugLen = File(context.filesDir, DEBUG_LOG_FILE).let { if (it.exists()) it.length() else 0L }
        val totalKb = (crashLen + debugLen) / 1024.0
        return String.format(Locale.US, "%.1f KB (crash: %.1f KB, debug: %.1f KB)", totalKb, crashLen / 1024.0, debugLen / 1024.0)
    }

    fun clearLogs(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, CRASH_LOG_FILE).delete()
                File(context.filesDir, DEBUG_LOG_FILE).delete()
            } catch (_: Throwable) {
            }
        }
        logInfo(TAG, "Logs cleared by user", includeMemory = true)
    }

    private fun appendRawLog(isCrash: Boolean, level: String, tag: String, message: String) {
        val ctx = appContext ?: return
        val fileName = if (isCrash) CRASH_LOG_FILE else DEBUG_LOG_FILE
        val timestamp = formatTimestamp(System.currentTimeMillis())
        val line = "[$timestamp] [$level/$tag] $message\n"

        synchronized(lock) {
            try {
                val file = File(ctx.filesDir, fileName)
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    trimLogFileInPlace(file, MAX_LOG_BYTES / 2)
                }
                file.appendText(line)
                _logUpdateTick.value = _logUpdateTick.value + 1
            } catch (_: Throwable) {
            }
        }
    }

    private fun trimLogFileInPlace(file: File, keepBytes: Long) {
        try {
            val len = file.length()
            if (len <= keepBytes) return
            val tailBytes = ByteArray(keepBytes.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - keepBytes)
                raf.readFully(tailBytes)
            }
            val firstNewlineIdx = tailBytes.indexOf('\n'.code.toByte())
            val startOffset = if (firstNewlineIdx in 0 until tailBytes.lastIndex) firstNewlineIdx + 1 else 0
            val alignedStr = String(tailBytes, startOffset, tailBytes.size - startOffset, Charsets.UTF_8)
            val text = "--- [Older log entries truncated] ---\n$alignedStr"
            file.writeText(text)
        } catch (_: Throwable) {
        }
    }

    internal fun readTailText(file: File, maxChars: Int): String {
        return try {
            val len = file.length()
            if (len <= 0L) return ""
            // Read at most maxChars * 3 bytes from the end of the file to avoid loading multi-hundred-KB files into heap
            val maxBytesToRead = (maxChars.toLong() * 3L).coerceAtLeast(4_096L)
            if (len <= maxBytesToRead) {
                val text = file.readText()
                if (text.length <= maxChars) {
                    text.trimEnd()
                } else {
                    "... [Showing last $maxChars chars] ...\n" + text.takeLast(maxChars).trimEnd()
                }
            } else {
                val tailBytes = ByteArray(maxBytesToRead.toInt())
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(len - maxBytesToRead)
                    raf.readFully(tailBytes)
                }
                val firstNewlineIdx = tailBytes.indexOf('\n'.code.toByte())
                val startOffset = if (firstNewlineIdx in 0 until tailBytes.lastIndex) firstNewlineIdx + 1 else 0
                val decoded = String(tailBytes, startOffset, tailBytes.size - startOffset, Charsets.UTF_8)
                val tail = if (decoded.length <= maxChars) decoded.trimEnd() else decoded.takeLast(maxChars).trimEnd()
                "... [Showing last $maxChars chars] ...\n$tail"
            }
        } catch (t: Throwable) {
            "Failed to read log file: ${t.message}"
        }
    }

    private fun isOutOfMemoryCause(throwable: Throwable?): Boolean {
        var curr = throwable
        var depth = 0
        while (curr != null && depth < 10) {
            if (curr is OutOfMemoryError || curr.message?.contains("OutOfMemory", ignoreCase = true) == true) {
                return true
            }
            curr = curr.cause
            depth++
        }
        return false
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private fun formatTimestamp(epochMs: Long): String {
        return timestampFormat.get()!!.format(Date(epochMs))
    }

    private fun trimLevelToString(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
        else -> "TRIM_LEVEL_$level"
    }

    private fun exitReasonToString(reason: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "UNKNOWN($reason)"
        return when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (OOM / LMKD Kill)"
            ApplicationExitInfo.REASON_CRASH -> "JVM_CRASH (Unhandled Exception)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH (SIGSEGV / SIGABRT / LiteRT / OpenCL)"
            ApplicationExitInfo.REASON_ANR -> "ANR (Application Not Responding)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (Swipe Away / Force Stop)"
            ApplicationExitInfo.REASON_SIGNALED -> "OS_SIGNALED"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            else -> "REASON_$reason"
        }
    }
}
