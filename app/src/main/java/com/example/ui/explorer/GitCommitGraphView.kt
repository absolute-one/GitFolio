package com.example.ui.explorer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewCompact
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GraphSegmentRole
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.max

/**
 * High-contrast Git Graph lane colors modeled directly after the user's reference image:
 * Azure Blue, Hot Magenta, Neon Green, Warm Amber, Electric Purple, Cyber Cyan, Coral, Lime.
 */
private val GitGraphLanePalette = listOf(
    Color(0xFF1E98F5), // 0: Azure Blue
    Color(0xFFEC298B), // 1: Hot Magenta / Pink
    Color(0xFF00E639), // 2: Neon Green
    Color(0xFFF59E0B), // 3: Warm Amber / Orange
    Color(0xFFA82FFC), // 4: Electric Purple
    Color(0xFF00E5FF), // 5: Cyber Cyan
    Color(0xFFFF6E40), // 6: Coral Flame
    Color(0xFFC6FF00)  // 7: Acid Lime
)

private fun laneColorForIndex(index: Int): Color {
    val safeIdx = (index % GitGraphLanePalette.size).let {
        if (it < 0) it + GitGraphLanePalette.size else it
    }
    return GitGraphLanePalette[safeIdx]
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
    var compactTopologyOnly by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

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
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("git_graph_search_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = compactTopologyOnly,
                        onClick = { compactTopologyOnly = !compactTopologyOnly },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ViewCompact,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(if (compactTopologyOnly) "Minimal Graph" else "Detailed Commit Log")
                        },
                        modifier = Modifier.testTag("toggle_compact_topology_chip")
                    )
                }

                item {
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
                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
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

                item {
                    FilterChip(
                        selected = selectedBranchFilter == null,
                        onClick = { onSelectBranchFilter(null) },
                        label = { Text("All Branches") }
                    )
                }

                items(availableBranches.take(12), key = { it }) { branchName ->
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

        // Main Graph Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF16191E))
        ) {
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
                val maxLanes = max(commitGraph?.maxConcurrentLanes ?: 5, 5).coerceAtMost(5)
                val isFilteredSubset = searchQuery.isNotBlank() || selectedBranchFilter != null

                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("git_commit_graph_list")
                ) {
                    items(
                        items = filteredRows,
                        key = { it.commit.sha },
                        contentType = { "git_graph_commit_row" }
                    ) { row ->
                        GitCommitGraphRowItem(
                            row = row,
                            maxConcurrentLanes = maxLanes,
                            compactTopologyOnly = compactTopologyOnly,
                            isHighlightedRow = row.rowIndex == 2,
                            isFilteredSubset = isFilteredSubset,
                            onClick = { inspectedRow = row }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(56.dp))
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
 * Single virtualized row in the Git Commit Graph.
 *
 * Uses `Modifier.height(IntrinsicSize.Min)` so the row height adapts dynamically to the text
 * and font scale without ever clipping or overlapping commit messages, while the left-side
 * Canvas stretches to `fillMaxHeight()` so rails and Bezier S-curves connect seamlessly across rows.
 */
@Composable
private fun GitCommitGraphRowItem(
    row: GitGraphCommitRow,
    maxConcurrentLanes: Int,
    compactTopologyOnly: Boolean,
    isHighlightedRow: Boolean,
    isFilteredSubset: Boolean,
    onClick: () -> Unit
) {
    val laneSpacingDp = if (compactTopologyOnly) 22.dp else 17.dp
    val horizontalPadDp = 14.dp
    val graphColumnWidth = (horizontalPadDp * 2) + (laneSpacingDp * (maxConcurrentLanes - 1).coerceAtLeast(1))
    val rowColor = laneColorForIndex(row.commitColorIndex)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .defaultMinSize(minHeight = if (compactTopologyOnly) 52.dp else 72.dp)
            .background(
                if (isHighlightedRow) {
                    Color.White.copy(alpha = 0.055f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(end = 14.dp)
            .testTag("commit_graph_row_${row.commit.shortSha}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Multi-Lane S-Curve DAG Canvas (stretches 100% of the intrinsic row height)
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(graphColumnWidth)
        ) {
            val h = size.height
            val centerY = h * 0.5f
            val startPadPx = horizontalPadDp.toPx()
            val laneSpacingPx = laneSpacingDp.toPx()
            val strokeWidthPx = 3.dp.toPx()

            fun laneX(lane: Int): Float = startPadPx + (lane * laneSpacingPx)

            if (!isFilteredSubset) {
                for (seg in row.segments) {
                    val segColor = laneColorForIndex(seg.colorIndex)
                    val fromX = laneX(seg.fromLane)
                    val toX = laneX(seg.toLane)

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
                            val path = Path().apply {
                                moveTo(fromX, centerY)
                                cubicTo(
                                    x1 = fromX,
                                    y1 = centerY + (h - centerY) * 0.62f,
                                    x2 = toX,
                                    y2 = centerY + (h - centerY) * 0.38f,
                                    x3 = toX,
                                    y3 = h
                                )
                            }
                            drawPath(
                                path = path,
                                color = segColor,
                                style = Stroke(
                                    width = strokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }

                        GraphSegmentRole.TOP_TO_CENTER_CURVE -> {
                            val path = Path().apply {
                                moveTo(fromX, 0f)
                                cubicTo(
                                    x1 = fromX,
                                    y1 = centerY * 0.62f,
                                    x2 = toX,
                                    y2 = centerY * 0.38f,
                                    x3 = toX,
                                    y3 = centerY
                                )
                            }
                            drawPath(
                                path = path,
                                color = segColor,
                                style = Stroke(
                                    width = strokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
            }

            // Commit Node Circle on commitLane
            val nodeX = laneX(row.commitLane)
            val nodeRadiusPx = 5.8.dp.toPx()

            if (row.rowIndex == 0) {
                drawCircle(
                    color = Color(0xFF16191E),
                    radius = nodeRadiusPx + 2.dp.toPx(),
                    center = Offset(nodeX, centerY)
                )
                drawCircle(
                    color = rowColor,
                    radius = nodeRadiusPx,
                    center = Offset(nodeX, centerY),
                    style = Stroke(width = 3.dp.toPx())
                )
            } else {
                drawCircle(
                    color = Color(0xFF16191E),
                    radius = nodeRadiusPx + 1.8.dp.toPx(),
                    center = Offset(nodeX, centerY)
                )
                drawCircle(
                    color = rowColor,
                    radius = nodeRadiusPx,
                    center = Offset(nodeX, centerY)
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        if (compactTopologyOnly) {
            // Minimal Topology Mode (matches the exact reference screenshot layout `[ ⑂ | M ] M`)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (row.rowIndex == 0) {
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(14.dp)
                            .clip(CircleShape)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = rowColor,
                                radius = size.minDimension / 2f - 2.dp.toPx(),
                                style = Stroke(width = 2.5.dp.toPx())
                            )
                        }
                    }
                }

                TwoToneGitBranchBadge(
                    laneColor = rowColor,
                    label = row.commit.shortSha.take(7),
                    isHeadRow = row.rowIndex == 0,
                    compact = false
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = row.commit.subject,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (row.rowIndex == 0) FontWeight.Bold else FontWeight.Medium,
                    color = if (row.rowIndex == 0) Color(0xFFF2F4F8) else Color(0xFFB1BAC4),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            // Detailed Commit Log Mode:
            // Stacked 2-Row Right Pane so the Badge and Commit Message NEVER compete for width or overlap!
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Top Line: Two-Tone `[ ⑂ | Ref ]` Badge + Branch Name Pill + Author
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (row.rowIndex == 0) {
                        Box(
                            modifier = Modifier.size(12.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = rowColor,
                                    radius = size.minDimension / 2f - 1.5.dp.toPx(),
                                    style = Stroke(width = 2.2.dp.toPx())
                                )
                            }
                        }
                    }

                    TwoToneGitBranchBadge(
                        laneColor = rowColor,
                        label = row.commit.shortSha.take(7),
                        isHeadRow = row.rowIndex == 0,
                        compact = true
                    )

                    Surface(
                        color = rowColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = row.badgeLabel,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = rowColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = row.commit.authorName,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = Color(0xFF8B949E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Bottom Line: Full-width Commit Subject Message with generous line-height & zero overlap
                Text(
                    text = row.commit.subject,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = if (row.rowIndex == 0) FontWeight.Bold else FontWeight.Medium,
                    color = if (row.rowIndex == 0) Color(0xFFF2F4F8) else Color(0xFFD0D7DE),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Renders the two-compartment pill badge from the reference screenshot:
 * Left side: solid laneColor background with dark Git branch icon.
 * Right side: dark charcoal background with commit code / ref label.
 */
@Composable
private fun TwoToneGitBranchBadge(
    laneColor: Color,
    label: String,
    isHeadRow: Boolean,
    compact: Boolean = false
) {
    val borderColor = if (isHeadRow) laneColor else Color(0xFF6E7681)
    val badgeHeight = if (compact) 22.dp else 27.dp

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Color(0xFF262930),
        border = BorderStroke(1.1.dp, borderColor),
        modifier = Modifier.height(badgeHeight)
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left colored icon compartment
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(laneColor)
                    .padding(horizontal = if (compact) 5.dp else 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountTree,
                    contentDescription = null,
                    tint = Color(0xFF121418),
                    modifier = Modifier.size(if (compact) 12.dp else 14.dp)
                )
            }

            // Right dark text compartment
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = if (compact) 11.sp else 12.sp,
                    lineHeight = if (compact) 14.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHeadRow) Color.White else Color(0xFFD0D7DE),
                    maxLines = 1
                )
            }
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
