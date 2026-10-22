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

package com.voicebubble.openflow.core.speech

import android.content.Context
import android.os.Build
import android.system.Os
import com.voicebubble.openflow.core.debug.DebugLogManager
import com.voicebubble.openflow.settings.HardwareAccelerator
import java.io.File
import java.util.Locale

enum class NpuVendor(val displayName: String, val runtimeName: String) {
    QUALCOMM_HEXAGON("Qualcomm Hexagon NPU", "QNN HTP / FastRPC CDSP"),
    MEDIATEK_NEUROPILOT("MediaTek NeuroPilot APU", "Neuron / APU MDLA"),
    GOOGLE_TENSOR("Google Tensor TPU", "EdgeTPU / DarwiNN"),
    SAMSUNG_EXYNOS("Samsung Exynos NPU", "ENN / EDEN Runtime"),
    HISILICON_KIRIN("HiSilicon Kirin NPU", "HiAI DaVinci"),
    ROCKCHIP_RKNN("Rockchip NPU", "RKNN Runtime"),
    GENERIC_NNAPI("Android NNAPI Accelerator", "NNAPI Hardware HAL"),
    NONE("No Dedicated NPU", "CPU / GPU Fallback")
}

data class NpuHardwareProfile(
    val vendor: NpuVendor,
    val socModel: String,
    val npuArchitecture: String,
    val hasHardwareNpu: Boolean,
    val hasFastRpcSupport: Boolean,
    val hasNnapiSupport: Boolean,
    val dispatchLibraryDir: String?,
    val dspLibraryPath: String?,
    val discoveredLibraries: List<String>,
    val recommendedCpuThreads: Int
) {
    val summaryLabel: String
        get() = when (vendor) {
            NpuVendor.QUALCOMM_HEXAGON -> "Qualcomm Hexagon ($npuArchitecture • ${vendor.runtimeName})"
            NpuVendor.MEDIATEK_NEUROPILOT -> "MediaTek NeuroPilot ($npuArchitecture)"
            NpuVendor.GOOGLE_TENSOR -> "Google Tensor TPU ($npuArchitecture)"
            NpuVendor.SAMSUNG_EXYNOS -> "Samsung Exynos NPU ($npuArchitecture)"
            NpuVendor.HISILICON_KIRIN -> "HiSilicon Kirin NPU ($npuArchitecture)"
            NpuVendor.ROCKCHIP_RKNN -> "Rockchip NPU ($npuArchitecture)"
            NpuVendor.GENERIC_NNAPI -> "NNAPI NPU ($npuArchitecture)"
            NpuVendor.NONE -> "Multi-Core CPU / OpenCL GPU"
        }
}

/**
 * Hardware NPU & Accelerator Discovery and Native Runtime Configurator.
 *
 * Provides first-class support for:
 * - Qualcomm Hexagon NPUs (QNN HTP / Hexagon DSP V66..V79, FastRPC `libcdsprpc.so`, ADSP/CDSP skel paths)
 * - MediaTek NeuroPilot APUs (Dimensity / Helio `libneuron_adapter.so`, `libapusys.so`)
 * - Google Tensor TPUs (G1..G4 `libedgetpu_client.so`, `libdarwinn.so`)
 * - Samsung Exynos NPUs (Exynos `libenn_user.so`, `libeden_rt.so`)
 * - HiSilicon Kirin NPUs (`libhiai.so`, `libai_client.so`)
 * - Rockchip RKNN & Android NNAPI (`libneuralnetworks.so`)
 */
object NpuHardwareManager {

    private val VENDOR_LIB_DIRS = listOf(
        "/vendor/lib64",
        "/system/vendor/lib64",
        "/odm/lib64",
        "/vendor/lib64/egl",
        "/system/lib64"
    )

    private val HEXAGON_DSP_DIRS = listOf(
        "/vendor/lib/rfsa/adsp",
        "/system/vendor/lib/rfsa/adsp",
        "/vendor/dsp/cdsp",
        "/vendor/dsp/adsp",
        "/odm/lib/rfsa/adsp",
        "/vendor/lib64",
        "/system/vendor/lib64"
    )

