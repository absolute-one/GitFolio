package com.example.ui.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GraphSegmentRole
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * High-contrast Git Graph lane colors:
 * Azure Blue, Hot Magenta, Neon Green, Warm Amber, Electric Purple, Cyber Cyan, Coral, Lime.
 */
private val GitGraphLanePalette = arrayOf(
    Color(0xFF1E98F5), // 0: Azure Blue
    Color(0xFFEC298B), // 1: Hot Magenta / Pink
    Color(0xFF00E639), // 2: Neon Green
    Color(0xFFF59E0B), // 3: Warm Amber / Orange
    Color(0xFFA82FFC), // 4: Electric Purple
    Color(0xFF00E5FF), // 5: Cyber Cyan
    Color(0xFFFF6E40), // 6: Coral Flame
    Color(0xFFC6FF00)  // 7: Acid Lime
)

private val GraphBadgeShape = RoundedCornerShape(6.dp)
private val BranchPillShape = RoundedCornerShape(7.dp)
private val SearchInputShape = RoundedCornerShape(14.dp)
private val TelemetryBadgeShape = RoundedCornerShape(8.dp)

private fun laneColorForIndex(index: Int): Color {
    val size = GitGraphLanePalette.size
    val rem = index % size
    return GitGraphLanePalette[if (rem < 0) rem + size else rem]
}

@Stable
private class MaxWidthTracker(var widestPx: Int)

@Stable
private class GraphDrawMetrics(density: Density) {
    val startPadPx: Float = with(density) { 16.dp.toPx() }
    val laneSpacingPx: Float = with(density) { 20.dp.toPx() }
    val strokeWidthPx: Float = with(density) { 3.dp.toPx() }
    val nodeRadiusPx: Float = with(density) { 5.8.dp.toPx() }
    val headHaloRadiusPx: Float = nodeRadiusPx + with(density) { 2.dp.toPx() }
    val regularHaloRadiusPx: Float = nodeRadiusPx + with(density) { 1.8.dp.toPx() }
    val headRingStroke: Stroke = Stroke(width = strokeWidthPx)
    val curveStroke: Stroke = Stroke(
        width = strokeWidthPx,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )
    val miniHeadStroke: Stroke = Stroke(width = with(density) { 2.dp.toPx() })
    val miniHeadInsetPx: Float = with(density) { 1.4.dp.toPx() }

    fun laneX(lane: Int): Float = startPadPx + (lane * laneSpacingPx)
}

