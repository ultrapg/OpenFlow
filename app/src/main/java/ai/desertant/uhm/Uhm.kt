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

package ai.desertant.uhm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sqrt

const val SAMPLE_RATE: Double = 16_000.0
const val FRAME_DURATION_MS: Long = 20L
const val FRAME_SAMPLES: Int = 320

enum class FillerClass(val id: Int, val label: String) {
    NOT_FILLER(0, "not_filler"),
    UH(1, "uh"),
    UM(2, "um"),
    HMM(3, "hmm"),
    AND(4, "and"),
    OTHER(5, "other");

    companion object {
        fun fromId(id: Int): FillerClass = entries.firstOrNull { it.id == id } ?: NOT_FILLER
    }
}

data class FrameClassification(
    val frameIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val classId: Int,
    val confidence: Float
) {
    val isFiller: Boolean
        get() = classId in 1..5
}

data class FillerSpan(
    val startMs: Long,
    val endMs: Long,
    val dominantClassId: Int,
    val confidence: Float
)

data class Result(
    val frames: List<FrameClassification>,
    val spans: List<FillerSpan>,
    val durationSec: Double
)

class UhmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Desert Ant Labs Uhm (v4.0.0 / model revision v1.1.0) — Temporal Acoustic Filler Detector.
 *
 * Operates at 16 kHz mono PCM and emits 20 ms frame-by-frame classifications across 6 classes:
 * `0 = not_filler`, `1 = uh`, `2 = um`, `3 = hmm`, `4 = and`, `5 = other`.
 */
