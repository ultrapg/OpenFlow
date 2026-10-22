package com.voicebubble.openflow.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.settings.DictationChunkSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings for live "transcribe while recording" and automatic audio chunking.
 * Changes are persisted immediately and picked up by the next dictation (no service restart needed).
 */
@Composable
fun DictationChunkingSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(DictationChunkSettings.load(context)) }

    fun apply(updated: DictationChunkSettings, persist: Boolean) {
        settings = updated.sanitized()
        if (persist) DictationChunkSettings.save(context, settings)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Dictation Chunking",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Long dictations are split at natural pauses. With live transcription each finished chunk is " +
                "transcribed while you keep talking, so after you stop only the last chunk is left.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = settings.chunkingEnabled,
                onCheckedChange = { apply(settings.copy(chunkingEnabled = it), persist = true) }
            )
            Text(
                "Enable audio chunking",
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (!settings.chunkingEnabled) {
            Text(
                "Off: dictations are processed in one piece and live transcription is unavailable. " +
                    "Audio is only split if it exceeds 60 s or memory is very low.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
        }

        if (settings.chunkingEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.liveTranscriptionEnabled,
                    onCheckedChange = { apply(settings.copy(liveTranscriptionEnabled = it), persist = true) }
                )
                Text(
                    "Transcribe while still recording",
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        if (settings.liveActive) {
            ChunkSlider(
                label = String.format(Locale.US, "Pause that ends a chunk: %.2fs", settings.pauseMs / 1000.0),
                value = settings.pauseMs.toFloat(),
                range = DictationChunkSettings.MIN_PAUSE_MS.toFloat()..DictationChunkSettings.MAX_PAUSE_MS.toFloat(),
                steps = 34,
                onChange = { apply(settings.copy(pauseMs = ((it / 50f).roundToInt() * 50)), persist = false) },
                onFinished = { apply(settings, persist = true) }
            )
            ChunkSlider(
                label = String.format(Locale.US, "Minimum live chunk length: %.0fs", settings.minLiveChunkSeconds),
                value = settings.minLiveChunkSeconds,
                range = DictationChunkSettings.MIN_LIVE_CHUNK_SECONDS_LOWER..DictationChunkSettings.MIN_LIVE_CHUNK_SECONDS_UPPER,
                steps = 12,
                onChange = { apply(settings.copy(minLiveChunkSeconds = it.roundToInt().toFloat()), persist = false) },
                onFinished = { apply(settings, persist = true) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.adaptivePause,
                    onCheckedChange = { apply(settings.copy(adaptivePause = it), persist = true) }
                )
                Text(
                    "Smart pause: accept shorter pauses in long stretches",
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.reuseLanguage,
                    onCheckedChange = { apply(settings.copy(reuseLanguage = it), persist = true) }
                )
                Text(
                    "Detect language once per dictation (faster)",
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        if (settings.chunkingEnabled) ChunkSlider(
            label = String.format(Locale.US, "Maximum chunk length: %.0fs", settings.maxChunkSeconds),
            value = settings.maxChunkSeconds,
            range = DictationChunkSettings.MIN_MAX_CHUNK_SECONDS..DictationChunkSettings.MAX_MAX_CHUNK_SECONDS,
            steps = 21,
            onChange = { apply(settings.copy(maxChunkSeconds = it.roundToInt().toFloat()), persist = false) },
            onFinished = { apply(settings, persist = true) }
        )
        if (settings.chunkingEnabled) ChunkSlider(
            label = String.format(Locale.US, "Silence level: %.3f (lower = only very quiet pauses count)", settings.silenceThreshold),
            value = settings.silenceThreshold,
            range = DictationChunkSettings.MIN_SILENCE_THRESHOLD..DictationChunkSettings.MAX_SILENCE_THRESHOLD,
            steps = 0,
            onChange = { apply(settings.copy(silenceThreshold = (it * 1000f).roundToInt() / 1000f), persist = false) },
            onFinished = { apply(settings, persist = true) }
        )

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = { apply(DictationChunkSettings(), persist = true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset chunking to defaults")
        }
    }
}

@Composable
private fun ChunkSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )
    }
}
