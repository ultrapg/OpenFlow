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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.voicebubble.openflow.core.debug.DebugLogManager

/**
 * Handles automatic startup of [FloatingBubbleService] on device boot, app package updates,
 * task removal, and automatic crash recovery.
 */
class BootAndRestartReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTART_BUBBLE = "com.voicebubble.openflow.ACTION_RESTART_BUBBLE"
        private const val EXTRA_REASON = "restart_reason"
        private const val REQUEST_CODE_RESTART = 2049

        private const val CRASH_PREFS_NAME = "openflow_crash_guard"
        private const val KEY_CRASH_TIMESTAMPS = "recent_crash_timestamps"
        private const val CRASH_WINDOW_MS = 60_000L
        private const val MAX_CRASH_RESTARTS_IN_WINDOW = 3

        /**
         * Records a crash timestamp synchronously and returns `true` if automatic restart is safe
         * (fewer than [MAX_CRASH_RESTARTS_IN_WINDOW] crashes within the last [CRASH_WINDOW_MS]).
         */
        fun recordCrashAndAllowRestart(context: Context): Boolean {
            return try {
                val deviceContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.applicationContext.createDeviceProtectedStorageContext()
                } else {
                    context.applicationContext
                }
                val prefs = deviceContext.getSharedPreferences(CRASH_PREFS_NAME, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val recent = parseRecentCrashTimestamps(
                    raw = prefs.getString(KEY_CRASH_TIMESTAMPS, null),
                    nowMs = now,
                    windowMs = CRASH_WINDOW_MS
                ).toMutableList()

                if (recent.size >= MAX_CRASH_RESTARTS_IN_WINDOW) {
                    false
                } else {
                    recent.add(now)
                    prefs.edit()
                        .putString(KEY_CRASH_TIMESTAMPS, recent.joinToString(","))
                        .commit()
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }

        internal fun parseRecentCrashTimestamps(
            raw: String?,
            nowMs: Long,
            windowMs: Long = CRASH_WINDOW_MS
        ): List<Long> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { ts -> nowMs - ts in 0L..windowMs }
        }

        /**
         * Schedules a one-shot wakeup alarm to restart [FloatingBubbleService] after [delayMs].
         */
        fun scheduleRestart(context: Context, delayMs: Long = 1_200L, reason: String = "auto_restart") {
            try {
                val appContext = context.applicationContext
                if (!FloatingBubbleService.shouldAutoStart(appContext)) return

                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(appContext, BootAndRestartReceiver::class.java).apply {
                    action = ACTION_RESTART_BUBBLE
                    putExtra(EXTRA_REASON, reason)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    REQUEST_CODE_RESTART,
                    intent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(500L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
                DebugLogManager.logInfo(
                    "BootAndRestartReceiver",
                    "Scheduled FloatingBubbleService restart in ${delayMs}ms (reason=$reason)"
                )
            } catch (t: Throwable) {
                DebugLogManager.logError("BootAndRestartReceiver", "Failed to schedule service restart", t)
            }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: return
        val reason = intent.getStringExtra(EXTRA_REASON) ?: action

        val isSupportedAction = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == ACTION_RESTART_BUBBLE

        if (!isSupportedAction) return

        DebugLogManager.logInfo("BootAndRestartReceiver", "onReceive action=$action reason=$reason")
        FloatingBubbleService.ensureRunningIfEnabled(context.applicationContext, reason = reason)
    }
}
