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

package com.voicebubble.openflow

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.voicebubble.openflow.core.speech.SmartPipeline
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OpenFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                val sw = StringWriter()
                exception.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logMessage = "CRASH at $time\nThread: ${thread.name}\n$stackTrace\n\n"

                val crashFile = File(filesDir, "crash_log.txt")
                crashFile.appendText(logMessage)
                Log.e("OpenFlowApp", "FATAL CRASH: $logMessage")
            } catch (e: Exception) {
                // Ignore errors while writing crash log
            } finally {
                defaultHandler?.uncaughtException(thread, exception)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            SmartPipeline.getInstance(this).onTrimMemory(level)
        }
    }
}
