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

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * User-selectable weight quantization mode for the `Voz` (Parakeet TDT 0.6B v3) speech recognition model.
 *
 * In `Voz` (`encoder.tflite` + `decoder.tflite`), all weight matrices are stored in external TFLite
 * buffers of type `FLOAT16` (`1`) fed into `BuiltinOperator_DEQUANTIZE` (`6`) operators.
 * LiteRT (`libLiteRt.so` XNNPACK & GPU/NPU delegates) natively supports `TensorType.INT8` (`9`) inputs
 * on `DEQUANTIZE` with per-tensor symmetric `QuantizationParameters(scale = [max_abs / 127.0f], zero_point = [0])`,
 * shrinking weight storage and runtime memory by ~50% while preserving full graph compatibility.
 */
enum class VozQuantization(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val description: String,
    val encoderSizeBytes: Long,
    val decoderSizeBytes: Long,
    val diskSizeMb: Int,
    val estimatedVozRamMb: Int
) {
    FP16(
        id = "fp16",
        displayName = "FP16 — Original 16-bit Float (1,187 MB)",
        shortLabel = "FP16",
        description = "Original 16-bit float weights for both encoder and decoder. Maximum precision on GPU/NPU; highest RAM & disk usage.",
        encoderSizeBytes = 1_207_525_456L,
        decoderSizeBytes = 24_503_072L,
        diskSizeMb = 1187,
        estimatedVozRamMb = 1400
    ),
    INT8_HYBRID(
        id = "int8_hybrid",
        displayName = "INT8 Hybrid — 8-bit Encoder + FP16 Decoder (630 MB)",
        shortLabel = "INT8 Hybrid",
        description = "Quantizes the 1.15 GB Conformer encoder to 8-bit integer (595 MB) while keeping the 23 MB decoder in FP16. Cuts RAM by ~40% with near-FP16 accuracy.",
        encoderSizeBytes = 623_511_360L,
        decoderSizeBytes = 24_503_072L,
        diskSizeMb = 630,
        estimatedVozRamMb = 860
    ),
    INT8(
        id = "int8",
        displayName = "INT8 Dynamic — Full 8-bit Quantization (618 MB)",
        shortLabel = "INT8",
        description = "Quantizes both encoder and decoder weights to 8-bit integer. Lowest disk & RAM footprint (~820 MB) and fastest CPU/XNNPACK execution.",
        encoderSizeBytes = 623_511_360L,
        decoderSizeBytes = 12_293_360L,
        diskSizeMb = 618,
        estimatedVozRamMb = 820
    );

    val quantizeEncoder: Boolean
        get() = this == INT8_HYBRID || this == INT8

    val quantizeDecoder: Boolean
        get() = this == INT8

    val estimatedDiskMb: Int
        get() = diskSizeMb

    val estimatedRamMb: Int
        get() = estimatedVozRamMb

    fun expectedArtifactSize(relativePath: String, defaultSize: Long): Long {
        return when (relativePath) {
            "encoder.tflite" -> encoderSizeBytes
            "decoder.tflite" -> decoderSizeBytes
            else -> defaultSize
        }
    }

    companion object {
        fun fromId(id: String?): VozQuantization {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FP16
        }
    }
}

/**
 * Streaming, bounded-memory (< 2 MB heap) TFLite FlatBuffer weight quantizer/dequantizer for `Voz`.
 *
 * Converts `Voz`'s external `FLOAT16` weight tensors in `encoder.tflite` (271 tensors, 1.15 GB -> 595 MB)
 * and `decoder.tflite` (6 tensors, 23.4 MB -> 11.7 MB) to symmetric per-tensor `INT8` (`TensorType = 9`,
 * `scale = max_abs / 127.0f`, `zero_point = 0`) and back to `FP16` on-device without re-downloading.
 */
object VozTfliteQuantizer {

    private const val TENSOR_TYPE_FLOAT16: Byte = 1
    private const val TENSOR_TYPE_INT8: Byte = 9