    private val QUALCOMM_NPU_LIBS = listOf(
        "libQnnHtp.so",
        "libQnnSystem.so",
        "libQnnHtpPrepare.so",
        "libQnnDsp.so",
        "libLiteRtDispatch_Qualcomm.so",
        "libLiteRtQualcommAccelerator.so",
        "libQnnHtpV79Stub.so",
        "libQnnHtpV75Stub.so",
        "libQnnHtpV73Stub.so",
        "libQnnHtpV69Stub.so",
        "libQnnHtpV68Stub.so",
        "libSNPE.so",
        "libhta.so",
        "libhexagon_nn_skel.so",
        "libcdsprpc.so",
        "libadsprpc.so"
    )

    private val MEDIATEK_NPU_LIBS = listOf(
        "libneuron_adapter.so",
        "libneuronusdk_adapter.mtk.so",
        "libneuron_runtime.so",
        "libapusys.so",
        "libnir_neon_driver_ndk.mtk.so",
        "libLiteRtNeuroPilotAccelerator.so"
    )

    private val GOOGLE_TENSOR_LIBS = listOf(
        "libedgetpu_client.so",
        "libedgetpu_util.so",
        "libdarwinn.so",
        "libLiteRtGoogleTensorAccelerator.so"
    )

    private val SAMSUNG_EXYNOS_LIBS = listOf(
        "libenn_user.so",
        "libenn_Wrapper.so",
        "libeden_rt.so",
        "libnpu.so"
    )

    private val HISILICON_KIRIN_LIBS = listOf(
        "libhiai.so",
        "libhiai_ir.so",
        "libai_client.so",
        "libnpu_drv.so"
    )

    private val ROCKCHIP_NPU_LIBS = listOf(
        "librknnrt.so",
        "librknn_api.so"
    )

    private val QUALCOMM_SOC_REGEX = Regex(
        "\\b(sm[4678]\\d{3}|sdm\\d{3}|qcs\\d{3,4}|lahaina|taro|kalama|pineapple|sun|crow|cliff|pakala|canoe)\\b"
    )

    @Volatile
    private var cachedProfile: NpuHardwareProfile? = null

    fun detectHardwareProfile(): NpuHardwareProfile {
        cachedProfile?.let { return it }

        val socRaw = buildSocSignature()
        val discoveredLibs = mutableListOf<String>()
        var dispatchDir: String? = null

        fun scanLibs(candidates: List<String>): List<String> {
            val found = mutableListOf<String>()
            for (dir in VENDOR_LIB_DIRS) {
                for (lib in candidates) {
                    val file = File(dir, lib)
                    if (file.exists()) {
                        found.add(file.absolutePath)
                        if (dispatchDir == null && dir != "/system/lib64") {
                            dispatchDir = dir
                        }
                    }
                }
            }
            return found
        }

        val qcomFound = scanLibs(QUALCOMM_NPU_LIBS)
        val mtkFound = scanLibs(MEDIATEK_NPU_LIBS)
        val tensorFound = scanLibs(GOOGLE_TENSOR_LIBS)
        val exynosFound = scanLibs(SAMSUNG_EXYNOS_LIBS)
        val kirinFound = scanLibs(HISILICON_KIRIN_LIBS)
        val rockchipFound = scanLibs(ROCKCHIP_NPU_LIBS)

        discoveredLibs.addAll(qcomFound)
        discoveredLibs.addAll(mtkFound)
        discoveredLibs.addAll(tensorFound)
        discoveredLibs.addAll(exynosFound)
        discoveredLibs.addAll(kirinFound)
        discoveredLibs.addAll(rockchipFound)

        val hasNnapi = File("/system/lib64/libneuralnetworks.so").exists() ||
            File("/apex/com.android.neuralnetworks/lib64/libneuralnetworks.so").exists() ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val hasFastRpc = qcomFound.any { it.endsWith("libcdsprpc.so") || it.endsWith("libadsprpc.so") } ||
            File("/dev/adsprpc-smd").exists() ||
            File("/dev/cdsprpc-smd").exists()

        val existingDspDirs = HEXAGON_DSP_DIRS.filter { File(it).isDirectory }
        val dspPath = if (existingDspDirs.isNotEmpty()) {
            existingDspDirs.joinToString(";")
        } else {
            "/vendor/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/lib64"
        }

        val classified = classifySocAndVendor(
            socSignature = socRaw,
            hasQualcommLibs = qcomFound.isNotEmpty() || hasFastRpc,
            hasMediaTekLibs = mtkFound.isNotEmpty(),
            hasTensorLibs = tensorFound.isNotEmpty(),
            hasExynosLibs = exynosFound.isNotEmpty(),
            hasKirinLibs = kirinFound.isNotEmpty(),
            hasRockchipLibs = rockchipFound.isNotEmpty(),
            hasNnapi = hasNnapi
        )

        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val recommendedThreads = computeOptimalCpuThreads(totalCores, classified.first)

        val profile = NpuHardwareProfile(
            vendor = classified.first,
            socModel = socRaw.ifBlank { Build.HARDWARE ?: "Unknown SoC" },
            npuArchitecture = classified.second,
            hasHardwareNpu = classified.first != NpuVendor.NONE,
            hasFastRpcSupport = hasFastRpc || classified.first == NpuVendor.QUALCOMM_HEXAGON,
            hasNnapiSupport = hasNnapi,
            dispatchLibraryDir = dispatchDir ?: existingDspDirs.firstOrNull() ?: "/vendor/lib64",
            dspLibraryPath = dspPath,
            discoveredLibraries = discoveredLibs.distinct(),
            recommendedCpuThreads = recommendedThreads
        )
        cachedProfile = profile
        return profile
    }