@Composable
fun GitCommitGraphView(
    commitGraph: GitRepoCommitGraph?,
    filteredRows: List<GitGraphCommitRow>,
    isLoadingGraph: Boolean,
    searchQuery: String,
    selectedBranchFilter: String?,
    availableBranches: List<String>,
    highScaleBenchmarkMode: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSelectBranchFilter: (String?) -> Unit,
    onToggleHighScaleMode: () -> Unit
) {
    var inspectedRow by remember { mutableStateOf<GitGraphCommitRow?>(null) }
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    val drawMetrics = remember(density) { GraphDrawMetrics(density) }

    val maxLanes = max(commitGraph?.maxConcurrentLanes ?: 8, 5)
    val laneSpacingDp = 20.dp
    val startPadDp = 16.dp
    val graphCanvasWidthDp = (startPadDp * 2) + (laneSpacingDp * (maxLanes - 1).coerceAtLeast(4))
    val idPanelWidthDp = 116.dp

    val graphWidthPx = with(density) { graphCanvasWidthDp.toPx() }
    val graphWidthIntPx = graphWidthPx.roundToInt()
    val idWidthPx = with(density) { idPanelWidthDp.toPx() }
    val idWidthIntPx = idWidthPx.roundToInt()
    val initialAnchorX = with(density) { 126.dp.toPx() }
    val minGraphPeekPx = with(density) { 24.dp.toPx() }
    val defaultMinMsgWidthPx = with(density) { 620.dp.toPx() }
    val minBehindScrollFloorPx = with(density) { 260.dp.toPx() }
    val headerHeightIntPx = with(density) { 28.dp.roundToPx() }
    val rowHeightIntPx = with(density) { 52.dp.roundToPx() }

    // Non-reactive tracker avoids measure-pass state invalidation & recomposition loops while scrolling
    val widestMessageTracker = remember { MaxWidthTracker(defaultMinMsgWidthPx.roundToInt()) }

    var anchorXPx by remember { mutableFloatStateOf(initialAnchorX) }
    val anchorXProvider = remember { { anchorXPx } }
    val onMessageMeasuredCallback = remember(widestMessageTracker) {
        { measuredWidth: Int ->
            if (measuredWidth > widestMessageTracker.widestPx) {
                widestMessageTracker.widestPx = measuredWidth
            }
        }
    }

    val displayedBranches = remember(availableBranches) {
        availableBranches.take(12)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("git_commit_graph_view")
    ) {
        // Top Graph Telemetry & Search Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Filter commits by SHA, message, branch, or author…") },
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                shape = SearchInputShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("git_graph_search_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "high_scale_toggle") {
                    FilterChip(
                        selected = highScaleBenchmarkMode,
                        onClick = onToggleHighScaleMode,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (highScaleBenchmarkMode) {
                                    "1K+ Commits · 100+ Branches"
                                } else {
                                    "Live Commits"
                                }
                            )
                        },
                        modifier = Modifier.testTag("toggle_high_scale_graph_chip")
                    )
                }

                if (commitGraph != null) {
                    item(key = "graph_telemetry_badge") {
                        Surface(
                            shape = TelemetryBadgeShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = "${filteredRows.size} COMMITS · ${commitGraph.totalBranchesRepresented} BRANCHES",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                item(key = "all_branches_chip") {
                    FilterChip(
                        selected = selectedBranchFilter == null,
                        onClick = { onSelectBranchFilter(null) },
                        label = { Text("All Branches") }
                    )
                }

                items(displayedBranches, key = { it }) { branchName ->
                    FilterChip(
                        selected = selectedBranchFilter == branchName,
                        onClick = {
                            onSelectBranchFilter(
                                if (selectedBranchFilter == branchName) null else branchName
                            )
                        },
                        label = { Text(branchName) }
                    )
                }
            }
        }

        // Main 2D Viewport with Unified 3-Phase Clamped Horizontal Scroll
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121418))
        ) {
            val viewportWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(idWidthPx + 1f)
            val maxIdX = (viewportWidthPx - idWidthPx).coerceAtLeast(0f)

            // Phase 1 max right scroll: allows Graph View to scroll rightwards behind Commit ID panel
            val maxGraphBehindScroll = (graphWidthPx - minGraphPeekPx).coerceAtLeast(80f)
            val maxAnchorX = maxIdX + maxGraphBehindScroll

            // Phase 3 max left scroll: allows Commit Message panel to scroll leftwards behind Commit ID panel
            val visibleMessageViewportAtLeftPin = (viewportWidthPx - idWidthPx).coerceAtLeast(120f)

            val horizontalScrollableState = rememberScrollableState { delta ->
                val maxMessageBehindScroll = (
                    widestMessageTracker.widestPx.toFloat() -
                        visibleMessageViewportAtLeftPin +
                        idWidthPx * 0.5f
                    ).coerceAtLeast(minBehindScrollFloorPx)
                val minAnchorX = -maxMessageBehindScroll
                val target = (anchorXPx + delta).coerceIn(minAnchorX, maxAnchorX)
                val consumed = target - anchorXPx
                anchorXPx = target
                consumed
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollable(
                        state = horizontalScrollableState,
                        orientation = Orientation.Horizontal,
                        flingBehavior = ScrollableDefaults.flingBehavior()
                    )
            ) {
                // Synchronized 3-Phase Header Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(Color(0xFF111318))
                        .border(0.5.dp, Color(0xFF262A34))
                        .clipToBounds()
                ) {
                    ThreePhaseClampedRowLayout(
                        graphWidthPx = graphWidthIntPx,
                        idWidthPx = idWidthIntPx,
                        rowHeightPx = headerHeightIntPx,
                        anchorXProvider = anchorXProvider,
                        onMessageWidthMeasured = {},
                        graphContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Text(
                                    text = "← GRAPH TREE",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.sp,
                                    color = Color(0xFF6E7681),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        },
                        messageContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(start = 12.dp, end = 24.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = "COMMIT MESSAGE · BRANCH · AUTHOR →",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.sp,
                                    color = Color(0xFF6E7681),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        },
                        idContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF161922))
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "COMMIT ID",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF58A6FF),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    )
                }

                if (isLoadingGraph && commitGraph == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Computing O(V+E) topological commit graph…",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (filteredRows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No commits match your current filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val isFilteredSubset = searchQuery.isNotBlank() || selectedBranchFilter != null

                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 6.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("git_commit_graph_list")
                    ) {
                        items(
                            items = filteredRows,
                            key = { it.commit.sha },
                            contentType = { "git_graph_commit_row" }
                        ) { row ->
                            ThreePhaseSlidingIdCommitRow(
                                row = row,
                                drawMetrics = drawMetrics,
                                graphWidthPx = graphWidthIntPx,
                                idWidthPx = idWidthIntPx,
                                rowHeightPx = rowHeightIntPx,
                                anchorXProvider = anchorXProvider,
                                onMessageWidthMeasured = onMessageMeasuredCallback,
                                isHighlightedRow = row.rowIndex == 2,
                                isFilteredSubset = isFilteredSubset,
                                onClick = { inspectedRow = row }
                            )
                        }

                        item(contentType = "bottom_spacer") {
                            Spacer(modifier = Modifier.height(56.dp))
                        }
                    }
                }
            }
        }
    }

    inspectedRow?.let { selected ->
        CommitDetailBottomSheet(
            repoSlug = commitGraph?.repoSlug.orEmpty(),
            row = selected,
            onDismiss = { inspectedRow = null },
            onOpenInBrowser = { url ->
                runCatching { uriHandler.openUri(url) }
            }
        )
    }
}

