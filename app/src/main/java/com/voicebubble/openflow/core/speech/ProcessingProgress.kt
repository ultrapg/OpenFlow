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

/**
 * Semantic pipeline stage displayed by the floating pill during audio transcription
 * and post-processing.
 */
enum class ProcessingStageKind {
    CONNECTING,
    LOADING_MODEL,
    DETECTING_LANGUAGE,
    TRANSCRIBING,
    FORMATTING,
    FINALIZING
}

/**
 * Structured live status reported by [SmartPipeline] and [StreamingDictationSession]
 * to the floating pill overlay so it can animate stage transitions smoothly and render
 * a compact, minimalistic chunk counter (e.g., `11/13`) alongside determinate progress.
 */
data class ProcessingProgress(
    val label: String,
    val detail: String? = null,
    val stage: ProcessingStageKind = ProcessingStageKind.TRANSCRIBING,
    val chunkCurrent: Int? = null,
    val chunkTotal: Int? = null,
    val isRemoteAi: Boolean = false
) {
    val hasChunkProgress: Boolean
        get() = chunkCurrent != null && chunkTotal != null && chunkTotal > 1

    val chunkFraction: Float?
        get() = if (chunkCurrent != null && chunkTotal != null && chunkTotal > 1) {
            (chunkCurrent.coerceIn(0, chunkTotal).toFloat() / chunkTotal.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    fun toDisplayString(): String = buildString {
        append(label)
        if (!detail.isNullOrBlank()) {
            append(" · ")
            append(detail)
        }
        if (hasChunkProgress) {
            append(" (")
            append(chunkCurrent)
            append('/')
            append(chunkTotal)
            append(')')
        }
    }

    companion object {
        val EMPTY = ProcessingProgress(label = "")
    }
}
