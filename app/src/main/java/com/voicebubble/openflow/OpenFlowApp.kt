package com.voicebubble.openflow

import android.app.Application
import android.util.Log
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
}
