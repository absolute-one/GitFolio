package com.example.ui.share

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.GitRepositoryCard
import com.example.ui.theme.CoralRose
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ElectricAmber
import kotlinx.coroutines.launch

sealed interface SharedRepoOverlayState {
    data object Hidden : SharedRepoOverlayState
    data class Mounting(val rawSharedUrl: String) : SharedRepoOverlayState
    data class MountedSuccess(val repository: GitRepositoryCard) : SharedRepoOverlayState
    data class MountError(val rawSharedUrl: String, val message: String) : SharedRepoOverlayState
}

/**
 * Full-screen translucent acknowledgement overlay when receiving a shared Git URL from a 3rd-party app.
 * - Draws an animated vector tick (checkmark).
 * - Uses a non-numeric contracting orbital aura & horizon bar that visually drains over 3 seconds
 *   before returning the user to the 3rd-party app.
 * - Tapping anywhere on the screen cancels the auto-close and keeps the user in GitFolio.
 * - Provides explicit "Close" (return immediately) and "Read" (open repo immediately) buttons.
 */
@Composable
fun SharedRepoAcknowledgementOverlay(
    state: SharedRepoOverlayState,
    onCloseAppImmediately: () -> Unit,
    onReadRepositoryNow: (GitRepositoryCard) -> Unit,
    onStayOnShelf: () -> Unit,
    onRetryWithToken: (String) -> Unit
) {
    if (state is SharedRepoOverlayState.Hidden) return

    var autoReturnCancelledByTap by remember(state) { mutableStateOf(false) }
    val tickProgress = remember(state) { Animatable(0f) }
    val portalHorizonFraction = remember(state) { Animatable(1f) }

    LaunchedEffect(state, autoReturnCancelledByTap) {
        if (state is SharedRepoOverlayState.MountedSuccess) {
            // Animate checkmark stroke drawing
            launch {
                tickProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                )
            }
            if (!autoReturnCancelledByTap) {
                // Smoothly contract the visual portal aura over 3,000ms (no numeric timer)
                portalHorizonFraction.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 3000, easing = LinearEasing)
                )
                if (!autoReturnCancelledByTap) {
                    onCloseAppImmediately()
                }
            } else {
                portalHorizonFraction.stop()
            }
        }
    }

    val backdropInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(
                interactionSource = backdropInteraction,
                indication = null
            ) {
                if (state is SharedRepoOverlayState.MountedSuccess) {
                    if (!autoReturnCancelledByTap) {
                        autoReturnCancelledByTap = true
                    } else {
                        onStayOnShelf()
                    }
                }
            }
            .padding(24.dp)
            .testTag("shared_repo_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.5.dp,
                color = when (state) {
                    is SharedRepoOverlayState.MountedSuccess -> CyberMint.copy(alpha = 0.65f)
                    is SharedRepoOverlayState.MountError -> CoralRose.copy(alpha = 0.65f)
                    else -> MaterialTheme.colorScheme.outline
                }
            ),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Tapping card also cancels auto-return so user stays in app
                    if (state is SharedRepoOverlayState.MountedSuccess && !autoReturnCancelledByTap) {
                        autoReturnCancelledByTap = true
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is SharedRepoOverlayState.Mounting -> {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Mounting Shared Repository…",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.rawSharedUrl,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }

                    is SharedRepoOverlayState.MountedSuccess -> {
                        val repo = state.repository

                        // Animated Vector Checkmark + Contracting Orbital Portal Ring
                        AnimatedTickWithPortalRing(
                            tickProgress = tickProgress.value,
                            portalFraction = if (autoReturnCancelledByTap) 1f else portalHorizonFraction.value,
                            isPaused = autoReturnCancelledByTap
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Surface(
                            color = CyberMint.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "ADDED TO GITFOLIO SHELF",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberMint,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = repo.fullSlug,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${repo.totalFilesCount} files indexed on ${repo.defaultBranch}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Creative Non-Numeric Visual Indicator of Auto-Return vs Stay
                        DepletingHorizonVisualBar(
                            fraction = if (autoReturnCancelledByTap) 1f else portalHorizonFraction.value,
                            isPaused = autoReturnCancelledByTap
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = if (autoReturnCancelledByTap) {
                                    CyberMint
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (autoReturnCancelledByTap) {
                                    "Auto-return paused · Staying in GitFolio"
                                } else {
                                    "Gliding back to source app · Tap screen to stay"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(22.dp))

                        // Explicit Close and Read Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCloseAppImmediately,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("shared_overlay_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Close")
                            }

                            Button(
                                onClick = {
                                    autoReturnCancelledByTap = true
                                    onReadRepositoryNow(repo)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("shared_overlay_read_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoStories,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Read",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    is SharedRepoOverlayState.MountError -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = CoralRose,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Could Not Mount Shared Repo",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCloseAppImmediately,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Close")
                            }
                            Button(
                                onClick = { onRetryWithToken(state.rawSharedUrl) },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Token")
                            }
                        }
                    }

                    SharedRepoOverlayState.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun AnimatedTickWithPortalRing(
    tickProgress: Float,
    portalFraction: Float,
    isPaused: Boolean
) {
    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 5.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)

            // Subtle track circle
            drawCircle(
                color = CyberMint.copy(alpha = 0.14f),
                radius = (size.minDimension - strokeWidth) / 2f,
                style = Stroke(width = strokeWidth)
            )

            // Contracting orbital portal arc (sweeps from 360 deg down to 0 deg as portal closes)
            val sweepAngle = 360f * portalFraction.coerceIn(0f, 1f)
            drawArc(
                brush = Brush.sweepGradient(
                    colors = if (isPaused) {
                        listOf(CyberMint, CyberMint)
                    } else {
                        listOf(ElectricAmber, CyberMint, ElectricAmber)
                    }
                ),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Inner glowing disc
            drawCircle(
                color = CyberMint.copy(alpha = 0.12f),
                radius = size.minDimension * 0.36f
            )

            // Animated Checkmark (Tick) vector path
            val fullTickPath = Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.52f)
                lineTo(size.width * 0.45f, size.height * 0.66f)
                lineTo(size.width * 0.72f, size.height * 0.36f)
            }
            val pathMeasure = PathMeasure()
            pathMeasure.setPath(fullTickPath, false)
            val partialTickPath = Path()
            pathMeasure.getSegment(
                startDistance = 0f,
                stopDistance = pathMeasure.length * tickProgress.coerceIn(0f, 1f),
                destination = partialTickPath,
                startWithMoveTo = true
            )

            drawPath(
                path = partialTickPath,
                color = CyberMint,
                style = Stroke(
                    width = 5.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/**
 * Creative non-numeric horizon bar that contracts symmetrically toward the center
 * to visualize the window gently closing to return to the calling app.
 */
@Composable
private fun DepletingHorizonVisualBar(
    fraction: Float,
    isPaused: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = fraction.coerceIn(0.02f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    brush = Brush.horizontalGradient(
                        colors = if (isPaused) {
                            listOf(CyberMint, CyberMint)
                        } else {
                            listOf(ElectricAmber, CyberMint, ElectricAmber)
                        }
                    )
                )
        )
    }
}