    /**
     * Pure classification function (also unit-testable without device filesystem).
     */
    internal fun classifySocAndVendor(
        socSignature: String,
        hasQualcommLibs: Boolean = false,
        hasMediaTekLibs: Boolean = false,
        hasTensorLibs: Boolean = false,
        hasExynosLibs: Boolean = false,
        hasKirinLibs: Boolean = false,
        hasRockchipLibs: Boolean = false,
        hasNnapi: Boolean = true
    ): Pair<NpuVendor, String> {
        val norm = socSignature.lowercase(Locale.ROOT)

        // 1. Qualcomm Snapdragon / Hexagon NPU (HTP / CDSP / QNN)
        val isQualcommSoc = norm.contains("qcom") ||
            norm.contains("qualcomm") ||
            norm.contains("snapdragon") ||
            QUALCOMM_SOC_REGEX.containsMatchIn(norm)

        if (isQualcommSoc || hasQualcommLibs) {
            val arch = resolveQualcommHexagonArch(norm)
            return NpuVendor.QUALCOMM_HEXAGON to arch
        }

        // 2. MediaTek Dimensity / Helio NeuroPilot APU
        val isMediaTekSoc = norm.contains("mt68") ||
            norm.contains("mt69") ||
            norm.contains("mt67") ||
            norm.contains("mt81") ||
            norm.contains("dimensity") ||
            norm.contains("mediatek") ||
            norm.contains("helio") ||
            norm.contains("kompanio")

        if (isMediaTekSoc || hasMediaTekLibs) {
            val arch = when {
                norm.contains("mt6991") || norm.contains("9400") -> "APU 890 (Gen 8)"
                norm.contains("mt6989") || norm.contains("9300") -> "APU 790 (Gen 7)"
                norm.contains("mt6985") || norm.contains("9200") -> "APU 690 (Gen 6)"
                norm.contains("mt6983") || norm.contains("9000") -> "APU 590 (Gen 5)"
                norm.contains("mt6897") || norm.contains("8300") -> "APU 780"
                norm.contains("mt6895") || norm.contains("8200") || norm.contains("8100") -> "APU 580"
                norm.contains("mt68") -> "Dimensity APU MDLA"
                else -> "NeuroPilot APU"
            }
            return NpuVendor.MEDIATEK_NEUROPILOT to arch
        }

        // 3. Google Tensor TPU (EdgeTPU / DarwiNN)
        val isGoogleTensor = norm.contains("tensor") ||
            norm.contains("gs101") ||
            norm.contains("gs201") ||
            norm.contains("zuma") ||
            norm.contains("laguna")

        if (isGoogleTensor || hasTensorLibs) {
            val arch = when {
                norm.contains("laguna") || norm.contains("g5") -> "Tensor G5 TPU"
                norm.contains("zumapro") || norm.contains("g4") -> "Tensor G4 Rio TPU"
                norm.contains("zuma") || norm.contains("g3") -> "Tensor G3 Rio TPU"
                norm.contains("gs201") || norm.contains("g2") -> "Tensor G2 Janeiro TPU"
                norm.contains("gs101") || norm.contains("g1") -> "Tensor G1 Whitechapel TPU"
                else -> "EdgeTPU / DarwiNN"
            }
            return NpuVendor.GOOGLE_TENSOR to arch
        }

        // 4. Samsung Exynos NPU (ENN / EDEN)
        val isExynos = norm.contains("exynos") ||
            norm.contains("s5e99") ||
            norm.contains("s5e88") ||
            norm.contains("s5e98") ||
            norm.contains("erd")

        if (isExynos || hasExynosLibs) {
            val arch = when {
                norm.contains("9955") || norm.contains("2500") -> "Exynos 2500 NPU"
                norm.contains("9945") || norm.contains("2400") -> "Exynos 2400 Dual-NPU"
                norm.contains("9925") || norm.contains("2200") -> "Exynos 2200 Dual-NPU"
                norm.contains("9840") || norm.contains("2100") -> "Exynos 2100 Triple-NPU"
                else -> "Exynos ENN NPU"
            }
            return NpuVendor.SAMSUNG_EXYNOS to arch
        }

        // 5. HiSilicon Kirin NPU
        val isKirin = norm.contains("kirin") ||
            norm.contains("hi36") ||
            norm.contains("hi62") ||
            norm.contains("hisilicon")

        if (isKirin || hasKirinLibs) {
            return NpuVendor.HISILICON_KIRIN to "DaVinci NPU"
        }

        // 6. Rockchip RKNN NPU
        val isRockchip = norm.contains("rk3588") || norm.contains("rk356") || norm.contains("rockchip")
        if (isRockchip || hasRockchipLibs) {
            return NpuVendor.ROCKCHIP_RKNN to "RKNN NPU"
        }

        // 7. Generic NNAPI on modern arm64 devices
        val isArm64 = Build.SUPPORTED_ABIS?.any { it.contains("arm64") } == true
        if (hasNnapi && isArm64) {
            return NpuVendor.GENERIC_NNAPI to "ARM64 NNAPI HAL"
        }

        return NpuVendor.NONE to "CPU XNNPACK"
    }