/**
 * Custom Single-Pass 3-Phase Clamped Horizontal Layout:
 * Avoids multi-content virtual layout wrapper allocations and reads `anchorXProvider()`
 * exclusively inside the placement block so horizontal scrolling runs in the placement phase
 * without recomposing or remeasuring text/canvas.
 */
@Composable
private fun ThreePhaseClampedRowLayout(
    graphWidthPx: Int,
    idWidthPx: Int,
    rowHeightPx: Int,
    anchorXProvider: () -> Float,
    onMessageWidthMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
    graphContent: @Composable () -> Unit,
    messageContent: @Composable () -> Unit,
    idContent: @Composable () -> Unit
) {
    Layout(
        content = {
            graphContent()
            messageContent()
            idContent()
        },
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
    ) { measurables, constraints ->
        val viewportWidth = constraints.maxWidth
        val height = rowHeightPx

        val graphPlaceable = measurables[0].measure(
            Constraints.fixed(width = graphWidthPx, height = height)
        )
        val messagePlaceable = measurables[1].measure(
            Constraints(
                minWidth = 0,
                maxWidth = Constraints.Infinity,
                minHeight = height,
                maxHeight = height
            )
        )
        onMessageWidthMeasured(messagePlaceable.width)

        val idPlaceable = measurables[2].measure(
            Constraints.fixed(width = idWidthPx, height = height)
        )

        layout(width = viewportWidth, height = height) {
            val maxIdX = (viewportWidth - idWidthPx).coerceAtLeast(0).toFloat()
            val anchorX = anchorXProvider()
            val idX = anchorX.coerceIn(0f, maxIdX).roundToInt()
            val graphX = (anchorX - graphPlaceable.width).roundToInt()
            val msgX = (anchorX + idWidthPx).roundToInt()

            graphPlaceable.place(x = graphX, y = 0, zIndex = 0f)
            messagePlaceable.place(x = msgX, y = 0, zIndex = 0f)
            idPlaceable.place(x = idX, y = 0, zIndex = 2f)
        }
    }
}

