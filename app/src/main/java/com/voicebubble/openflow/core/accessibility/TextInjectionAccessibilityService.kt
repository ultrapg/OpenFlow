package com.voicebubble.openflow.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference
import android.os.Build

class TextInjectionAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: WeakReference<TextInjectionAccessibilityService>? = null
        val isInputFieldFocused = kotlinx.coroutines.flow.MutableStateFlow(false)
        
        fun getSharedInstance(): TextInjectionAccessibilityService? {
            return instance?.get()
        }
    }

    private var pollingJob: kotlinx.coroutines.Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        
        pollingJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val rootNode = rootInActiveWindow
                    var isFocused = false
                    if (rootNode != null) {
                        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        isFocused = focusedNode != null && focusedNode.isEditable
                        focusedNode?.recycle()
                        rootNode.recycle()
                    }

                    // Check if keyboard is visible
                    var isKeyboardVisible = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val allWindows = windows
                        for (window in allWindows) {
                            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                                isKeyboardVisible = true
                            }
                            window.recycle()
                        }
                    } else {
                        // Fallback for old Android versions
                        isKeyboardVisible = isFocused
                    }
                    
                    isInputFieldFocused.value = isFocused && isKeyboardVisible
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isInputFieldFocused.value = false
        pollingJob?.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We now rely purely on the polling job for focus + keyboard detection
    }

    override fun onInterrupt() {}

    fun injectTextDirectly(textToInsert: String): Boolean {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val oldClip = clipboard.primaryClip
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            // Set new text to clipboard
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OpenFlow", textToInsert))
            
            // Wait for clipboard propagation without blocking main thread
            kotlinx.coroutines.delay(100)
            
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                restoreClipboard(clipboard, oldClip)
                return@launch
            }
            
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && focusedNode.isEditable) {
                var success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                
                if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // Fallback to ACTION_SET_TEXT
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
                    
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                    success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    
                    if (success) {
                        val newCursorPos = if (start in 0..currentText.length) minOf(start, end) + textToInsert.length else newText.length
                        val selectionArgs = Bundle()
                        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                        selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                    }
                }
                focusedNode.recycle()
            }
            rootNode.recycle()
            
            // Restore old clip after target app had time to paste
            kotlinx.coroutines.delay(300)
            restoreClipboard(clipboard, oldClip)
        }
        
        return true
    }

    private fun restoreClipboard(clipboard: android.content.ClipboardManager, oldClip: android.content.ClipData?) {
        try {
            if (oldClip != null) {
                clipboard.setPrimaryClip(oldClip)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