    internal fun resolveQualcommHexagonArch(normSoc: String): String {
        return when {
            normSoc.contains("sm8750") || normSoc.contains("sun") || normSoc.contains("8 elite") ->
                "Hexagon V79 HTP"
            normSoc.contains("sm8650") || normSoc.contains("pineapple") || normSoc.contains("8 gen 3") ->
                "Hexagon V75 HTP"
            normSoc.contains("sm8635") || normSoc.contains("cliff") || normSoc.contains("8s gen 3") ->
                "Hexagon V73 HTP"
            normSoc.contains("sm8550") || normSoc.contains("kalama") || normSoc.contains("8 gen 2") ->
                "Hexagon V73 HTP"
            normSoc.contains("sm7675") || normSoc.contains("7+ gen 3") || normSoc.contains("sm7550") ->
                "Hexagon V73 HTP"
            normSoc.contains("sm8475") || normSoc.contains("sm8450") || normSoc.contains("taro") || normSoc.contains("8 gen 1") ->
                "Hexagon V69 HTP"
            normSoc.contains("sm7475") || normSoc.contains("7+ gen 2") ->
                "Hexagon V69 HTP"
            normSoc.contains("sm8350") || normSoc.contains("lahaina") || normSoc.contains("888") ->
                "Hexagon V68 HTP"
            normSoc.contains("sm8250") || normSoc.contains("865") || normSoc.contains("870") ->
                "Hexagon V66 HTP"
            normSoc.contains("sm7") || normSoc.contains("sm6") ->
                "Hexagon CDSP HTP"
            else -> "Hexagon HTP / QNN"
        }
    }

    internal fun computeOptimalCpuThreads(totalCores: Int, vendor: NpuVendor): Int {
        // On heterogeneous big.LITTLE / Prime+Perf+Efficiency mobile SoCs (e.g. 8-core Snapdragon / Dimensity),
        // scheduling more threads than performance cores causes little-core straggler stalls in XNNPACK.
        return when {
            totalCores >= 8 -> 4
            totalCores >= 6 -> 4
            totalCores >= 4 -> 3
            else -> 2
        }
    }

