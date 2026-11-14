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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicebubble.openflow.core.audio.AudioRecordingController
import com.voicebubble.openflow.core.speech.PipelinePhase
import com.voicebubble.openflow.core.speech.ProcessingProgress
import kotlin.math.max

@Composable
fun BubbleOverlayComponent(
    audioController: AudioRecordingController,
    bubbleState: OverlayBubbleState,
    isInputFieldFocused: Boolean,
    processingProgress: String,
    processingState: ProcessingProgress = ProcessingProgress.EMPTY,
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
                dampingRatio = 0.74f,
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
    val latestBubbleState by rememberUpdatedState(bubbleState)
    val latestStartRecording by rememberUpdatedState(onStartRecording)
    val latestStopRecording by rememberUpdatedState(onStopRecording)

    val effectiveState = remember(processingState, processingProgress) {
        if (processingState.label.isNotBlank()) {
            processingState
        } else if (processingProgress.isNotBlank()) {
            ProcessingProgress(label = processingProgress)
        } else {
            ProcessingProgress.EMPTY
        }
    }
    val hasAnyProgressText = effectiveState.label.isNotBlank()
    val hasChunkCounter = effectiveState.hasChunkProgress
    val chunkBadgeText = if (hasChunkCounter) {
        "${effectiveState.chunkCurrent}/${effectiveState.chunkTotal}"
    } else {
        ""
    }
    val detailTagText = effectiveState.detail?.trim()?.takeIf { it.isNotEmpty() }.orEmpty()

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp
    )
    val detailStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp
    )
    val counterStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    )

    val measuredContentWidthDp = remember(
        effectiveState.label,
        detailTagText,
        chunkBadgeText,
        labelStyle,
        detailStyle,
        counterStyle,
        density
    ) {
        if (effectiveState.label.isEmpty()) {
            0.dp
        } else {
            val labelWidthPx = textMeasurer.measure(
                text = effectiveState.label,
                style = labelStyle,
                maxLines = 1,
                softWrap = false
            ).size.width
            val detailWidthPx = if (detailTagText.isNotEmpty()) {
                textMeasurer.measure(
                    text = detailTagText,
                    style = detailStyle,
                    maxLines = 1,
                    softWrap = false
                ).size.width
            } else 0
            val counterWidthPx = if (chunkBadgeText.isNotEmpty()) {
                textMeasurer.measure(
                    text = chunkBadgeText,
                    style = counterStyle,
                    maxLines = 1,
                    softWrap = false
                ).size.width
            } else 0
            val labelDp = with(density) { labelWidthPx.toDp() }
            val detailDp = if (detailTagText.isNotEmpty()) with(density) { detailWidthPx.toDp() } + 15.dp else 0.dp
            val counterDp = if (chunkBadgeText.isNotEmpty()) with(density) { counterWidthPx.toDp() } + 11.dp else 0.dp
            labelDp + detailDp + counterDp
        }
    }

    val targetWidth = when (bubbleState) {
        OverlayBubbleState.RECORDING -> (bubbleSize + 40f).dp
        OverlayBubbleState.PROCESSING -> {
            if (hasAnyProgressText) {
                (measuredContentWidthDp + 44.dp).coerceAtLeast(bubbleSize.dp)
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
            dampingRatio = 0.76f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3BubbleWidth"
    )

    val height by animateDpAsState(
        targetValue = bubbleSize.dp,
        animationSpec = spring(
            dampingRatio = 0.80f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3BubbleHeight"
    )

    val cornerRadius by animateDpAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> (bubbleSize * 0.34f).dp
            else -> (bubbleSize * 0.50f).dp
        },
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3CornerRadius"
    )

    val targetScale = when {
        isDragging -> 1.05f
        isPressed -> 0.93f
        else -> 1.0f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = 0.68f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3BubbleScale"
    )

    val shadowElevation by animateDpAsState(
        targetValue = when {
            !isVisible -> 0.dp
            isDragging -> 10.dp
            isPressed -> 2.dp
            bubbleState == OverlayBubbleState.PROCESSING -> 6.dp
            else -> 4.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "m3BubbleElevation"
    )

    val containerColor by animateColorAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> colorScheme.primaryContainer
            OverlayBubbleState.RECORDING -> colorScheme.errorContainer
            OverlayBubbleState.PROCESSING -> colorScheme.secondaryContainer
            OverlayBubbleState.SUCCESS -> Color(0xFF00C853)
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "m3ContainerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when (bubbleState) {
            OverlayBubbleState.IDLE -> colorScheme.onPrimaryContainer
            OverlayBubbleState.RECORDING -> colorScheme.onErrorContainer
            OverlayBubbleState.PROCESSING -> colorScheme.onSecondaryContainer
            OverlayBubbleState.SUCCESS -> Color.White
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "m3ContentColor"
    )

    val targetFraction = effectiveState.chunkFraction ?: 0f
    val animatedChunkFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "m3ChunkProgress"
    )

    val bubbleShape = RoundedCornerShape(cornerRadius)
    val effectiveScale = if (isVisible) {
        (scale * (0.86f + 0.14f * visibilityProgress)).coerceAtLeast(0.86f)
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
                    when (latestBubbleState) {
                        OverlayBubbleState.IDLE, OverlayBubbleState.SUCCESS -> latestStartRecording()
                        OverlayBubbleState.RECORDING -> latestStopRecording()
                        OverlayBubbleState.PROCESSING -> {}
                    }
                },
            shape = bubbleShape,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 3.dp,
            shadowElevation = shadowElevation,
            border = BorderStroke(1.dp, contentColor.copy(alpha = 0.14f))
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
                        (fadeIn(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing))
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
                                        color = contentColor.copy(alpha = 0.6f),
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
                            Box(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = if (hasAnyProgressText) 12.dp else 0.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (hasChunkCounter && hasAnyProgressText) {
                                        ChunkProgressRing(
                                            fraction = animatedChunkFraction,
                                            color = contentColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            color = contentColor,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(
                                                if (hasAnyProgressText) 14.dp else (bubbleSize * 0.44f).dp
                                            )
                                        )
                                    }

                                    if (hasAnyProgressText) {
                                        Spacer(modifier = Modifier.width(7.dp))
                                        AnimatedContent(
                                            targetState = effectiveState.label,
                                            transitionSpec = {
                                                (fadeIn(tween(150)) + slideInVertically(tween(160)) { it / 3 })
                                                    .togetherWith(
                                                        fadeOut(tween(100)) + slideOutVertically(tween(120)) { -it / 3 }
                                                    )
                                            },
                                            label = "m3StageLabel"
                                        ) { currentLabel ->
                                            Text(
                                                text = currentLabel,
                                                color = contentColor,
                                                style = labelStyle,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }

                                        if (detailTagText.isNotEmpty()) {
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(contentColor.copy(alpha = 0.12f))
                                                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = detailTagText,
                                                    color = contentColor.copy(alpha = 0.85f),
                                                    style = detailStyle,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }

                                        if (hasChunkCounter) {
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = chunkBadgeText,
                                                color = contentColor.copy(alpha = 0.68f),
                                                style = counterStyle,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                // Minimalistic whisper-thin bottom progress strip for chunked dictations
                                if (hasChunkCounter && hasAnyProgressText) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(contentColor.copy(alpha = 0.12f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(animatedChunkFraction)
                                                .height(2.dp)
                                                .background(contentColor.copy(alpha = 0.68f))
                                        )
                                    }
                                }
                            }
                        }

                        OverlayBubbleState.SUCCESS -> {
                            SuccessCheckComponent(
                                bubbleSize = bubbleSize,
                                contentColor = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChunkProgressRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokePx = 2.dp.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0.05f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun SuccessCheckComponent(
    bubbleSize: Float,
    contentColor: Color
) {
    var popped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { popped = true }
    val checkScale by animateFloatAsState(
        targetValue = if (popped) 1f else 0.55f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3SuccessPop"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = checkScale
                scaleY = checkScale
            },
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

@Composable
fun RecordingBarsComponent(
    rms: Float,
    bubbleSize: Float,
    contentColor: Color
) {
    val clampedRms = rms.coerceIn(0f, 1f)
    val maxBarHeight = (bubbleSize * 0.48f).coerceAtLeast(16f)
    val minBarHeight = (bubbleSize * 0.14f).coerceAtLeast(5f)

    val pulseTransition = rememberInfiniteTransition(label = "recPulse")
    val liveDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )

    val barSpring = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val bar1 by animateDpAsState(
        targetValue = (minBarHeight + (maxBarHeight - minBarHeight) * max(0.08f, clampedRms * 0.55f)).dp,
        animationSpec = barSpring,
        label = "bar1"
    )
    val bar2 by animateDpAsState(
        targetValue = (minBarHeight + 3f + (maxBarHeight - minBarHeight - 3f) * max(0.14f, clampedRms)).dp,
        animationSpec = barSpring,
        label = "bar2"
    )
    val bar3 by animateDpAsState(
        targetValue = (minBarHeight + 1.5f + (maxBarHeight - minBarHeight - 1.5f) * max(0.10f, clampedRms * 0.80f)).dp,
        animationSpec = barSpring,
        label = "bar3"
    )
    val bar4 by animateDpAsState(
        targetValue = (minBarHeight + (maxBarHeight - minBarHeight) * max(0.06f, clampedRms * 0.48f)).dp,
        animationSpec = barSpring,
        label = "bar4"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = liveDotAlpha))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf(
                bar1 to 0.78f,
                bar2 to 1.0f,
                bar3 to 0.90f,
                bar4 to 0.72f
            ).forEach { (barHeight, alpha) ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(barHeight)
                        .clip(CircleShape)
                        .background(contentColor.copy(alpha = alpha))
                )
            }
        }

        Spacer(modifier = Modifier.width(9.dp))

        Icon(
            imageVector = Icons.Rounded.Stop,
            contentDescription = "Stop recording",
            tint = contentColor,
            modifier = Modifier.size((bubbleSize * 0.38f).coerceIn(16f, 22f).dp)
        )
    }
}
