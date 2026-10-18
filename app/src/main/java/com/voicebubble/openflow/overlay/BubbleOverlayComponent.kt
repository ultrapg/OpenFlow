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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.speech.PipelinePhase
import kotlin.math.sin

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
                dampingRatio = Spring.DampingRatioMediumBouncy,
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

    // Material Design 3 Expressive width/height & corner-radius spring physics
    val targetWidth = when (bubbleState) {
        OverlayBubbleState.RECORDING -> (bubbleSize + 48f).dp
        OverlayBubbleState.PROCESSING -> {
            if (processingProgress.isNotEmpty()) (bubbleSize + 82f).dp else (bubbleSize + 8f).dp
        }
        OverlayBubbleState.SUCCESS -> (bubbleSize + 6f).dp
        OverlayBubbleState.IDLE -> bubbleSize.dp
    }

    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3BubbleWidth"
    )

    val height by animateDpAsState(
        targetValue = bubbleSize.dp,
        animationSpec = spring(
            dampingRatio = 0.68f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3BubbleHeight"
    )

    // M3 FAB squircle (32% radius) in IDLE morphing into a full M3 Stadium Pill (50% radius) when active
    val cornerRadius by animateDpAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> (bubbleSize * 0.32f).dp
            else -> (bubbleSize * 0.50f).dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3CornerRadius"
    )

    // Interactive spring scale physics (press squish, drag lift, and subtle voice amplitude pulse)
    val voicePulse = if (bubbleState == OverlayBubbleState.RECORDING) {
        (rms.coerceIn(0f, 1f) * 0.045f)
    } else {
        0f
    }
    val targetScale = when {
        isDragging -> 1.08f
        isPressed -> 0.90f
        bubbleState == OverlayBubbleState.SUCCESS -> 1.04f
        else -> 1.0f + voicePulse
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3BubbleScale"
    )

    val shadowElevation by animateDpAsState(
        targetValue = when {
            !isVisible -> 0.dp
            isDragging -> 12.dp
            bubbleState == OverlayBubbleState.RECORDING -> 9.dp
            isPressed -> 3.dp
            else -> 6.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "m3BubbleElevation"
    )

    // Material Design 3 semantic tonal color scheme transitions
    val containerColor by animateColorAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> colorScheme.primaryContainer
            OverlayBubbleState.RECORDING -> colorScheme.errorContainer
            OverlayBubbleState.PROCESSING -> colorScheme.secondaryContainer
            OverlayBubbleState.SUCCESS -> colorScheme.tertiaryContainer
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "m3ContainerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> colorScheme.onPrimaryContainer
            OverlayBubbleState.RECORDING -> colorScheme.onErrorContainer
            OverlayBubbleState.PROCESSING -> colorScheme.onSecondaryContainer
            OverlayBubbleState.SUCCESS -> colorScheme.onTertiaryContainer
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "m3ContentColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> colorScheme.primary.copy(alpha = 0.28f)
            OverlayBubbleState.RECORDING -> colorScheme.error.copy(alpha = 0.55f)
            OverlayBubbleState.PROCESSING -> colorScheme.secondary.copy(alpha = 0.35f)
            OverlayBubbleState.SUCCESS -> colorScheme.tertiary.copy(alpha = 0.45f)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "m3BorderColor"
    )

    val bubbleShape = RoundedCornerShape(cornerRadius)
    val effectiveScale = (scale * (0.35f + 0.65f * visibilityProgress)).coerceAtLeast(0.01f)
    val effectiveAlpha = visibilityProgress.coerceIn(0f, 1f)

    // Outer padding gives room for bouncy spring overshoot and M3 elevation shadow inside the overlay window
    Box(
        modifier = Modifier
            .padding(6.dp)
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
            tonalElevation = 6.dp,
            shadowElevation = shadowElevation,
            border = BorderStroke(1.2.dp, borderColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = bubbleState,
                    transitionSpec = {
                        (scaleIn(
                            initialScale = 0.72f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) + fadeIn(animationSpec = tween(140)))
                            .togetherWith(
                                scaleOut(
                                    targetScale = 0.78f,
                                    animationSpec = tween(110, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(110))
                            )
                            .using(SizeTransform(clip = false))
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
                                        color = colorScheme.primary,
                                        strokeWidth = 2.2.dp,
                                        modifier = Modifier.size((bubbleSize * 0.72f).dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = "Start dictation",
                                    tint = contentColor,
                                    modifier = Modifier.size((bubbleSize * 0.46f).dp)
                                )

                                // M3 Readiness status badge in bottom-right corner
                                val dotColor by animateColorAsState(
                                    targetValue = when {
                                        isLoadingModels -> colorScheme.tertiary
                                        isModelLoaded -> Color(0xFF00C853)
                                        else -> colorScheme.outline
                                    },
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    label = "m3StatusDotColor"
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(
                                            end = (bubbleSize * 0.12f).dp,
                                            bottom = (bubbleSize * 0.12f).dp
                                        )
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(containerColor)
                                        .padding(1.8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                            }
                        }

                        OverlayBubbleState.RECORDING -> {
                            RecordingBarsComponent(
                                rms = rms,
                                bubbleSize = bubbleSize,
                                barColor = colorScheme.error,
                                stopContainerColor = colorScheme.error,
                                stopIconColor = colorScheme.onError
                            )
                        }

                        OverlayBubbleState.PROCESSING -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = colorScheme.primary,
                                    strokeWidth = 2.4.dp,
                                    modifier = Modifier.size(
                                        if (processingProgress.isNotEmpty()) 18.dp else (bubbleSize * 0.48f).dp
                                    )
                                )
                                if (processingProgress.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = processingProgress,
                                        color = contentColor,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        OverlayBubbleState.SUCCESS -> {
                            val checkScale by animateFloatAsState(
                                targetValue = 1.0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "m3SuccessCheckScale"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scale(checkScale),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Transcription complete",
                                    tint = contentColor,
                                    modifier = Modifier.size((bubbleSize * 0.48f).dp)
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
    barColor: Color,
    stopContainerColor: Color,
    stopIconColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "m3WaveformWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "m3WavePhase"
    )

    val clampedRms = rms.coerceIn(0f, 1f)
    val maxBarHeight = (bubbleSize * 0.54f).coerceAtLeast(18f)
    val minBarHeight = (bubbleSize * 0.18f).coerceAtLeast(7f)

    // 4-bar M3 spring-driven equalizer with organic phase offsets + live microphone amplitude
    val bar1Target = minBarHeight + (maxBarHeight - minBarHeight) *
        (0.22f + 0.14f * ((sin(wavePhase.toDouble()) + 1.0).toFloat() * 0.5f) + clampedRms * 0.64f)
    val bar2Target = minBarHeight + (maxBarHeight - minBarHeight) *
        (0.32f + 0.18f * ((sin(wavePhase.toDouble() + 1.3) + 1.0).toFloat() * 0.5f) + clampedRms * 0.85f)
    val bar3Target = minBarHeight + (maxBarHeight - minBarHeight) *
        (0.28f + 0.18f * ((sin(wavePhase.toDouble() + 2.6) + 1.0).toFloat() * 0.5f) + clampedRms * 0.78f)
    val bar4Target = minBarHeight + (maxBarHeight - minBarHeight) *
        (0.20f + 0.14f * ((sin(wavePhase.toDouble() + 3.9) + 1.0).toFloat() * 0.5f) + clampedRms * 0.58f)

    val barSpring = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = 0.52f,
        stiffness = Spring.StiffnessMedium
    )

    val bar1 by animateDpAsState(targetValue = bar1Target.coerceAtMost(maxBarHeight).dp, animationSpec = barSpring, label = "bar1")
    val bar2 by animateDpAsState(targetValue = bar2Target.coerceAtMost(maxBarHeight).dp, animationSpec = barSpring, label = "bar2")
    val bar3 by animateDpAsState(targetValue = bar3Target.coerceAtMost(maxBarHeight).dp, animationSpec = barSpring, label = "bar3")
    val bar4 by animateDpAsState(targetValue = bar4Target.coerceAtMost(maxBarHeight).dp, animationSpec = barSpring, label = "bar4")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(bar1)
                    .clip(CircleShape)
                    .background(barColor.copy(alpha = 0.82f))
            )
            Box(
                modifier = Modifier
                    .width(3.8.dp)
                    .height(bar2)
                    .clip(CircleShape)
                    .background(barColor)
            )
            Box(
                modifier = Modifier
                    .width(3.8.dp)
                    .height(bar3)
                    .clip(CircleShape)
                    .background(barColor)
            )
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(bar4)
                    .clip(CircleShape)
                    .background(barColor.copy(alpha = 0.82f))
            )
        }

        // M3 Expressive filled stop icon badge inside the recording pill
        Box(
            modifier = Modifier
                .size((bubbleSize * 0.56f).coerceIn(24f, 36f).dp)
                .clip(CircleShape)
                .background(stopContainerColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = "Stop recording",
                tint = stopIconColor,
                modifier = Modifier.size((bubbleSize * 0.34f).coerceIn(15f, 22f).dp)
            )
        }
    }
}