    /**
     * Applies native environment variables consumed by `libLiteRt.so`, `libVozAndroid.so`,
     * Qualcomm QNN/FastRPC (`libcdsprpc.so`), MediaTek Neuron, and Google Tensor runtimes.
     *
     * Returns a user-facing description of the active hardware delegate.
     */
    fun configureNativeRuntime(
        context: Context,
        requested: HardwareAccelerator,
        lowMemoryPressure: Boolean
    ): String {
        val profile = detectHardwareProfile()
        val npuCacheDir = File(context.cacheDir, "litert_npu_cache").apply { mkdirs() }
        val threads = if (lowMemoryPressure) {
            profile.recommendedCpuThreads.coerceAtMost(3)
        } else {
            profile.recommendedCpuThreads
        }

        val effectiveMode = when {
            lowMemoryPressure && requested == HardwareAccelerator.AUTO && !profile.hasHardwareNpu ->
                HardwareAccelerator.CPU
            requested == HardwareAccelerator.AUTO -> when {
                profile.hasHardwareNpu -> HardwareAccelerator.NPU
                else -> HardwareAccelerator.GPU
            }
            requested == HardwareAccelerator.NPU && !profile.hasHardwareNpu && !profile.hasNnapiSupport ->
                HardwareAccelerator.GPU
            else -> requested
        }

        try {
            // Native DAL LiteRT thread count (verified in dal_lrt_model_create -> getenv("DAL_LITERT_THREADS"))
            // and DAL_RUN_DEPTH=1 (verified in ParallelRuns.depth -> getenv("DAL_RUN_DEPTH")).
            // Cache dirs ensure compiled NPU/QNN/OpenCL/XNNPACK graphs are persisted across sessions and
            // FastRPC / DSP restarts instead of recompiling on the fly.
            Os.setenv("DAL_LITERT_THREADS", threads.toString(), true)
            Os.setenv("DAL_RUN_DEPTH", "1", true)
            Os.setenv("LITERT_NUM_THREADS", threads.toString(), true)
            Os.setenv("LITERT_NPU_CACHE_DIR", npuCacheDir.absolutePath, true)
            Os.setenv("LITERT_CACHE_DIR", npuCacheDir.absolutePath, true)
            Os.setenv("TFLITE_CACHE_DIR", npuCacheDir.absolutePath, true)
            Os.setenv("OPENCL_CACHE_DIR", npuCacheDir.absolutePath, true)

            when (effectiveMode) {
                HardwareAccelerator.NPU -> {
                    Os.setenv("LITERT_USE_NPU", "1", true)
                    Os.setenv("LITERT_USE_NNAPI", "1", true)
                    Os.setenv("LITERT_USE_GPU", "1", true)
                    Os.setenv("DAL_ACCELERATOR", "npu", true)
                    Os.setenv("NNAPI_ALLOW_FP16", "1", true)

                    profile.dispatchLibraryDir?.let { dir ->
                        Os.setenv("LITERT_DISPATCH_LIBRARY_DIR", dir, true)
                        Os.setenv("QNN_LIBRARY_PATH", dir, true)
                    }

                    // Qualcomm Hexagon HTP / CDSP FastRPC library paths & PDR (Process Domain Restart) recovery
                    if (profile.vendor == NpuVendor.QUALCOMM_HEXAGON || profile.hasFastRpcSupport) {
                        val dspPath = profile.dspLibraryPath
                            ?: "/vendor/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp"
                        Os.setenv("ADSP_LIBRARY_PATH", dspPath, true)
                        Os.setenv("CDSP_LIBRARY_PATH", dspPath, true)
                        // Use "sustained_high_performance" rather than "burst" when not in low-memory mode so
                        // back-to-back live chunks do not trigger Hexagon HTP thermal/voltage throttling or
                        // FastRPC CDSP watchdog timeouts on long dictations.
                        Os.setenv(
                            "QNN_HTP_PERF_MODE",
                            if (lowMemoryPressure) "balanced" else "sustained_high_performance",
                            true
                        )
                        Os.setenv("QNN_HTP_ENABLE_PDR", "1", true)
                        Os.setenv("FASTRPC_PERF_KERNEL", "1", true)
                        Os.setenv("HEXAGON_NPU_ENABLE", "1", true)
                    }

                    // MediaTek NeuroPilot APU hints
                    if (profile.vendor == NpuVendor.MEDIATEK_NEUROPILOT) {
                        Os.setenv("NEURON_RT_PERFORMANCE_MODE", if (lowMemoryPressure) "0" else "1", true)
                        Os.setenv("NEURON_RT_ALLOW_FP16", "1", true)
                        Os.setenv("MTK_NEUROPILOT_ENABLE", "1", true)
                    }

                    // Google Tensor EdgeTPU / DarwiNN hints
                    if (profile.vendor == NpuVendor.GOOGLE_TENSOR) {
                        Os.setenv("LITERT_GOOGLE_TENSOR_ENABLE", "1", true)
                    }

                    // Samsung Exynos ENN / EDEN NPU hints
                    if (profile.vendor == NpuVendor.SAMSUNG_EXYNOS) {
                        Os.setenv("ENN_PREFER_FP16", "1", true)
                    }
                }

                HardwareAccelerator.GPU -> {
                    Os.setenv("LITERT_USE_NPU", "0", true)
                    Os.setenv("LITERT_USE_NNAPI", "0", true)
                    Os.setenv("LITERT_USE_GPU", "1", true)
                    Os.setenv("DAL_ACCELERATOR", "gpu", true)
                }

                HardwareAccelerator.CPU -> {
                    Os.setenv("LITERT_USE_NPU", "0", true)
                    Os.setenv("LITERT_USE_NNAPI", "0", true)
                    Os.setenv("LITERT_USE_GPU", "0", true)
                    Os.setenv("DAL_ACCELERATOR", "cpu", true)
                }

                HardwareAccelerator.AUTO -> Unit
            }
        } catch (t: Throwable) {
            DebugLogManager.logWarning(
                "NpuHardwareManager",
                "Failed to apply native env vars: ${t.message}"
            )
        }

        val activeLabel = when (effectiveMode) {
            HardwareAccelerator.NPU -> {
                val prefix = if (requested == HardwareAccelerator.AUTO) "Auto -> " else ""
                "$prefix${profile.summaryLabel} ($threads CPU fallback threads)"
            }
            HardwareAccelerator.GPU -> {
                val prefix = if (requested == HardwareAccelerator.AUTO) "Auto -> " else ""
                "${prefix}GPU OpenCL/GLES + $threads-thread XNNPACK"
            }
            HardwareAccelerator.CPU -> {
                val suffix = if (lowMemoryPressure && requested != HardwareAccelerator.CPU) " • Low-RAM Safe" else ""
                "CPU ($threads-thread XNNPACK$suffix)"
            }
            HardwareAccelerator.AUTO -> "Auto (${profile.summaryLabel})"
        }

        DebugLogManager.logInfo(
            "NpuHardwareManager",
            "Configured accelerator: requested=${requested.id}, effective=${effectiveMode.id}, " +
                "vendor=${profile.vendor.name}, arch=${profile.npuArchitecture}, soc='${profile.socModel}', " +
                "threads=$threads, libs=${profile.discoveredLibraries.size}"
        )

        return activeLabel
    }