@Composable
private fun ThreePhaseSlidingIdCommitRow(
    row: GitGraphCommitRow,
    drawMetrics: GraphDrawMetrics,
    graphWidthPx: Int,
    idWidthPx: Int,
    rowHeightPx: Int,
    anchorXProvider: () -> Float,
    onMessageWidthMeasured: (Int) -> Unit,
    isHighlightedRow: Boolean,
    isFilteredSubset: Boolean,
    onClick: () -> Unit
) {
    val rowColor = laneColorForIndex(row.commitColorIndex)
    val rowBg = if (isHighlightedRow) Color(0xFF1E222A) else Color(0xFF121418)
    val idPanelBg = if (isHighlightedRow) Color(0xFF222731) else Color(0xFF151820)
    val isHeadRow = row.rowIndex == 0
    val reusablePath = remember { Path() }

    ThreePhaseClampedRowLayout(
        graphWidthPx = graphWidthPx,
        idWidthPx = idWidthPx,
        rowHeightPx = rowHeightPx,
        anchorXProvider = anchorXProvider,
        onMessageWidthMeasured = onMessageWidthMeasured,
        modifier = Modifier
            .background(rowBg)
            .clickable(onClick = onClick)
            .testTag("commit_graph_row_${row.commit.shortSha}"),
        graphContent = {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val h = size.height
                val centerY = h * 0.5f
                val strokeWidthPx = drawMetrics.strokeWidthPx

                if (!isFilteredSubset) {
                    val segments = row.segments
                    for (i in 0 until segments.size) {
                        val seg = segments[i]
                        val segColor = laneColorForIndex(seg.colorIndex)
                        val fromX = drawMetrics.laneX(seg.fromLane)
                        val toX = drawMetrics.laneX(seg.toLane)

                        when (seg.role) {
                            GraphSegmentRole.FULL_PASS_THROUGH -> {
                                drawLine(
                                    color = segColor,
                                    start = Offset(fromX, 0f),
                                    end = Offset(fromX, h),
                                    strokeWidth = strokeWidthPx,
                                    cap = StrokeCap.Butt
                                )
                            }

                            GraphSegmentRole.TOP_TO_CENTER -> {
                                drawLine(
                                    color = segColor,
                                    start = Offset(fromX, 0f),
                                    end = Offset(fromX, centerY),
                                    strokeWidth = strokeWidthPx,
                                    cap = StrokeCap.Butt
                                )
                            }

                            GraphSegmentRole.CENTER_TO_BOTTOM -> {
                                drawLine(
                                    color = segColor,
                                    start = Offset(fromX, centerY),
                                    end = Offset(fromX, h),
                                    strokeWidth = strokeWidthPx,
                                    cap = StrokeCap.Butt
                                )
                            }

                            GraphSegmentRole.CENTER_TO_BOTTOM_CURVE -> {
                                reusablePath.reset()
                                reusablePath.moveTo(fromX, centerY)
                                reusablePath.cubicTo(
                                    x1 = fromX,
                                    y1 = centerY + (h - centerY) * 0.62f,
                                    x2 = toX,
                                    y2 = centerY + (h - centerY) * 0.38f,
                                    x3 = toX,
                                    y3 = h
                                )
                                drawPath(
                                    path = reusablePath,
                                    color = segColor,
                                    style = drawMetrics.curveStroke
                                )
                            }

                            GraphSegmentRole.TOP_TO_CENTER_CURVE -> {
                                reusablePath.reset()
                                reusablePath.moveTo(fromX, 0f)
                                reusablePath.cubicTo(
                                    x1 = fromX,
                                    y1 = centerY * 0.62f,
                                    x2 = toX,
                                    y2 = centerY * 0.38f,
                                    x3 = toX,
                                    y3 = centerY
                                )
                                drawPath(
                                    path = reusablePath,
                                    color = segColor,
                                    style = drawMetrics.curveStroke
                                )
                            }
                        }
                    }
                }

                // Commit Node Circle on commitLane
                val nodeX = drawMetrics.laneX(row.commitLane)
                val nodeCenter = Offset(nodeX, centerY)

                if (isHeadRow) {
                    drawCircle(
                        color = rowBg,
                        radius = drawMetrics.headHaloRadiusPx,
                        center = nodeCenter
                    )
                    drawCircle(
                        color = rowColor,
                        radius = drawMetrics.nodeRadiusPx,
                        center = nodeCenter,
                        style = drawMetrics.headRingStroke
                    )
                } else {
                    drawCircle(
                        color = rowBg,
                        radius = drawMetrics.regularHaloRadiusPx,
                        center = nodeCenter
                    )
                    drawCircle(
                        color = rowColor,
                        radius = drawMetrics.nodeRadiusPx,
                        center = nodeCenter
                    )
                }
            }
        },
        messageContent = {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 12.dp, end = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = row.commit.subject,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (isHeadRow) FontWeight.Bold else FontWeight.Medium,
                    color = if (isHeadRow) Color(0xFFF2F4F8) else Color(0xFFB1BAC4),
                    maxLines = 1,
                    softWrap = false
                )

                Box(
                    modifier = Modifier
                        .background(rowColor.copy(alpha = 0.14f), GraphBadgeShape)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = row.badgeLabel,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = rowColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Text(
                    text = row.authorAndDateSummary,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = Color(0xFF6E7681),
                    maxLines = 1,
                    softWrap = false
                )
            }
        },
        idContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .background(idPanelBg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF2B313C))
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                ) {
                    if (isHeadRow) {
                        Canvas(
                            modifier = Modifier
                                .padding(end = 5.dp)
                                .size(11.dp)
                        ) {
                            drawCircle(
                                color = rowColor,
                                radius = size.minDimension * 0.5f - drawMetrics.miniHeadInsetPx,
                                style = drawMetrics.miniHeadStroke
                            )
                        }
                    }

                    TwoToneGitBranchBadge(
                        laneColor = rowColor,
                        label = row.shortSha7,
                        isHeadRow = isHeadRow
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF2B313C))
                )
            }
        }
    )
}

