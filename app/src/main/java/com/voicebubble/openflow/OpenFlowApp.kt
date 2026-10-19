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
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.core.speech.SmartPipeline

class OpenFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLogManager.init(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                DebugLogManager.logCrash(thread, exception)
            } catch (_: Throwable) {
                // Ignore errors while writing crash log
            } finally {
                defaultHandler?.uncaughtException(thread, exception)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLogManager.logTrimMemory("OpenFlowApp", level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            SmartPipeline.getInstance(this).onTrimMemory(level)
        }
    }
}
