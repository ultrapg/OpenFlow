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

package com.voicebubble.openflow.settings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voicebubble.openflow.MainActivity
import com.voicebubble.openflow.R
import com.voicebubble.openflow.core.speech.SmartPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ModelFileArtifact(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String
)

enum class ModelQueueStatus {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    DONE,
    PAUSED,
    ERROR
}

data class DownloadSessionState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val activeModel: DesertAntModel? = null,
    val currentFileName: String = "",
    val currentModelIndex: Int = 0,
    val totalModelsInQueue: Int = 0,
    val modelDownloadedBytes: Long = 0L,
    val modelTotalBytes: Long = 0L,
    val overallDownloadedBytes: Long = 0L,
    val overallTotalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val statusNote: String? = null,
    val errorMessage: String? = null,
    val modelStatuses: Map<DesertAntModel, ModelQueueStatus> = emptyMap()
) {
    val modelProgress: Float
        get() = if (modelTotalBytes > 0L) {
            (modelDownloadedBytes.toDouble() / modelTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val overallProgress: Float
        get() = if (overallTotalBytes > 0L) {
            (overallDownloadedBytes.toDouble() / overallTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val formattedSpeed: String
        get() = formatSpeed(speedBytesPerSec)

    val formattedEta: String
        get() = formatEta(etaSeconds)

    val formattedModelBytes: String
        get() = "${formatBytesMb(modelDownloadedBytes)} / ${formatBytesMb(modelTotalBytes)}"

    val formattedOverallBytes: String
        get() = "${formatBytesMb(overallDownloadedBytes)} / ${formatBytesMb(overallTotalBytes)}"

    companion object {
        fun formatBytesMb(bytes: Long): String {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 100.0) {
                String.format(Locale.US, "%.0f MB", mb)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "Connecting..."
            val kb = bytesPerSec.toDouble() / 1024.0
            return if (kb >= 1024.0) {
                String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
            } else {
                String.format(Locale.US, "%.0f KB/s", kb)
            }
        }

        fun formatEta(seconds: Long?): String {
            if (seconds == null || seconds < 0L) return "Calculating time left..."
            if (seconds == 0L) return "Almost done..."
            val hours = seconds / 3600L
            val mins = (seconds % 3600L) / 60L
            val secs = seconds % 60L
            return when {
                hours > 0L -> String.format(Locale.US, "~%dh %02dm left", hours, mins)
                mins > 0L -> String.format(Locale.US, "~%dm %02ds left", mins, secs)
                else -> String.format(Locale.US, "~%ds left", secs)
            }
        }
    }
}

/**
 * Process-wide manager for resumable, slow-network-resilient background model downloads.
 *
 * Features:
 * - HTTP Range (`Range: bytes=<offset>-`) support for seamless resume across pauses, network drops,
 *   and app restarts.
 * - Automatic exponential backoff retry (up to 15 attempts per stall) with generous socket timeouts
 *   (45s connect, 90s read) for slow or unstable connections.
 * - Real-time moving-average download speed (`MB/s` or `KB/s`) and ETA calculation.
 * - Foreground service (`ModelDownloadService`) with partial WakeLock so downloads continue in the
 *   background when the user switches apps or locks the screen.
 */
object ModelDownloadManager {
    private const val TAG = "ModelDownloadManager"
    private const val MAX_STALL_RETRIES = 15
    private const val CONNECT_TIMEOUT_MS = 45_000
    private const val READ_TIMEOUT_MS = 90_000

    val MODEL_SPECS: Map<DesertAntModel, Pair<String, List<ModelFileArtifact>>> = mapOf(
        DesertAntModel.CLEAR to (
            "v0.3.0" to listOf(
                ModelFileArtifact(
                    relativePath = "clear-studio.tflite",
                    sizeBytes = 49_285_108L,
                    sha256 = "fe2438e4e1137455298321ca952c5d3a4d7dde27eca6b8a495789723212fc79f"
                )
            )
            ),
        DesertAntModel.EAR to (
            "v0.2.0" to listOf(
                ModelFileArtifact(
                    relativePath = "ear.tflite",
                    sizeBytes = 10_671_552L,
                    sha256 = "a19b43892abe53afba50122a861b10fd76f312a5027a5c659b0738081135feeb"
                ),
                ModelFileArtifact(
                    relativePath = "languages.json",
                    sizeBytes = 628L,
                    sha256 = "0a08f30dec26ad25730a1fa9375a5d693f5a159032d0cd7818007b7dbc3eae18"
                ),
                ModelFileArtifact(
                    relativePath = "ear_meta.json",
                    sizeBytes = 2_190L,
                    sha256 = "538d66c747024d4ef2ae33aef273817c7baea21171430fef0c2713a66bb6af18"
                ),
                ModelFileArtifact(
                    relativePath = "mel_filters.f32",
                    sizeBytes = 64_328L,
                    sha256 = "a3099fcba1a7f30f229b230011146ea0140db8de4f467b44aaa5de70bcc0f325"
                )
            )
            ),
        DesertAntModel.UHM to (
            "v1.1.0" to listOf(
                ModelFileArtifact(
                    relativePath = "labels.json",
                    sizeBytes = 237L,
                    sha256 = "9b3a8579b0185771f137873e5cbcbdd67572c34fdbeeac778580170c9248f9a3"
                ),
                ModelFileArtifact(
                    relativePath = "uhm.tflite",
                    sizeBytes = 23_919_472L,
                    sha256 = "9bb5eaa39bb56306ca58692242117b66728ccd169abe2f215ba50063ef0e8261"
                )
            )
            ),
        DesertAntModel.VOZ to (
            "v0.4.0" to listOf(
                ModelFileArtifact(
                    relativePath = "encoder.tflite",
                    sizeBytes = 1_207_525_456L,
                    sha256 = "1152abb5fce2cf50b57b1cd80a1bf70320c32b20656f835bfe08c7faa5c3f885"
                ),
                ModelFileArtifact(
                    relativePath = "mel.tflite",
                    sizeBytes = 1_663_424L,
                    sha256 = "505d80fc1be255c115c715a6cffdb55e51ff22465bca820e5feb59f0cccabe09"
                ),
                ModelFileArtifact(
                    relativePath = "decoder.tflite",
                    sizeBytes = 24_503_072L,
                    sha256 = "429ff2b574455733b388f1667d05d6764ac6086417b94d4cc24edabd25d52591"
                ),
                ModelFileArtifact(
                    relativePath = "meta.litert.json",
                    sizeBytes = 579L,
                    sha256 = "a8ad38f4144d12445dce0e32a723859816596c1c667906c062df97c94a81e741"
                ),
                ModelFileArtifact(
                    relativePath = "vocab.json",
                    sizeBytes = 78_654L,
                    sha256 = "3eb2f73e8853dce5156c2ed97c0b1bb84eb4ebd201eb1761d15977fc1ea31490"
                ),
                ModelFileArtifact(
                    relativePath = "embedding.f16",
                    sizeBytes = 10_487_040L,
                    sha256 = "07163e5f6f8a68f9ffeed7a8ff770c68098c2db216232321912188bd614b9a72"
                )
            )
            )
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    private val _sessionState = MutableStateFlow(DownloadSessionState())
    val sessionState: StateFlow<DownloadSessionState> = _sessionState.asStateFlow()

    fun modelTotalBytes(model: DesertAntModel): Long {
        return MODEL_SPECS[model]?.second?.sumOf { it.sizeBytes } ?: model.expectedBytes
    }

    /**
     * Returns how many bytes of [model] are already present on disk (including partial `.part` files),
     * enabling instant UI display of resumable progress.
     */
    fun getCachedOrPartialBytes(context: Context, model: DesertAntModel): Long {
        val (revision, artifacts) = MODEL_SPECS[model] ?: return 0L
        val modelDir = File(DesertAntModelStorage.modelCacheRoot(context, model), revision)
        if (!modelDir.exists()) return 0L
        var sum = 0L
        for (artifact in artifacts) {
            val finalFile = File(modelDir, artifact.relativePath)
            val partFile = File(modelDir, "${artifact.relativePath}.part")
            sum += when {
                finalFile.exists() && finalFile.length() == artifact.sizeBytes -> artifact.sizeBytes
                partFile.exists() -> min(partFile.length(), artifact.sizeBytes)
                else -> 0L
            }
        }
        return sum
    }

    /**
     * Starts or resumes a background download queue for [models].
     */
    @Synchronized
    fun startQueue(
        context: Context,
        models: List<DesertAntModel>,
        onAllCompleted: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        if (_sessionState.value.isRunning) {
            return
        }

        val queue = models.distinct()
        if (queue.isEmpty()) return

        val initialStatuses = queue.associateWith { model ->
            if (DesertAntModelStorage.isModelDownloaded(appContext, model)) {
                ModelQueueStatus.DONE
            } else {
                ModelQueueStatus.QUEUED
            }
        }.toMutableMap()

        val totalBytesAll = queue.sumOf { modelTotalBytes(it) }.coerceAtLeast(1L)
        val initialDoneBytes = queue.sumOf { getCachedOrPartialBytes(appContext, it) }

        _sessionState.value = DownloadSessionState(
            isRunning = true,
            isPaused = false,
            isCompleted = false,
            activeModel = queue.firstOrNull { initialStatuses[it] != ModelQueueStatus.DONE },
            currentModelIndex = 1,
            totalModelsInQueue = queue.size,
            overallDownloadedBytes = initialDoneBytes,
            overallTotalBytes = totalBytesAll,
            modelStatuses = initialStatuses.toMap()
        )

        ModelDownloadService.start(appContext)

        activeJob = scope.launch {
            val speedTracker = RollingSpeedTracker()
            try {
                for ((idx, model) in queue.withIndex()) {
                    ensureActive()
                    val mTotal = modelTotalBytes(model)

                    if (DesertAntModelStorage.isModelDownloaded(appContext, model)) {
                        initialStatuses[model] = ModelQueueStatus.DONE
                        updateState {
                            copy(
                                activeModel = model,
                                currentModelIndex = idx + 1,
                                modelDownloadedBytes = mTotal,
                                modelTotalBytes = mTotal,
                                overallDownloadedBytes = computeQueueDownloadedBytes(appContext, queue),
                                modelStatuses = initialStatuses.toMap()
                            )
                        }
                        continue
                    }

                    initialStatuses[model] = ModelQueueStatus.DOWNLOADING
                    updateState {
                        copy(
                            activeModel = model,
                            currentModelIndex = idx + 1,
                            modelDownloadedBytes = getCachedOrPartialBytes(appContext, model),
                            modelTotalBytes = mTotal,
                            overallDownloadedBytes = computeQueueDownloadedBytes(appContext, queue),
                            statusNote = null,
                            errorMessage = null,
                            modelStatuses = initialStatuses.toMap()
                        )
                    }

                    downloadSingleModelResumable(
                        context = appContext,
                        model = model,
                        queue = queue,
                        modelIndex = idx + 1,
                        statuses = initialStatuses,
                        speedTracker = speedTracker
                    )

                    initialStatuses[model] = ModelQueueStatus.DONE
                    updateState {
                        copy(
                            modelDownloadedBytes = mTotal,
                            modelTotalBytes = mTotal,
                            overallDownloadedBytes = computeQueueDownloadedBytes(appContext, queue),
                            statusNote = null,
                            modelStatuses = initialStatuses.toMap()
                        )
                    }
                }

                // All models in queue downloaded & verified!
                val latestSettings = ModelSettings.load(appContext)
                ModelSettings.setOnboardingCompleted(appContext, true)
                SmartPipeline.getInstance(appContext).reloadSettings(latestSettings)

                updateState {
                    copy(
                        isRunning = false,
                        isPaused = false,
                        isCompleted = true,
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L,
                        statusNote = "All selected models downloaded and verified.",
                        errorMessage = null,
                        modelStatuses = initialStatuses.toMap()
                    )
                }
                withContext(Dispatchers.Main) {
                    onAllCompleted?.invoke()
                }
            } catch (ce: CancellationException) {
                val pausedStatuses = initialStatuses.mapValues { (_, st) ->
                    if (st == ModelQueueStatus.DOWNLOADING || st == ModelQueueStatus.VERIFYING) {
                        ModelQueueStatus.PAUSED
                    } else {
                        st
                    }
                }
                updateState {
                    copy(
                        isRunning = false,
                        isPaused = true,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        statusNote = "Download paused — progress saved on disk. Tap Resume anytime.",
                        modelStatuses = pausedStatuses
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Model download failed: ${t.message}", t)
                val active = _sessionState.value.activeModel
                if (active != null) {
                    initialStatuses[active] = ModelQueueStatus.ERROR
                }
                updateState {
                    copy(
                        isRunning = false,
                        isPaused = false,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        statusNote = null,
                        errorMessage = "Connection interrupted (${t.message ?: "Network error"}). Progress is saved — tap Resume to continue from ${DownloadSessionState.formatBytesMb(overallDownloadedBytes)}.",
                        modelStatuses = initialStatuses.toMap()
                    )
                }
            } finally {
                ModelDownloadService.stop(appContext)
            }
        }
    }

    @Synchronized
    fun pauseDownloads() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun downloadSingleModelResumable(
        context: Context,
        model: DesertAntModel,
        queue: List<DesertAntModel>,
        modelIndex: Int,
        statuses: MutableMap<DesertAntModel, ModelQueueStatus>,
        speedTracker: RollingSpeedTracker
    ) {
        val (revision, artifacts) = MODEL_SPECS[model]
            ?: throw IOException("Missing catalog specification for ${model.id}")
        val targetDir = File(DesertAntModelStorage.modelCacheRoot(context, model), revision)
        if (!targetDir.exists() && !targetDir.mkdirs() && !targetDir.exists()) {
            throw IOException("Cannot create model cache directory: ${targetDir.absolutePath}")
        }

        val mTotalBytes = artifacts.sumOf { it.sizeBytes }
        val overallTotal = _sessionState.value.overallTotalBytes

        for (artifact in artifacts) {
            coroutineContext.ensureActive()
            val finalFile = File(targetDir, artifact.relativePath)
            val partFile = File(targetDir, "${artifact.relativePath}.part")

            // If finalFile already exists with matching size and SHA-256, skip downloading it
            if (finalFile.exists() && finalFile.length() == artifact.sizeBytes) {
                if (verifySha256(finalFile, artifact.sha256)) {
                    continue
                } else {
                    finalFile.delete()
                }
            }

            val urlStr = "https://huggingface.co/desert-ant-labs/${model.id}/resolve/$revision/${artifact.relativePath}"

            downloadFileWithAutoResume(
                urlStr = urlStr,
                partFile = partFile,
                expectedSize = artifact.sizeBytes,
                onProgress = { _, statusHint ->
                    val modelNow = getCachedOrPartialBytes(context, model)
                    val overallNow = computeQueueDownloadedBytes(context, queue)
                    val speed = speedTracker.currentSpeedBytesPerSec()
                    val remainingBytes = max(0L, overallTotal - overallNow)
                    val eta = if (speed > 0L) remainingBytes / speed else null

                    updateState {
                        copy(
                            activeModel = model,
                            currentFileName = artifact.relativePath,
                            currentModelIndex = modelIndex,
                            modelDownloadedBytes = modelNow,
                            modelTotalBytes = mTotalBytes,
                            overallDownloadedBytes = overallNow,
                            speedBytesPerSec = speed,
                            etaSeconds = eta,
                            statusNote = statusHint
                        )
                    }
                },
                onChunkReceived = { chunkBytes ->
                    speedTracker.recordBytes(chunkBytes)
                }
            )

            // Verify SHA-256 of completed .part file before atomic rename
            statuses[model] = ModelQueueStatus.VERIFYING
            updateState {
                copy(
                    currentFileName = "Verifying ${artifact.relativePath}...",
                    statusNote = "Verifying SHA-256 integrity...",
                    modelStatuses = statuses.toMap()
                )
            }

            if (!verifySha256(partFile, artifact.sha256)) {
                partFile.delete()
                throw IOException("SHA-256 integrity mismatch for ${artifact.relativePath}")
            }

            if (finalFile.exists()) {
                finalFile.delete()
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            statuses[model] = ModelQueueStatus.DOWNLOADING
        }

        // Write DAL1 manifest last so ModelStore.swift / isDownloaded() marks the model complete
        val metaDir = File(targetDir, ".dal-meta")
        metaDir.mkdirs()
        val manifestFile = File(metaDir, "manifest")
        val rLines = artifacts.joinToString("\n") { "R\t${it.relativePath}" }
        val fLines = artifacts.joinToString("\n") { "F\t${it.relativePath}\t${it.sizeBytes}\t${it.sha256}" }
        manifestFile.writeText("DAL1\n$rLines\n$fLines\n")

        if (!DesertAntModelStorage.isModelDownloaded(context, model)) {
            DesertAntModelStorage.downloadModel(context, model) {}
        }
        if (!DesertAntModelStorage.isModelDownloaded(context, model)) {
            throw IOException("Post-download verification failed for ${model.displayName}")
        }
    }

    private suspend fun downloadFileWithAutoResume(
        urlStr: String,
        partFile: File,
        expectedSize: Long,
        onProgress: (Long, String?) -> Unit,
        onChunkReceived: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        var stallAttempt = 0
        var lastEmitMs = 0L

        while (true) {
            coroutineContext.ensureActive()
            val existingBytes = if (partFile.exists()) partFile.length() else 0L
            if (existingBytes == expectedSize && expectedSize > 0L) {
                onProgress(expectedSize, null)
                return@withContext
            }
            if (existingBytes > expectedSize) {
                partFile.delete()
            }

            val resumeOffset = if (partFile.exists()) partFile.length() else 0L
            var connection: HttpURLConnection? = null
            var bytesReadThisConnection = 0L

            try {
                if (resumeOffset > 0L && stallAttempt == 0) {
                    onProgress(
                        resumeOffset,
                        "Resuming from ${DownloadSessionState.formatBytesMb(resumeOffset)}..."
                    )
                }

                connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                    if (resumeOffset > 0L) {
                        setRequestProperty("Range", "bytes=$resumeOffset-")
                    }
                }

                val responseCode = connection.responseCode
                val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
                val isOk = responseCode == HttpURLConnection.HTTP_OK

                if (!isPartial && !isOk) {
                    throw IOException("HTTP $responseCode from server")
                }

                val startOffset = if (isPartial) resumeOffset else 0L
                RandomAccessFile(partFile, "rw").use { raf ->
                    if (!isPartial && resumeOffset > 0L) {
                        raf.setLength(0L)
                    }
                    raf.seek(startOffset)

                    connection.inputStream.use { input ->
                        // 16 KB buffer for responsive progress updates even on very slow internet
                        val buffer = ByteArray(16 * 1024)
                        var currentSize = startOffset

                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            currentSize += read
                            bytesReadThisConnection += read
                            onChunkReceived(read)

                            // Reset stall counter whenever at least 64 KB of forward progress was made
                            if (bytesReadThisConnection >= 64 * 1024L) {
                                stallAttempt = 0
                            }

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmitMs >= 150L || currentSize >= expectedSize) {
                                lastEmitMs = now
                                onProgress(currentSize, null)
                            }
                        }
                    }
                }

                val finalLength = partFile.length()
                if (finalLength == expectedSize) {
                    onProgress(finalLength, null)
                    return@withContext
                } else {
                    throw IOException("Incomplete stream ($finalLength / $expectedSize bytes)")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                stallAttempt++
                if (stallAttempt > MAX_STALL_RETRIES) {
                    throw e
                }
                val backoffMs = min(15_000L, 1_500L * stallAttempt)
                val currentSaved = if (partFile.exists()) partFile.length() else 0L
                Log.w(
                    TAG,
                    "Slow/interrupted connection on $urlStr (saved=$currentSaved bytes, retry $stallAttempt/$MAX_STALL_RETRIES in ${backoffMs}ms): ${e.message}"
                )
                onProgress(
                    currentSaved,
                    "Slow connection — auto-resuming from ${DownloadSessionState.formatBytesMb(currentSaved)} (retry $stallAttempt/$MAX_STALL_RETRIES)..."
                )
                delay(backoffMs)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun computeQueueDownloadedBytes(context: Context, queue: List<DesertAntModel>): Long {
        return queue.sumOf { getCachedOrPartialBytes(context, it) }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        if (!file.exists()) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    digest.update(buf, 0, r)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHex, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private inline fun updateState(transform: DownloadSessionState.() -> DownloadSessionState) {
        _sessionState.value = _sessionState.value.transform()
    }

    /**
     * Sliding 4-second window speed tracker for smooth speed & ETA calculation on slow or bursty links.
     */
    private class RollingSpeedTracker {
        private val samples = ArrayDeque<Pair<Long, Long>>() // (timestampMs, cumulativeBytes)
        private var totalBytesRecorded = 0L

        @Synchronized
        fun recordBytes(count: Int) {
            val now = SystemClock.elapsedRealtime()
            totalBytesRecorded += count
            samples.addLast(now to totalBytesRecorded)
            pruneOld(now)
        }

        @Synchronized
        fun currentSpeedBytesPerSec(): Long {
            val now = SystemClock.elapsedRealtime()
            pruneOld(now)
            if (samples.size < 2) return 0L
            val oldest = samples.first()
            val newest = samples.last()
            val dtMs = newest.first - oldest.first
            val dBytes = newest.second - oldest.second
            if (dtMs <= 100L || dBytes <= 0L) return 0L
            return (dBytes * 1000L) / dtMs
        }

        private fun pruneOld(nowMs: Long) {
            while (samples.size > 2 && nowMs - samples.first().first > 4_000L) {
                samples.removeFirst()
            }
        }
    }
}

/**
 * Foreground service that keeps model downloads running when the user switches apps or locks the screen,
 * displaying real-time progress, speed, and ETA in a system notification.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "openflow_model_download_channel"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ModelDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.stopService(Intent(appContext, ModelDownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        startForegroundCompat(buildNotification(ModelDownloadManager.sessionState.value))

        serviceScope.launch {
            ModelDownloadManager.sessionState.collect { state ->
                if (!state.isRunning) {
                    stopSelf()
                } else {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(ModelDownloadManager.sessionState.value))
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(state: DownloadSessionState): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val percent = (state.overallProgress * 100f).roundToInt()
        val modelName = state.activeModel?.id?.replaceFirstChar { it.uppercase() } ?: "Models"
        val title = "Downloading $modelName ($percent%) — ${state.formattedOverallBytes}"
        val subtitle = state.statusNote ?: "${state.formattedSpeed} • ${state.formattedEta}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setProgress(1000, (state.overallProgress * 1000f).roundToInt().coerceIn(0, 1000), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Speech Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OpenFlow::ModelDownloadWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // Max 2 hours safety cap for very slow connections
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {
        }
    }
}