/**
 * Lightweight two-compartment pill badge without `IntrinsicSize.Min` or `Surface` overhead.
 */
@Composable
private fun TwoToneGitBranchBadge(
    laneColor: Color,
    label: String,
    isHeadRow: Boolean
) {
    val borderColor = if (isHeadRow) laneColor else Color(0xFF6E7681)

    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(BranchPillShape)
            .background(Color(0xFF262930))
            .border(1.2.dp, borderColor, BranchPillShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left colored icon compartment
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(laneColor)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.AccountTree,
                contentDescription = null,
                tint = Color(0xFF121418),
                modifier = Modifier.size(13.dp)
            )
        }

        // Right dark text compartment
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHeadRow) Color.White else Color(0xFFD0D7DE),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitDetailBottomSheet(
    repoSlug: String,
    row: GitGraphCommitRow,
    onDismiss: () -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val laneColor = laneColorForIndex(row.commitColorIndex)
    val commit = row.commit
    val canOpenInGitHub = commit.sha.length >= 7 && !commit.sha.contains("_")
    val commitUrl = "https://github.com/$repoSlug/commit/${commit.sha}"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TwoToneGitBranchBadge(
                    laneColor = laneColor,
                    label = commit.shortSha,
                    isHeadRow = row.isHollowHeadNode
                )
                Text(
                    text = row.badgeLabel,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelLarge,
                    color = laneColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = commit.subject,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${commit.authorName} · ${commit.authorDateIso} · Lane #${row.commitLane + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "COMMIT SHA",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = commit.sha,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "PARENTS (${commit.parentShas.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (commit.parentShas.isEmpty()) {
                            "Root commit (no parents)"
                        } else {
                            commit.parentShas.joinToString(", ")
                        },
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (commit.body.isNotBlank() && commit.body != commit.subject) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = commit.body,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (canOpenInGitHub && repoSlug.isNotBlank()) {
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = { onOpenInBrowser(commitUrl) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Commit on GitHub", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