    /**
     * Resolves the `deviceId` string passed to native `LoadedModel(context, deviceId)`
     * based on the user's selected hardware accelerator and detected NPU vendor.
     */
    fun resolveNativeDeviceId(
        requested: HardwareAccelerator,
        lowMemoryPressure: Boolean
    ): String? {
        if (lowMemoryPressure) {
            return "cpu"
        }
        return when (requested) {
            HardwareAccelerator.CPU -> "cpu"
            HardwareAccelerator.GPU -> null // default native computeUnits (.cpuAndGPU / .all)
            HardwareAccelerator.NPU, HardwareAccelerator.AUTO -> null
        }
    }

    private fun buildSocSignature(): String {
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val soc = Build.SOC_MODEL
                if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) parts.add(soc)
                val mf = Build.SOC_MANUFACTURER
                if (!mf.isNullOrBlank() && mf != Build.UNKNOWN) parts.add(mf)
            }
        }
        runCatching {
            val hw = Build.HARDWARE
            if (!hw.isNullOrBlank() && hw != Build.UNKNOWN) parts.add(hw)
            val board = Build.BOARD
            if (!board.isNullOrBlank() && board != Build.UNKNOWN) parts.add(board)
        }
        for (prop in listOf("ro.soc.model", "ro.board.platform", "ro.hardware.chipname", "ro.ChipName")) {
            val value = readSystemProperty(prop)
            if (!value.isNullOrBlank()) {
                parts.add(value)
            }
        }
        return parts.distinct().joinToString(" ")
    }

    private fun readSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            val v = getMethod.invoke(null, key, "") as? String
            v?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
