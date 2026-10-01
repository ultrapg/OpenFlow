package com.voicebubble.openflow.overlay

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.voicebubble.openflow.ui.theme.OpenFlowTheme
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.voicebubble.openflow.core.accessibility.TextInjectionAccessibilityService
import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.database.AppDatabase
import com.voicebubble.openflow.core.database.TranscriptionRecord
import com.voicebubble.openflow.core.speech.SherpaSpeechEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

enum class OverlayBubbleState {
    IDLE,
    RECORDING,
    PROCESSING,
    SUCCESS
}

class FloatingBubbleService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val audioController = AudioRecordingController()
    private var speechEngine: SherpaSpeechEngine? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentModelType: String = "nemo-canary-180m"

    private val bubbleState = MutableStateFlow(OverlayBubbleState.IDLE)
    private val processingProgress = MutableStateFlow("")


    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "language") {
            loadModel(currentModelType)
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadModel(currentModelType)
        setupOverlayView()
        val prefs = getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startCustomForeground()
        return START_STICKY
    }

    private var loadModelJob: Job? = null

    private fun loadModel(modelType: String) {
        val previousJob = loadModelJob
        loadModelJob = serviceScope.launch(Dispatchers.IO) {
            previousJob?.cancelAndJoin()
            val prefs = getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
            val selectedLang = prefs.getString("language", "auto") ?: "auto"

            currentModelType = modelType
            speechEngine?.release()
            speechEngine = null

            val modelDir = File(filesDir, "models/$modelType")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            val lidDir = File(filesDir, "models/lid/sherpa-onnx-whisper-tiny")
            if (selectedLang == "auto" && !lidDir.exists()) {
                lidDir.mkdirs()
            }

            val hotwordsFile = File(filesDir, "hotwords.txt")
            if (!hotwordsFile.exists()) {
                try {
                    hotwordsFile.writeText("OpenFlow\nTranskription")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val copyAssets = { assetsPath: String, targetDir: File ->
                try {
                    val assetFiles = assets.list(assetsPath)
                    if (assetFiles != null && assetFiles.isNotEmpty()) {
                        for (filename in assetFiles) {
                            val outFile = File(targetDir, filename)
                            if (outFile.exists() && outFile.length() > 0) continue
                            try {
                                this@FloatingBubbleService.assets.open("$assetsPath/$filename").use { inputStream ->
                                    FileOutputStream(outFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            copyAssets("models/$modelType", modelDir)
            if (selectedLang == "auto") {
                copyAssets("models/lid/sherpa-onnx-whisper-tiny", lidDir)
            }

            val tokensFile = "$modelDir/tokens.txt"
            if (File(modelDir, "encoder.int8.onnx").exists() && File(tokensFile).exists()) {
                try {
                    speechEngine = SherpaSpeechEngine(
                        modelType = modelType,
                        modelPath = modelDir.absolutePath,
                        tokensPath = tokensFile,
                        hotwordsPath = hotwordsFile.absolutePath,
                        lidPath = if (selectedLang == "auto") lidDir.absolutePath else null,
                        defaultLang = if (selectedLang == "auto") "de" else selectedLang
                    ).apply { initialize() }
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@FloatingBubbleService,
                            "Modell geladen",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    modelDir.deleteRecursively()
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@FloatingBubbleService,
                            "Fehler beim Laden",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@FloatingBubbleService,
                        "Fehler: Modell-Dateien fehlen!",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun startCustomForeground() {
        val channelId = "bubble_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Spracheingabe-Dienst",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.voicebubble.openflow.MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OpenFlow aktiv")
            .setContentText("Tippe auf die Bubble zum Diktieren.")
            .setSmallIcon(com.voicebubble.openflow.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    1001,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun setupOverlayView() {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 80
            y = 400
        }

        val themeContext = android.view.ContextThemeWrapper(this, com.voicebubble.openflow.R.style.Theme_OpenFlow)
        composeView = ComposeView(themeContext).apply {
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)

            setContent {
                OpenFlowTheme {
                    val currentState by bubbleState.collectAsState()
                    val isFocused by TextInjectionAccessibilityService.isInputFieldFocused.collectAsState()
                    val prefs = androidx.compose.runtime.remember { getSharedPreferences("openflow_settings", Context.MODE_PRIVATE) }
                    var showOnlyOnInputs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("show_only_on_inputs", true)) }
                    var bubbleSize by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getFloat("bubble_size", 52f)) }
                    
                    val listener = androidx.compose.runtime.remember {
                        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                            if (sharedPreferences != null && key != null) {
                                when (key) {
                                    "show_only_on_inputs" -> showOnlyOnInputs = sharedPreferences.getBoolean(key, true)
                                    "bubble_size" -> bubbleSize = sharedPreferences.getFloat(key, 52f)
                                }
                            }
                        }
                    }
                    
                    androidx.compose.runtime.DisposableEffect(prefs, listener) {
                        prefs.registerOnSharedPreferenceChangeListener(listener)
                        onDispose {
                            prefs.unregisterOnSharedPreferenceChangeListener(listener)
                        }
                    }
                    val isEffectivelyFocused = if (showOnlyOnInputs) isFocused else true

                    BubbleOverlayComponent(
                        audioController = audioController,
                        bubbleState = currentState,
                        isInputFieldFocused = isEffectivelyFocused,
                        processingProgress = processingProgress.collectAsState(initial = "").value,
                        bubbleSize = bubbleSize,
                        onDragDelta = { dx, dy ->
                            this@FloatingBubbleService.layoutParams.x += dx.toInt()
                            this@FloatingBubbleService.layoutParams.y += dy.toInt()
                            try {
                                windowManager.updateViewLayout(
                                    composeView,
                                    this@FloatingBubbleService.layoutParams
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        onStartRecording = {
                            bubbleState.value = OverlayBubbleState.RECORDING
                            if (prefs.getBoolean("haptic_feedback", true)) vibrate(20, 180)
                            setKeepScreenOn(true)
                            serviceScope.launch {
                                audioController.startRecording(this)
                            }
                        },
                        onStopRecording = {
                            bubbleState.value = OverlayBubbleState.PROCESSING
                            setKeepScreenOn(false)
                            serviceScope.launch {
                                val samples = audioController.stopRecording()
                                processAudio(samples)
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(composeView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "Fehler: Overlay-Berechtigung fehlt!", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun setKeepScreenOn(keep: Boolean) {
        if (keep) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            if (composeView.isAttachedToWindow) {
                windowManager.updateViewLayout(composeView, layoutParams)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun vibrate(duration: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) {}
    }

    private fun processAudio(samples: FloatArray) {
        val prefs = getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)

        serviceScope.launch {
            try {
                processingProgress.value = ""

                var chunkText = speechEngine?.transcribe(samples) { current, total ->
                    if (total > 1) {
                        processingProgress.value = "$current / $total"
                    }
                } ?: ""

                var finalText = chunkText.trim()
                    .replace(Regex(" +([.,!?:;%€])"), "$1")
                    .replace("O K", "OK")
                    .replace("o k", "ok")
                    .replace(Regex("\\s+"), " ")

                if (finalText.isNotBlank()) {
                    val isFocused = TextInjectionAccessibilityService.isInputFieldFocused.value
                    if (isFocused) {
                        val accessibilityService = TextInjectionAccessibilityService.getSharedInstance()
                        accessibilityService?.injectTextDirectly(finalText)
                    } else {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlow", finalText))
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(this@FloatingBubbleService, "In Zwischenablage kopiert", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }

                    if (prefs.getBoolean("haptic_feedback", true)) vibrate(15, 120)

                    try {
                        val dao = AppDatabase.getDatabase(this@FloatingBubbleService).transcriptionDao()
                        dao.insert(
                            TranscriptionRecord(
                                transcribedText = finalText,
                                durationMs = (samples.size / 16f).toLong(),
                                audioSamplesCount = samples.size,
                                modelIdentifier = currentModelType,
                                wasInjectedSuccessfully = isFocused
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                processingProgress.value = ""
                bubbleState.value = OverlayBubbleState.SUCCESS
                kotlinx.coroutines.delay(1500)
                bubbleState.value = OverlayBubbleState.IDLE

            } catch (e: Exception) {
                e.printStackTrace()
                processingProgress.value = ""
                bubbleState.value = OverlayBubbleState.IDLE
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@FloatingBubbleService,
                        "Verarbeitungsfehler: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences("openflow_settings", Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        GlobalScope.launch { audioController.release() }
        speechEngine?.release()
        serviceScope.cancel()
        if (::composeView.isInitialized) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
