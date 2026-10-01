package com.voicebubble.openflow.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.audio.AudioRecordingController

@Composable
fun BubbleOverlayComponent(
    audioController: AudioRecordingController,
    bubbleState: OverlayBubbleState,
    isInputFieldFocused: Boolean,
    processingProgress: String,
    bubbleSize: Float = 52f,
    onDragDelta: (Float, Float) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    if (!isInputFieldFocused && bubbleState == OverlayBubbleState.IDLE) {
        return
    }

    val width by animateDpAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.RECORDING -> (bubbleSize + 24).dp
            else -> bubbleSize.dp
        },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    )

    val height by animateDpAsState(
        targetValue = bubbleSize.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    )

    val themePrimary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val themeSecondary = androidx.compose.material3.MaterialTheme.colorScheme.secondary
    val errorColor = androidx.compose.material3.MaterialTheme.colorScheme.error
    val onPrimary = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
    val backgroundColor = when (bubbleState) {
        OverlayBubbleState.RECORDING -> Color(0xFFE53935)
        OverlayBubbleState.SUCCESS -> Color(0xFF00C853)
        OverlayBubbleState.PROCESSING -> themeSecondary
        else -> themePrimary
    }

    Box(
        modifier = Modifier
            .size(width, height)
            .shadow(8.dp, RoundedCornerShape(percent = 50))
            .clip(RoundedCornerShape(percent = 50))
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
            .clickable {
                when (bubbleState) {
                    OverlayBubbleState.IDLE -> onStartRecording()
                    OverlayBubbleState.RECORDING -> onStopRecording()
                    else -> {}
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (bubbleState) {
            OverlayBubbleState.IDLE -> {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Mic",
                    tint = onPrimary,
                    modifier = Modifier.size((bubbleSize * 0.46f).dp)
                )
            }
            OverlayBubbleState.RECORDING -> {
                RecordingBarsComponent(audioController = audioController, onPrimary = onPrimary)
            }
            OverlayBubbleState.PROCESSING -> {
                if (processingProgress.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        text = processingProgress,
                        color = onPrimary,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                } else {
                    CircularProgressIndicator(
                        color = onPrimary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size((bubbleSize * 0.55f).dp)
                    )
                }
            }
            OverlayBubbleState.SUCCESS -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Success",
                    tint = onPrimary,
                    modifier = Modifier.size((bubbleSize * 0.46f).dp)
                )
            }
        }
    }
}

@Composable
fun RecordingBarsComponent(audioController: AudioRecordingController, onPrimary: Color) {
    val rms by audioController.rmsLevel.collectAsState()

    // Smooth animated bar heights
    val bar1 by animateDpAsState(
        targetValue = (10f + rms * 16f).dp,
        animationSpec = tween(80, easing = FastOutSlowInEasing)
    )
    val bar2 by animateDpAsState(
        targetValue = (14f + rms * 22f).dp,
        animationSpec = tween(80, easing = FastOutSlowInEasing)
    )
    val bar3 by animateDpAsState(
        targetValue = (10f + rms * 16f).dp,
        animationSpec = tween(80, easing = FastOutSlowInEasing)
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(modifier = Modifier.width(10.dp))

        // 3-bar equalizer with rounded caps
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(bar1)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(bar2)
                .clip(CircleShape)
                .background(Color.White)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(bar3)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Stop icon
        Icon(
            Icons.Default.Stop,
            contentDescription = "Stop",
            tint = onPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
    }
}