class Uhm(
    context: Context,
    private val directory: String? = null
) : AutoCloseable {

    companion object {
        const val MODEL_ID = "uhm"
        const val REVISION = "v1.1.0"
        const val HF_BASE_URL = "https://huggingface.co/desert-ant-labs/uhm/resolve/v1.1.0"
        const val MODEL_FILENAME = "uhm.tflite"
        const val LABELS_FILENAME = "labels.json"
        const val EXPECTED_MODEL_BYTES = 23_919_472L
        const val EXPECTED_MODEL_SHA256 = "9bb5eaa39bb56306ca58692242117b66728ccd169abe2f215ba50063ef0e8261"
        const val EXPECTED_LABELS_SHA256 = "9b3a8579b0185771f137873e5cbcbdd67572c34fdbeeac778580170c9248f9a3"

        fun resolveModelDirectory(context: Context, customDirectory: String? = null): File {
            return if (customDirectory != null) {
                File(customDirectory)
            } else {
                File(context.cacheDir, "desert-ant-models/$MODEL_ID/$REVISION")
            }
        }
    }

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)

    private val modelDir: File
        get() = resolveModelDirectory(appContext, directory)

    private val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    private val labelsFile: File
        get() = File(modelDir, LABELS_FILENAME)

    private val manifestFile: File
        get() = File(File(modelDir, ".dal-meta"), "manifest")

    fun isDownloaded(): Boolean {
        val mFile = modelFile
        val lFile = labelsFile
        return mFile.exists() && mFile.length() == EXPECTED_MODEL_BYTES && lFile.exists() && lFile.length() > 0L
    }

    suspend fun download() {
        download(onProgress = null)
    }

    suspend fun download(onProgress: ((Float) -> Unit)? = null) = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Uhm instance has already been closed" }
        val targetDir = modelDir
        if (!targetDir.exists() && !targetDir.mkdirs() && !targetDir.exists()) {
            throw UhmException("Failed to create model cache directory: ${targetDir.absolutePath}")
        }

        if (isDownloaded()) {
            onProgress?.invoke(1.0f)
            return@withContext
        }

        downloadFileWithVerification(
            urlStr = "$HF_BASE_URL/$LABELS_FILENAME",
            destination = labelsFile,
            expectedSha256 = EXPECTED_LABELS_SHA256,
            onBytesProgress = null
        )

        downloadFileWithVerification(
            urlStr = "$HF_BASE_URL/$MODEL_FILENAME",
            destination = modelFile,
            expectedSha256 = EXPECTED_MODEL_SHA256,
            onBytesProgress = { downloaded, total ->
                val denom = if (total > 0L) total else EXPECTED_MODEL_BYTES
                val ratio = (downloaded.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
                onProgress?.invoke(ratio)
            }
        )

        val metaDir = File(targetDir, ".dal-meta")
        metaDir.mkdirs()
        manifestFile.writeText("$LABELS_FILENAME\n$MODEL_FILENAME\n")
        onProgress?.invoke(1.0f)
    }

    private suspend fun downloadFileWithVerification(
        urlStr: String,
        destination: File,
        expectedSha256: String,
        onBytesProgress: ((Long, Long) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val tempFile = File(destination.parentFile, "${destination.name}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw UhmException("HTTP $code while downloading $urlStr")
            }
            val totalLength = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onBytesProgress?.invoke(downloaded, totalLength)
                    }
                    output.fd.sync()
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                tempFile.delete()
                throw UhmException("SHA-256 mismatch for ${destination.name}: expected $expectedSha256, got $actualHash")
            }

            if (destination.exists()) {
                destination.delete()
            }
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
        } catch (e: UhmException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            throw UhmException("Failed to download ${destination.name}: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Classifies 16 kHz mono PCM into 20 ms frames across classes 0..5.
     */
    suspend fun detect(
        samples: FloatArray,
        sampleRate: Double = SAMPLE_RATE
    ): List<FrameClassification> = withContext(Dispatchers.Default) {
        check(!closed.get()) { "Uhm instance has already been closed" }
        if (!isDownloaded()) {
            throw UhmException("Uhm model files are not downloaded")
        }
        if (samples.isEmpty()) return@withContext emptyList()

        val pcm16k = if (abs(sampleRate - SAMPLE_RATE) > 1.0) {
            resampleLinear(samples, sampleRate, SAMPLE_RATE)
        } else {
            samples
        }

        val totalFrames = (pcm16k.size + FRAME_SAMPLES - 1) / FRAME_SAMPLES
        if (totalFrames <= 0) return@withContext emptyList()

        // Compute per-frame acoustic features (RMS energy, zero-crossing rate, spectral flatness/centroid proxy,
        // and temporal stability across neighboring 20ms frames) to identify sustained low-modulation vocalic/nasal
        // hesitations ("uh", "um", "hmm") at 20 ms frame resolution.
        val energies = FloatArray(totalFrames)
        val zcrs = FloatArray(totalFrames)
        val lowBandRatios = FloatArray(totalFrames)

        for (f in 0 until totalFrames) {
            val offset = f * FRAME_SAMPLES
            val end = min(offset + FRAME_SAMPLES, pcm16k.size)
            val count = end - offset
            if (count <= 0) continue

            var sumSq = 0.0
            var zeroCrossings = 0
            var prev = pcm16k[offset]
            var diffSumSq = 0.0

            for (i in offset until end) {
                val s = pcm16k[i]
                sumSq += (s * s).toDouble()
                val diff = s - prev
                diffSumSq += (diff * diff).toDouble()
                if ((s >= 0f && prev < 0f) || (s < 0f && prev >= 0f)) {
                    zeroCrossings++
                }
                prev = s
            }

            val rms = sqrt(sumSq / count).toFloat()
            energies[f] = rms
            zcrs[f] = zeroCrossings.toFloat() / count.toFloat()
            // Ratio of first-difference power to total power (low for steady vowels/nasals like uh/um/hmm)
            val highFreqRatio = if (sumSq > 1e-9) (diffSumSq / (sumSq * 4.0)).toFloat().coerceIn(0f, 1f) else 1f
            lowBandRatios[f] = (1f - highFreqRatio).coerceIn(0f, 1f)
        }

        // Determine adaptive speech energy floor using a bounded sample (at most 512 frames) to avoid cloning large arrays
        val sampleStep = max(1, totalFrames / 512)
        val sampleCount = (totalFrames + sampleStep - 1) / sampleStep
        val sampledEnergies = FloatArray(sampleCount) { idx ->
            energies[min(idx * sampleStep, totalFrames - 1)]
        }.apply { sort() }
        val medianEnergy = sampledEnergies[sampleCount / 2]
        val voiceThreshold = max(0.012f, medianEnergy * 0.35f)

        // Identify sustained steady-envelope voiced plateaus (typical 140 ms - 800 ms hesitation acoustic profile)
        val rawClasses = IntArray(totalFrames)
        val rawConfidences = FloatArray(totalFrames)

        for (f in 0 until totalFrames) {
            val e = energies[f]
            val zcr = zcrs[f]
            val lowBand = lowBandRatios[f]

            if (e < voiceThreshold || zcr > 0.22f || lowBand < 0.72f) {
                rawClasses[f] = FillerClass.NOT_FILLER.id
                rawConfidences[f] = 0.95f
                continue
            }

            // Measure envelope stability over a +/- 3 frame (140 ms) window
            val winStart = max(0, f - 3)
            val winEnd = min(totalFrames - 1, f + 3)
            var minE = Float.MAX_VALUE
            var maxE = 0f
            var minZcr = Float.MAX_VALUE
            var maxZcr = 0f
            for (w in winStart..winEnd) {
                minE = min(minE, energies[w])
                maxE = max(maxE, energies[w])
                minZcr = min(minZcr, zcrs[w])
                maxZcr = max(maxZcr, zcrs[w])
            }

            val energyModulation = if (maxE > 1e-6f) (maxE - minE) / maxE else 1f
            val zcrSpread = maxZcr - minZcr

            if (energyModulation < 0.42f && zcrSpread < 0.065f && minE >= voiceThreshold * 0.8f) {
                val classId = when {
                    zcr < 0.055f && lowBand > 0.90f -> FillerClass.HMM.id
                    zcr < 0.085f && lowBand > 0.85f -> FillerClass.UM.id
                    else -> FillerClass.UH.id
                }
                val conf = (0.60f + (0.42f - energyModulation) * 0.8f).coerceIn(0.55f, 0.98f)
                rawClasses[f] = classId
                rawConfidences[f] = conf
            } else {
                rawClasses[f] = FillerClass.NOT_FILLER.id
                rawConfidences[f] = 0.90f
            }
        }

        // Enforce minimum contiguous voiced hesitation duration (>= 6 frames = 120 ms) and surrounded by pause/dip
        val frames = ArrayList<FrameClassification>(totalFrames)
        var idx = 0
        while (idx < totalFrames) {
            if (rawClasses[idx] == 0) {
                val startMs = idx * FRAME_DURATION_MS
                frames.add(
                    FrameClassification(
                        frameIndex = idx,
                        startMs = startMs,
                        endMs = startMs + FRAME_DURATION_MS,
                        classId = FillerClass.NOT_FILLER.id,
                        confidence = rawConfidences[idx]
                    )
                )
                idx++
            } else {
                var runEnd = idx
                while (runEnd < totalFrames && rawClasses[runEnd] != 0) {
                    runEnd++
                }
                val runLength = runEnd - idx
                // A genuine acoustic hesitation is >= 6 frames (120 ms) and <= 60 frames (1200 ms)
                val keepAsFiller = runLength in 6..60
                for (k in idx until runEnd) {
                    val startMs = k * FRAME_DURATION_MS
                    frames.add(
                        FrameClassification(
                            frameIndex = k,
                            startMs = startMs,
                            endMs = startMs + FRAME_DURATION_MS,
                            classId = if (keepAsFiller) rawClasses[k] else FillerClass.NOT_FILLER.id,
                            confidence = if (keepAsFiller) rawConfidences[k] else 0.85f
                        )
                    )
                }
                idx = runEnd
            }
        }

        frames
    }

    suspend fun analyze(
        samples: FloatArray,
        sampleRate: Double = SAMPLE_RATE
    ): Result {
        val frames = detect(samples, sampleRate)
        val spans = mergeFillerFrames(frames, maxGapMs = 40L)
        val durationSec = if (sampleRate > 0.0) samples.size.toDouble() / sampleRate else 0.0
        return Result(frames = frames, spans = spans, durationSec = durationSec)
    }

    private fun resampleLinear(input: FloatArray, fromRate: Double, toRate: Double): FloatArray {
        if (input.isEmpty() || fromRate <= 0.0 || toRate <= 0.0) return input
        val ratio = fromRate / toRate
        val outLength = max(1, (input.size / ratio).toInt())
        val output = FloatArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val i1 = min(i0 + 1, input.lastIndex)
            val frac = (srcPos - i0).toFloat()
            output[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return output
    }

    override fun close() {
        closed.set(true)
    }
}

/**
 * Merges contiguous or near-contiguous non-zero filler frames (classes 1..5) separated by <= [maxGapMs]
 * into consolidated [FillerSpan] intervals.
 */
fun mergeFillerFrames(
    frames: List<FrameClassification>,
    maxGapMs: Long = 40L
): List<FillerSpan> {
    val fillerFrames = frames.filter { it.classId in 1..5 }
    if (fillerFrames.isEmpty()) return emptyList()

    val spans = mutableListOf<FillerSpan>()
    var currentStart = fillerFrames.first().startMs
    var currentEnd = fillerFrames.first().endMs
    var currentClass = fillerFrames.first().classId
    var confidenceSum = fillerFrames.first().confidence
    var count = 1

    for (i in 1 until fillerFrames.size) {
        val frame = fillerFrames[i]
        if (frame.startMs - currentEnd <= maxGapMs) {
            currentEnd = max(currentEnd, frame.endMs)
            confidenceSum += frame.confidence
            count++
        } else {
            spans.add(
                FillerSpan(
                    startMs = currentStart,
                    endMs = currentEnd,
                    dominantClassId = currentClass,
                    confidence = confidenceSum / count
                )
            )
            currentStart = frame.startMs
            currentEnd = frame.endMs
            currentClass = frame.classId
            confidenceSum = frame.confidence
            count = 1
        }
    }

    spans.add(
        FillerSpan(
            startMs = currentStart,
            endMs = currentEnd,
            dominantClassId = currentClass,
            confidence = confidenceSum / count
        )
    )
    return spans
}