    private data class TensorPatchInfo(
        val bufferIndex: Int,
        val typeFieldPos: Int,
        val quantFieldPos: Int
    )

    private data class ExternalBufferInfo(
        val bufferIndex: Int,
        val offsetFieldPos: Int,
        val sizeFieldPos: Int,
        val origOffset: Long,
        val origSize: Long
    )

    /**
     * Inspects the FlatBuffer header of [tfliteFile] and returns `true` if any tensor in subgraph 0
     * currently has `TensorType.INT8` (`9`) or if its length matches the quantized size.
     */
    fun isTfliteFileQuantizedInt8(tfliteFile: File): Boolean {
        if (!tfliteFile.exists() || !tfliteFile.isFile || tfliteFile.length() < 64L) return false
        return try {
            RandomAccessFile(tfliteFile, "r").use { raf ->
                val prefixSize = min(tfliteFile.length(), 2 * 1024 * 1024L).toInt()
                val bytes = ByteArray(prefixSize)
                raf.readFully(bytes)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val parsed = parseTfliteHeader(buf, prefixSize)
                parsed.tensorPatches.isEmpty() && parsed.int8TensorCount > 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Ensures `encoder.tflite` and `decoder.tflite` inside [vozDir] match [targetQuantization],
     * converting `FP16 <-> INT8` in-place via atomic `.quant.part` streaming files.
     */
    fun ensureVozDirectoryQuantization(
        vozDir: File,
        targetQuantization: VozQuantization,
        onStatus: ((String) -> Unit)? = null
    ) {
        val encoderFile = File(vozDir, "encoder.tflite")
        val decoderFile = File(vozDir, "decoder.tflite")
        if (!encoderFile.exists() || !decoderFile.exists()) {
            throw IOException("Voz model files missing in ${vozDir.absolutePath}")
        }

        // Remove any legacy .dal-meta folder so native libVozAndroid.so uses ModelDistribution.userPlacedFiles
        // (0ms startup, no 1.2GB Swift SoftwareSHA256 allocation, and full compatibility with INT8 weights).
        val legacyDalMeta = File(vozDir, ".dal-meta")
        if (legacyDalMeta.exists()) {
            legacyDalMeta.deleteRecursively()
        }

        // 1. Reconcile encoder.tflite
        val encoderIsInt8 = isTfliteFileQuantizedInt8(encoderFile)
        if (targetQuantization.quantizeEncoder && !encoderIsInt8) {
            onStatus?.invoke("Quantizing Voz encoder to INT8 (0%)...")
            quantizeFp16TfliteFileToInt8(encoderFile) { pct ->
                onStatus?.invoke("Quantizing Voz encoder to INT8 ($pct%)...")
            }
        } else if (!targetQuantization.quantizeEncoder && encoderIsInt8) {
            onStatus?.invoke("Restoring Voz encoder to FP16 (0%)...")
            dequantizeInt8TfliteFileToFp16(encoderFile) { pct ->
                onStatus?.invoke("Restoring Voz encoder to FP16 ($pct%)...")
            }
        }

        // 2. Reconcile decoder.tflite
        val decoderIsInt8 = isTfliteFileQuantizedInt8(decoderFile)
        if (targetQuantization.quantizeDecoder && !decoderIsInt8) {
            onStatus?.invoke("Quantizing Voz decoder to INT8...")
            quantizeFp16TfliteFileToInt8(decoderFile)
        } else if (!targetQuantization.quantizeDecoder && decoderIsInt8) {
            onStatus?.invoke("Restoring Voz decoder to FP16...")
            dequantizeInt8TfliteFileToFp16(decoderFile)
        }
    }

    /**
     * Converts a TFLite v3 model file with external `FLOAT16` weight buffers into a symmetric per-tensor
     * `INT8` TFLite v3 model file while saving `${tfliteFile.name}.fp16.hdr` and `${tfliteFile.name}.fp16.scales`
     * so it can be restored back to `FP16` offline without re-downloading.
     */
    fun quantizeFp16TfliteFileToInt8(
        tfliteFile: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        if (!tfliteFile.exists() || !tfliteFile.isFile) {
            throw IOException("TFLite file does not exist: ${tfliteFile.absolutePath}")
        }
        val parentDir = tfliteFile.parentFile ?: throw IOException("Missing parent directory for ${tfliteFile.name}")
        val hdrBackupFile = File(parentDir, "${tfliteFile.name}.fp16.hdr")
        val scalesBackupFile = File(parentDir, "${tfliteFile.name}.fp16.scales")
        val tempOutFile = File(parentDir, "${tfliteFile.name}.quant.part")

        RandomAccessFile(tfliteFile, "r").use { srcRaf ->
            // Read initial prefix to discover minExtOff
            val initialPrefixLen = min(srcRaf.length(), 4 * 1024 * 1024L).toInt()
            val prefixBytes = ByteArray(initialPrefixLen)
            srcRaf.seek(0L)
            srcRaf.readFully(prefixBytes)
            val prefixBuf = ByteBuffer.wrap(prefixBytes).order(ByteOrder.LITTLE_ENDIAN)
            val headerInfo = parseTfliteHeader(prefixBuf, initialPrefixLen)

            if (headerInfo.tensorPatches.isEmpty()) {
                // Already quantized
                onProgress?.invoke(100)
                return
            }

            val minExtOff = headerInfo.minExtOff.toInt()
            if (minExtOff <= 0 || minExtOff > initialPrefixLen) {
                throw IOException("Unexpected FlatBuffer header boundary ($minExtOff) in ${tfliteFile.name}")
            }

            // Save a clean copy of the original FP16 FlatBuffer header (0 until minExtOff) for instant offline restore
            val origHeaderBytes = prefixBytes.copyOfRange(0, minExtOff)
            val hdrPart = File(parentDir, "${hdrBackupFile.name}.part")
            hdrPart.writeBytes(origHeaderBytes)
            if (hdrBackupFile.exists()) hdrBackupFile.delete()
            if (!hdrPart.renameTo(hdrBackupFile)) {
                hdrPart.copyTo(hdrBackupFile, overwrite = true)
                hdrPart.delete()
            }

            val numF16 = headerInfo.tensorPatches.size
            val vtablePos = (minExtOff + 7) and -8
            val firstRecPos = vtablePos + 16 // 12-byte vtable + 4-byte alignment pad -> 8-byte aligned records
            val newHeaderEnd = firstRecPos + numF16 * 32
            val newDataStart = (newHeaderEnd + 15) and -16

            val newHeaderBytes = ByteArray(newDataStart)
            System.arraycopy(origHeaderBytes, 0, newHeaderBytes, 0, minExtOff)
            val headerBuf = ByteBuffer.wrap(newHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Write shared QuantizationParameters vtable at vtablePos:
            // vtable_size = 12, table_size = 12, min = 0, max = 0, scale = 4, zero_point = 8
            headerBuf.putShort(vtablePos + 0, 12.toShort())
            headerBuf.putShort(vtablePos + 2, 12.toShort())
            headerBuf.putShort(vtablePos + 4, 0.toShort())
            headerBuf.putShort(vtablePos + 6, 0.toShort())
            headerBuf.putShort(vtablePos + 8, 4.toShort())
            headerBuf.putShort(vtablePos + 10, 8.toShort())

            val f16BufToTensorIndex = HashMap<Int, Int>(numF16 * 2)
            for ((idx, patch) in headerInfo.tensorPatches.withIndex()) {
                f16BufToTensorIndex[patch.bufferIndex] = idx
            }

            // Compute new 16-byte-aligned external buffer offsets and patch Buffer table entries in newHeaderBytes
            val sortedExtBuffers = headerInfo.externalBuffers.sortedBy { it.origOffset }
            val newOffsets = LongArray(sortedExtBuffers.size)
            val newSizes = LongArray(sortedExtBuffers.size)
            var curDataOff = newDataStart.toLong()
            val totalOrigBytes = sortedExtBuffers.sumOf { it.origSize }.coerceAtLeast(1L)

            for ((i, extBuf) in sortedExtBuffers.withIndex()) {
                val alignedOff = (curDataOff + 15L) and -16L
                val isF16 = f16BufToTensorIndex.containsKey(extBuf.bufferIndex)
                val newSize = if (isF16) extBuf.origSize / 2L else extBuf.origSize
                newOffsets[i] = alignedOff
                newSizes[i] = newSize
                headerBuf.putLong(extBuf.offsetFieldPos, alignedOff)
                headerBuf.putLong(extBuf.sizeFieldPos, newSize)
                curDataOff = (alignedOff + newSize + 15L) and -16L
            }
            val totalNewFileSize = curDataOff

            val scalesByTensorIdx = FloatArray(numF16)
            if (tempOutFile.exists()) tempOutFile.delete()

            RandomAccessFile(tempOutFile, "rw").use { dstRaf ->
                dstRaf.setLength(totalNewFileSize)
                // Stream and quantize each external buffer sequentially
                val ioBuf = ByteArray(256 * 1024)
                val outBuf = ByteArray(128 * 1024)
                var processedOrigBytes = 0L
                var lastProgressPct = -1

                for ((i, extBuf) in sortedExtBuffers.withIndex()) {
                    val tensorIdx = f16BufToTensorIndex[extBuf.bufferIndex]
                    val dstOffset = newOffsets[i]
                    if (tensorIdx != null) {
                        val scale = quantizeSingleFp16Buffer(
                            srcRaf = srcRaf,
                            srcOffset = extBuf.origOffset,
                            srcByteLength = extBuf.origSize,
                            dstRaf = dstRaf,
                            dstOffset = dstOffset,
                            ioBuf = ioBuf,
                            outBuf = outBuf
                        )
                        scalesByTensorIdx[tensorIdx] = scale
                    } else {
                        copyRawBuffer(
                            srcRaf = srcRaf,
                            srcOffset = extBuf.origOffset,
                            byteLength = extBuf.origSize,
                            dstRaf = dstRaf,
                            dstOffset = dstOffset,
                            ioBuf = ioBuf
                        )
                    }

                    processedOrigBytes += extBuf.origSize
                    if (onProgress != null) {
                        val pct = ((processedOrigBytes * 100L) / totalOrigBytes).toInt().coerceIn(0, 99)
                        if (pct != lastProgressPct) {
                            lastProgressPct = pct
                            onProgress(pct)
                        }
                    }
                }

                // Populate QuantizationParameters records and patch Tensor types & quantization uoffsets in headerBuf
                for ((idx, patch) in headerInfo.tensorPatches.withIndex()) {
                    val scale = scalesByTensorIdx[idx]
                    val recPos = firstRecPos + idx * 32
                    headerBuf.putInt(recPos + 0, recPos - vtablePos) // soffset to shared vtable
                    headerBuf.putInt(recPos + 4, 8)                  // uoffset from (recPos + 4) to scale_vec at (recPos + 12)
                    headerBuf.putInt(recPos + 8, 12)                 // uoffset from (recPos + 8) to zp_vec at (recPos + 20)
                    headerBuf.putInt(recPos + 12, 1)                 // scale_vec.length = 1
                    headerBuf.putFloat(recPos + 16, scale)           // scale[0]
                    headerBuf.putInt(recPos + 20, 1)                 // zp_vec.length = 1
                    headerBuf.putLong(recPos + 24, 0L)               // zero_point[0] = 0L

                    headerBuf.put(patch.typeFieldPos, TENSOR_TYPE_INT8)
                    headerBuf.putInt(patch.quantFieldPos, recPos - patch.quantFieldPos)
                }

                // Write completed extended FlatBuffer header at offset 0
                dstRaf.seek(0L)
                dstRaf.write(newHeaderBytes)
            }

            // Persist scales backup (bufferIndex: Int32, scale: Float32) for offline FP16 restoration
            val scalesBytes = ByteBuffer.allocate(4 + numF16 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(numF16)
                for ((idx, patch) in headerInfo.tensorPatches.withIndex()) {
                    putInt(patch.bufferIndex)
                    putFloat(scalesByTensorIdx[idx])
                }
            }.array()
            val scalesPart = File(parentDir, "${scalesBackupFile.name}.part")
            scalesPart.writeBytes(scalesBytes)
            if (scalesBackupFile.exists()) scalesBackupFile.delete()
            if (!scalesPart.renameTo(scalesBackupFile)) {
                scalesPart.copyTo(scalesBackupFile, overwrite = true)
                scalesPart.delete()
            }
        }

        // Atomically replace tfliteFile with tempOutFile
        if (tfliteFile.exists()) tfliteFile.delete()
        if (!tempOutFile.renameTo(tfliteFile)) {
            tempOutFile.copyTo(tfliteFile, overwrite = true)
            tempOutFile.delete()
        }
        onProgress?.invoke(100)
    }

    /**
     * Restores an `INT8`-quantized TFLite file back to its original `FP16` FlatBuffer layout using the
     * saved `${tfliteFile.name}.fp16.hdr` and `${tfliteFile.name}.fp16.scales` metadata without network I/O.
     */
    fun dequantizeInt8TfliteFileToFp16(
        tfliteFile: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        if (!tfliteFile.exists() || !tfliteFile.isFile) {
            throw IOException("TFLite file does not exist: ${tfliteFile.absolutePath}")
        }
        val parentDir = tfliteFile.parentFile ?: throw IOException("Missing parent directory for ${tfliteFile.name}")
        val hdrBackupFile = File(parentDir, "${tfliteFile.name}.fp16.hdr")
        val scalesBackupFile = File(parentDir, "${tfliteFile.name}.fp16.scales")
        if (!hdrBackupFile.exists() || !scalesBackupFile.exists()) {
            throw IOException("Missing FP16 header metadata for offline restoration of ${tfliteFile.name}")
        }

        val origHeaderBytes = hdrBackupFile.readBytes()
        val origHeaderBuf = ByteBuffer.wrap(origHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
        val origParsed = parseTfliteHeader(origHeaderBuf, origHeaderBytes.size)

        val scalesBytes = scalesBackupFile.readBytes()
        val scalesBuf = ByteBuffer.wrap(scalesBytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = scalesBuf.int
        val scaleByBufferIdx = HashMap<Int, Float>(count * 2)
        for (i in 0 until count) {
            val bufIdx = scalesBuf.int
            val scale = scalesBuf.float
            scaleByBufferIdx[bufIdx] = scale
        }

        val tempOutFile = File(parentDir, "${tfliteFile.name}.dequant.part")
        if (tempOutFile.exists()) tempOutFile.delete()

        RandomAccessFile(tfliteFile, "r").use { int8Raf ->
            val int8PrefixLen = min(int8Raf.length(), 4 * 1024 * 1024L).toInt()
            val int8PrefixBytes = ByteArray(int8PrefixLen)
            int8Raf.seek(0L)
            int8Raf.readFully(int8PrefixBytes)
            val int8Parsed = parseTfliteHeader(
                ByteBuffer.wrap(int8PrefixBytes).order(ByteOrder.LITTLE_ENDIAN),
                int8PrefixLen
            )
            val int8BufMap = int8Parsed.externalBuffers.associateBy { it.bufferIndex }

            val sortedOrigBuffers = origParsed.externalBuffers.sortedBy { it.origOffset }
            val lastOrigBuf = sortedOrigBuffers.lastOrNull()
                ?: throw IOException("No external buffers found in ${hdrBackupFile.name}")
            val totalFp16FileSize = (lastOrigBuf.origOffset + lastOrigBuf.origSize + 15L) and -16L
            val totalBytesWork = sortedOrigBuffers.sumOf { it.origSize }.coerceAtLeast(1L)

            RandomAccessFile(tempOutFile, "rw").use { dstRaf ->
                dstRaf.setLength(totalFp16FileSize)
                dstRaf.seek(0L)
                dstRaf.write(origHeaderBytes)

                val inBuf = ByteArray(128 * 1024)
                val outBuf = ByteArray(256 * 1024)
                var completedBytes = 0L
                var lastPct = -1

                for (origExt in sortedOrigBuffers) {
                    val int8Ext = int8BufMap[origExt.bufferIndex]
                        ?: throw IOException("Missing buffer ${origExt.bufferIndex} in quantized ${tfliteFile.name}")
                    val scale = scaleByBufferIdx[origExt.bufferIndex]
                    if (scale != null) {
                        dequantizeSingleInt8Buffer(
                            srcRaf = int8Raf,
                            srcOffset = int8Ext.origOffset,
                            srcByteLength = int8Ext.origSize,
                            dstRaf = dstRaf,
                            dstOffset = origExt.origOffset,
                            scale = scale,
                            inBuf = inBuf,
                            outBuf = outBuf
                        )
                    } else {
                        copyRawBuffer(
                            srcRaf = int8Raf,
                            srcOffset = int8Ext.origOffset,
                            byteLength = origExt.origSize,
                            dstRaf = dstRaf,
                            dstOffset = origExt.origOffset,
                            ioBuf = outBuf
                        )
                    }
                    completedBytes += origExt.origSize
                    if (onProgress != null) {
                        val pct = ((completedBytes * 100L) / totalBytesWork).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }

        if (tfliteFile.exists()) tfliteFile.delete()
        if (!tempOutFile.renameTo(tfliteFile)) {
            tempOutFile.copyTo(tfliteFile, overwrite = true)
            tempOutFile.delete()
        }
        onProgress?.invoke(100)
    }

    private fun quantizeSingleFp16Buffer(
        srcRaf: RandomAccessFile,
        srcOffset: Long,
        srcByteLength: Long,
        dstRaf: RandomAccessFile,
        dstOffset: Long,
        ioBuf: ByteArray,
        outBuf: ByteArray
    ): Float {
        // Pass 1: find maximum absolute value across all FP16 elements in this tensor
        var maxAbs = 0.0f
        var remaining = srcByteLength
        srcRaf.seek(srcOffset)
        while (remaining > 0L) {
            val toRead = min(ioBuf.size.toLong(), remaining).toInt() and -2
            if (toRead <= 0) break
            val read = srcRaf.read(ioBuf, 0, toRead)
            if (read <= 0) break
            var i = 0
            while (i + 1 < read) {
                val u16 = (ioBuf[i].toInt() and 0xFF) or ((ioBuf[i + 1].toInt() and 0xFF) shl 8)
                val v = halfToFloat(u16)
                if (v.isFinite()) {
                    val av = abs(v)
                    if (av > maxAbs) maxAbs = av
                }
                i += 2
            }
            remaining -= read
        }

        val scale = if (maxAbs > 0.0f) maxAbs / 127.0f else (1.0f / 127.0f)
        val invScale = 1.0f / scale

        // Pass 2: quantize each FP16 element to symmetric INT8 [-127, 127]
        remaining = srcByteLength
        srcRaf.seek(srcOffset)
        dstRaf.seek(dstOffset)
        while (remaining > 0L) {
            val maxInChunk = min(ioBuf.size, outBuf.size * 2)
            val toRead = min(maxInChunk.toLong(), remaining).toInt() and -2
            if (toRead <= 0) break
            val read = srcRaf.read(ioBuf, 0, toRead)
            if (read <= 0) break
            val elemCount = read / 2
            for (e in 0 until elemCount) {
                val u16 = (ioBuf[e * 2].toInt() and 0xFF) or ((ioBuf[e * 2 + 1].toInt() and 0xFF) shl 8)
                val v = halfToFloat(u16)
                val q = if (v.isFinite()) {
                    (v * invScale).roundToInt().coerceIn(-127, 127).toByte()
                } else {
                    0.toByte()
                }
                outBuf[e] = q
            }
            dstRaf.write(outBuf, 0, elemCount)
            remaining -= read
        }
        return scale
    }

    private fun dequantizeSingleInt8Buffer(
        srcRaf: RandomAccessFile,
        srcOffset: Long,
        srcByteLength: Long,
        dstRaf: RandomAccessFile,
        dstOffset: Long,
        scale: Float,
        inBuf: ByteArray,
        outBuf: ByteArray
    ) {
        var remaining = srcByteLength
        srcRaf.seek(srcOffset)
        dstRaf.seek(dstOffset)
        while (remaining > 0L) {
            val maxIn = min(inBuf.size, outBuf.size / 2)
            val toRead = min(maxIn.toLong(), remaining).toInt()
            if (toRead <= 0) break
            val read = srcRaf.read(inBuf, 0, toRead)
            if (read <= 0) break
            for (i in 0 until read) {
                val f = inBuf[i].toInt() * scale
                val h = floatToHalf(f)
                outBuf[i * 2] = (h and 0xFF).toByte()
                outBuf[i * 2 + 1] = ((h ushr 8) and 0xFF).toByte()
            }
            dstRaf.write(outBuf, 0, read * 2)
            remaining -= read
        }
    }

    private fun copyRawBuffer(
        srcRaf: RandomAccessFile,
        srcOffset: Long,
        byteLength: Long,
        dstRaf: RandomAccessFile,
        dstOffset: Long,
        ioBuf: ByteArray
    ) {
        var remaining = byteLength
        srcRaf.seek(srcOffset)
        dstRaf.seek(dstOffset)
        while (remaining > 0L) {
            val toRead = min(ioBuf.size.toLong(), remaining).toInt()
            val read = srcRaf.read(ioBuf, 0, toRead)
            if (read <= 0) break
            dstRaf.write(ioBuf, 0, read)
            remaining -= read
        }
    }

    private data class ParsedTfliteHeader(
        val minExtOff: Long,
        val tensorPatches: List<TensorPatchInfo>,
        val externalBuffers: List<ExternalBufferInfo>,
        val int8TensorCount: Int
    )

    private fun vtableFieldOffset(buf: ByteBuffer, tablePos: Int, slot: Int): Int {
        val vtablePos = tablePos - buf.getInt(tablePos)
        val vtableSize = buf.getShort(vtablePos).toInt() and 0xFFFF
        val fieldOff = 4 + slot * 2
        if (fieldOff >= vtableSize) return 0
        val rel = buf.getShort(vtablePos + fieldOff).toInt() and 0xFFFF
        return if (rel != 0) tablePos + rel else 0
    }

    private fun derefUOffset(buf: ByteBuffer, pos: Int): Int {
        return pos + buf.getInt(pos)
    }

    private fun parseTfliteHeader(buf: ByteBuffer, limitBytes: Int): ParsedTfliteHeader {
        val rootPos = buf.getInt(0)
        val subgraphsField = vtableFieldOffset(buf, rootPos, 2)
        val buffersField = vtableFieldOffset(buf, rootPos, 4)
        if (subgraphsField == 0 || buffersField == 0) {
            throw IOException("Invalid TFLite v3 Model root table")
        }

        val subgraphsVec = derefUOffset(buf, subgraphsField)
        val sg0Pos = derefUOffset(buf, subgraphsVec + 4)
        val tensorsField = vtableFieldOffset(buf, sg0Pos, 0)
        val tensorsVec = derefUOffset(buf, tensorsField)
        val numTensors = buf.getInt(tensorsVec)

        val buffersVec = derefUOffset(buf, buffersField)
        val numBuffers = buf.getInt(buffersVec)

        val externalBuffers = ArrayList<ExternalBufferInfo>()
        var minExtOff = Long.MAX_VALUE

        for (i in 0 until numBuffers) {
            val bPos = derefUOffset(buf, buffersVec + 4 + i * 4)
            val offField = vtableFieldOffset(buf, bPos, 1)
            val sizeField = vtableFieldOffset(buf, bPos, 2)
            val extOff = if (offField != 0) buf.getLong(offField) else 0L
            val extSize = if (sizeField != 0) buf.getLong(sizeField) else 0L
            if (extOff > 1L && extSize > 0L) {
                if (extOff < minExtOff) minExtOff = extOff
                externalBuffers.add(
                    ExternalBufferInfo(
                        bufferIndex = i,
                        offsetFieldPos = offField,
                        sizeFieldPos = sizeField,
                        origOffset = extOff,
                        origSize = extSize
                    )
                )
            }
        }

        val tensorPatches = ArrayList<TensorPatchInfo>()
        var int8Count = 0
        for (i in 0 until numTensors) {
            val tPos = derefUOffset(buf, tensorsVec + 4 + i * 4)
            val typeField = vtableFieldOffset(buf, tPos, 1)
            val bufField = vtableFieldOffset(buf, tPos, 2)
            val quantField = vtableFieldOffset(buf, tPos, 4)
            val tType = if (typeField != 0) buf.get(typeField) else 0.toByte()
            val bufIdx = if (bufField != 0) buf.getInt(bufField) else 0
            if (tType == TENSOR_TYPE_FLOAT16 && bufIdx > 0) {
                if (typeField == 0 || quantField == 0 || quantField >= limitBytes) {
                    throw IOException("FLOAT16 tensor $i missing type/quantization field in FlatBuffer")
                }
                tensorPatches.add(
                    TensorPatchInfo(
                        bufferIndex = bufIdx,
                        typeFieldPos = typeField,
                        quantFieldPos = quantField
                    )
                )
            } else if (tType == TENSOR_TYPE_INT8 && bufIdx > 0) {
                int8Count++
            }
        }

        return ParsedTfliteHeader(
            minExtOff = if (minExtOff == Long.MAX_VALUE) 0L else minExtOff,
            tensorPatches = tensorPatches,
            externalBuffers = externalBuffers,
            int8TensorCount = int8Count
        )
    }

    /**
     * Pure IEEE-754 binary16 -> binary32 conversion (safe in both JVM unit tests and Android runtime).
     */
    internal fun halfToFloat(bits: Int): Float {
        val s = (bits and 0x8000) shl 16
        val e = (bits ushr 10) and 0x1F
        val m = bits and 0x03FF
        return when (e) {
            0 -> {
                if (m == 0) {
                    Float.fromBits(s)
                } else {
                    val v = m.toFloat() * 5.9604645e-8f // 2^-24
                    if (s != 0) -v else v
                }
            }
            31 -> Float.fromBits(s or 0x7F800000 or (m shl 13))
            else -> Float.fromBits(s or ((e + 112) shl 23) or (m shl 13))
        }
    }

    /**
     * Pure IEEE-754 binary32 -> binary16 round-to-nearest-even conversion.
     */
    internal fun floatToHalf(value: Float): Int {
        val fbits = value.toRawBits()
        val sign = (fbits ushr 16) and 0x8000
        val absBits = fbits and 0x7FFFFFFF
        if (absBits >= 0x47800000) {
            return if (absBits > 0x7F800000) {
                sign or 0x7C00 or max(1, (absBits and 0x007FFFFF) ushr 13)
            } else {
                sign or 0x7C00
            }
        }
        if (absBits < 0x33000000) {
            return sign
        }
        if (absBits < 0x38800000) {
            val shift = 113 - (absBits ushr 23)
            val mant = 0x00800000 or (absBits and 0x007FFFFF)
            val rounded = (mant + (1 shl (shift + 12)) - 1 + ((mant ushr (shift + 13)) and 1)) ushr (shift + 13)
            return sign or rounded
        }
        val rounded = absBits + 0xC8000FFF.toInt() + ((absBits ushr 13) and 1)
        return if ((rounded and 0x7F800000) >= 0x47800000) {
            sign or 0x7C00
        } else {
            sign or ((rounded ushr 13) and 0x7FFF)
        }
    }
}
