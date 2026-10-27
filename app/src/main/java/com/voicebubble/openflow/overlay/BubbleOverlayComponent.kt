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

package com.voicebubble.openflow.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.speech.PipelinePhase

@Composable
fun BubbleOverlayComponent(
    audioController: AudioRecordingController,
    bubbleState: OverlayBubbleState,
    isInputFieldFocused: Boolean,
    processingProgress: String,
    bubbleSize: Float = 52f,
    pipelinePhase: PipelinePhase = PipelinePhase.READY,
    isModelLoaded: Boolean = true,
    onDragDelta: (Float, Float) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val isVisible = isInputFieldFocused || bubbleState != OverlayBubbleState.IDLE
    val visibilityProgress by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = if (isVisible) {
            spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMedium
            )
        } else {
            tween(durationMillis = 150, easing = FastOutSlowInEasing)
        },
        label = "m3BubbleVisibilityProgress"
    )

    val colorScheme = MaterialTheme.colorScheme
    val isLoadingModels = pipelinePhase == PipelinePhase.LOADING || pipelinePhase == PipelinePhase.WARMING_UP
    val rms by audioController.rmsLevel.collectAsState()

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isDragging by remember { mutableStateOf(false) }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
    val measuredTextWidthDp = remember(processingProgress, labelStyle, density) {
        if (processingProgress.isEmpty()) {
            0.dp
        } else {
            val widthPx = textMeasurer.measure(
                text = processingProgress,
                style = labelStyle,
                maxLines = 1,
                softWrap = false
            ).size.width
            with(density) { widthPx.toDp() }
        }
    }

    // Exact width morphing: only as wide as needed for the spinner + stage text with 12.dp side padding
    val targetWidth = when (bubbleState) {
        OverlayBubbleState.RECORDING -> (bubbleSize + 32f).dp
        OverlayBubbleState.PROCESSING -> {
            if (processingProgress.isNotEmpty()) {
                (measuredTextWidthDp + 45.dp).coerceAtLeast(bubbleSize.dp)
            } else {
                bubbleSize.dp
            }
        }
        OverlayBubbleState.SUCCESS -> bubbleSize.dp
        OverlayBubbleState.IDLE -> bubbleSize.dp
    }

    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3BubbleWidth"
    )

    val height by animateDpAsState(
        targetValue = bubbleSize.dp,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3BubbleHeight"
    )

    // Minimalist M3 corner radius (rounded FAB in IDLE, full pill when expanded)
    val cornerRadius by animateDpAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> (bubbleSize * 0.32f).dp
            else -> (bubbleSize * 0.50f).dp
        },
        animationSpec = spring(
            dampingRatio = 0.80f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3CornerRadius"
    )

    // Steady scale during recording (no pulsing); subtle tactile feedback only on press/drag
    val targetScale = when {
        isDragging -> 1.04f
        isPressed -> 0.94f
        else -> 1.0f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3BubbleScale"
    )

    val shadowElevation by animateDpAsState(
        targetValue = when {
            !isVisible -> 0.dp
            isDragging -> 8.dp
            isPressed -> 2.dp
            else -> 4.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "m3BubbleElevation"
    )

    // Clean Material Design 3 tonal color scheme with dedicated green confirmation for SUCCESS
    val containerColor by animateColorAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> colorScheme.primaryContainer
            OverlayBubbleState.RECORDING -> colorScheme.errorContainer
            OverlayBubbleState.PROCESSING -> colorScheme.secondaryContainer
            OverlayBubbleState.SUCCESS -> Color(0xFF00C853)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "m3ContainerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> colorScheme.onPrimaryContainer
            OverlayBubbleState.RECORDING -> colorScheme.onErrorContainer
            OverlayBubbleState.PROCESSING -> colorScheme.onSecondaryContainer
            OverlayBubbleState.SUCCESS -> Color.White
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "m3ContentColor"
    )

    val bubbleShape = RoundedCornerShape(cornerRadius)
    val effectiveScale = if (isVisible) {
        (scale * (0.88f + 0.12f * visibilityProgress)).coerceAtLeast(0.88f)
    } else {
        (scale * (0.4f + 0.6f * visibilityProgress)).coerceAtLeast(0.01f)
    }
    val effectiveAlpha = if (isVisible) 1f else visibilityProgress.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .padding(4.dp)
            .graphicsLayer {
                scaleX = effectiveScale
                scaleY = effectiveScale
                alpha = effectiveAlpha
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(width, height)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (!dragAmount.x.isNaN() && !dragAmount.y.isNaN()) {
                                onDragDelta(dragAmount.x, dragAmount.y)
                            }
                        }
                    )
                }
                .clip(bubbleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.material3.ripple()
                ) {
                    when (bubbleState) {
                        OverlayBubbleState.IDLE -> onStartRecording()
                        OverlayBubbleState.RECORDING -> onStopRecording()
                        else -> {}
                    }
                },
            shape = bubbleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 3.dp,
            shadowElevation = shadowElevation
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = bubbleState,
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
                            .togetherWith(
                                fadeOut(animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing))
                            )
                            .using(null)
                    },
                    label = "m3BubbleStateContent"
                ) { state ->
                    when (state) {
                        OverlayBubbleState.IDLE -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingModels) {
                                    CircularProgressIndicator(
                                        color = contentColor.copy(alpha = 0.55f),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size((bubbleSize * 0.68f).dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = "Start dictation",
                                    tint = contentColor,
                                    modifier = Modifier.size((bubbleSize * 0.46f).dp)
                                )
                            }
                        }

                        OverlayBubbleState.RECORDING -> {
                            RecordingBarsComponent(
                                rms = rms,
                                bubbleSize = bubbleSize,
                                contentColor = contentColor
                            )
                        }

                        OverlayBubbleState.PROCESSING -> {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = contentColor,
                                    strokeWidth = 2.2.dp,
                                    modifier = Modifier.size(
                                        if (processingProgress.isNotEmpty()) 15.dp else (bubbleSize * 0.44f).dp
                                    )
                                )
                                if (processingProgress.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = processingProgress,
                                        color = contentColor,
                                        style = labelStyle,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        OverlayBubbleState.SUCCESS -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Transcription complete",
                                    tint = contentColor,
                                    modifier = Modifier.size((bubbleSize * 0.46f).dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingBarsComponent(
    rms: Float,
    bubbleSize: Float,
    contentColor: Color
) {
    val clampedRms = rms.coerceIn(0f, 1f)
    val maxBarHeight = (bubbleSize * 0.48f).coerceAtLeast(16f)
    val minBarHeight = (bubbleSize * 0.16f).coerceAtLeast(6f)

    // Minimalist 3-bar voice equalizer responding strictly to real microphone voice level
    val barSpring = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val bar1 by animateDpAsState(
        targetValue = (minBarHeight + (maxBarHeight - minBarHeight) * (clampedRms * 0.65f)).dp,
        animationSpec = barSpring,
        label = "bar1"
    )
    val bar2 by animateDpAsState(
        targetValue = (minBarHeight + 3f + (maxBarHeight - minBarHeight - 3f) * clampedRms).dp,
        animationSpec = barSpring,
        label = "bar2"
    )
    val bar3 by animateDpAsState(
        targetValue = (minBarHeight + (maxBarHeight - minBarHeight) * (clampedRms * 0.70f)).dp,
        animationSpec = barSpring,
        label = "bar3"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.2.dp)
                    .height(bar1)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.85f))
            )
            Box(
                modifier = Modifier
                    .width(3.2.dp)
                    .height(bar2)
                    .clip(CircleShape)
                    .background(contentColor)
            )
            Box(
                modifier = Modifier
                    .width(3.2.dp)
                    .height(bar3)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.85f))
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Icon(
            imageVector = Icons.Rounded.Stop,
            contentDescription = "Stop recording",
            tint = contentColor,
            modifier = Modifier.size((bubbleSize * 0.38f).coerceIn(16f, 22f).dp)
        )
    }
}
