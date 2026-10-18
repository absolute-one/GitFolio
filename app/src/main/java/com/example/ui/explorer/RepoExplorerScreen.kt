package com.example.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.RepoFileNode
import com.example.ui.ExplorerUiState
import com.example.ui.ExplorerViewMode
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LavenderPop

@Composable
fun RepoExplorerScreen(
    state: ExplorerUiState,
    onBack: () -> Unit,
    onSelectViewMode: (ExplorerViewMode) -> Unit,
    onRefreshTree: () -> Unit,
    onRequestBranchListRefresh: () -> Unit,
    onSwitchBranch: (String) -> Unit,
    onToggleFolder: (String) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleMarkdownFilter: () -> Unit,
    onCommitSearchQueryChange: (String) -> Unit,
    onSelectCommitBranchFilter: (String?) -> Unit,
    onToggleHighScaleGraphMode: () -> Unit,
    onOpenFile: (RepoFileNode) -> Unit
) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    var showBranchSelectorSheet by rememberSaveable { mutableStateOf(false) }

    val quickFiles by remember(state.allNodes) {
        derivedStateOf {
            state.allNodes.filter {
                !it.isDirectory && (it.isLicenseOrReadme || it.isPureMarkdown)
            }.take(6)
        }
    }

    val isSearchActive = state.searchQuery.isNotBlank() || state.markdownOnlyFilter
    val activeBranch = state.repository?.defaultBranch ?: "main"
    val availableBranches = state.availableBranches.ifEmpty { listOf(activeBranch) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top Explorer Bar + Segmented Mode Switcher (Files vs Git Tree View)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("explorer_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.repository?.name ?: "Repository",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.repository?.fullSlug ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Interactive Branch Switcher Pill right in the Explorer Header!
                        Surface(
                            onClick = {
                                onRequestBranchListRefresh()
                                showBranchSelectorSheet = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .testTag("explorer_branch_selector_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountTree,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = activeBranch,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Switch branch",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        state.repository?.let { repo ->
                            IconButton(
                                onClick = {
                                    runCatching { uriHandler.openUri(repo.branchTreeHtmlUrl) }
                                },
                                modifier = Modifier.testTag("explorer_open_browser_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open repository in browser",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = onRefreshTree,
                            modifier = Modifier.testTag("explorer_refresh_button")
                        ) {
                            if ((state.isRefreshingTree || state.isLoadingCommitGraph) && !state.isSwitchingBranch) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.refresh_repository),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Mode Switcher Bar: [ Files Tree ] vs [ Git Tree View (Commit Graph) ]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isFilesMode = state.viewMode == ExplorerViewMode.FILES_TREE
                        val isGraphMode = state.viewMode == ExplorerViewMode.GIT_COMMIT_GRAPH

                        Surface(
                            onClick = { onSelectViewMode(ExplorerViewMode.FILES_TREE) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isFilesMode) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isFilesMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mode_tab_files")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = if (isFilesMode) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Files (${state.allNodes.count { !it.isDirectory }})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isFilesMode) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isFilesMode) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        Surface(
                            onClick = { onSelectViewMode(ExplorerViewMode.GIT_COMMIT_GRAPH) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isGraphMode) {
                                CyberMint.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isGraphMode) {
                                    CyberMint
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mode_tab_git_tree")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountTree,
                                    contentDescription = null,
                                    tint = if (isGraphMode) {
                                        CyberMint
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val graphCountLabel = state.commitGraph?.totalCommits?.let { "($it)" } ?: ""
                                Text(
                                    text = "Git Tree View $graphCountLabel".trim(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isGraphMode) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isGraphMode) {
                                        CyberMint
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }

                    if (state.isSwitchingBranch || state.isRefreshingTree || state.isLoadingCommitGraph) {
                        LinearProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                        )
                    }
                }
            }

            // Content Area + Branch Switching Loader Effect
            Box(modifier = Modifier.fillMaxSize()) {
                when (state.viewMode) {
                    ExplorerViewMode.GIT_COMMIT_GRAPH -> {
                        GitCommitGraphView(
                            commitGraph = state.commitGraph,
                            filteredRows = state.filteredCommitRows,
                            isLoadingGraph = state.isLoadingCommitGraph,
                            searchQuery = state.commitSearchQuery,
                            selectedBranchFilter = state.selectedCommitBranchFilter,
                            availableBranches = availableBranches,
                            highScaleBenchmarkMode = state.highScaleGraphBenchmarkMode,
                            onSearchQueryChange = onCommitSearchQueryChange,
                            onSelectBranchFilter = onSelectCommitBranchFilter,
                            onToggleHighScaleMode = onToggleHighScaleGraphMode
                        )
                    }

                    ExplorerViewMode.FILES_TREE -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Optimized In-Repo Search & Quick Filter Controls
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    placeholder = { Text("Search files, paths, or fuzzy initials (e.g. rmd)…") },
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null
                                        )
                                    },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isSearchActive) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.padding(end = 4.dp)
                                                ) {
                                                    Text(
                                                        text = "${state.visibleNodes.size}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (state.searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { onSearchQueryChange("") }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Clear,
                                                        contentDescription = "Clear search"
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("explorer_search_input")
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    item {
                                        FilterChip(
                                            selected = state.markdownOnlyFilter,
                                            onClick = onToggleMarkdownFilter,
                                            label = { Text(if (state.markdownOnlyFilter) ".md & Docs Only" else "All Readable Files") },
                                            modifier = Modifier.testTag("filter_markdown_chip")
                                        )
                                    }

                                    item {
                                        Surface(
                                            onClick = onExpandAll,
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                            modifier = Modifier.testTag("expand_all_folders_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.UnfoldMore,
                                                    contentDescription = "Expand all",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Expand",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        Surface(
                                            onClick = onCollapseAll,
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                            modifier = Modifier.testTag("collapse_all_folders_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.UnfoldLess,
                                                    contentDescription = "Collapse all",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Collapse",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    items(quickFiles, key = { it.id }) { quickNode ->
                                        Surface(
                                            onClick = { onOpenFile(quickNode) },
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                            modifier = Modifier.testTag("quick_file_${quickNode.name}")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (quickNode.name.uppercase().startsWith("LICENSE") ||
                                                        quickNode.name.uppercase().startsWith("COPYING")
                                                    ) {
                                                        Icons.Default.Gavel
                                                    } else {
                                                        Icons.Default.Description
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = quickNode.name,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (state.visibleNodes.isEmpty() && !state.isRefreshingTree && !state.isSwitchingBranch) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (state.searchQuery.isNotBlank()) {
                                            "No files matching \"${state.searchQuery}\""
                                        } else {
                                            "No readable files indexed yet. Tap Refresh or verify your GitHub OAuth token."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("file_tree_list")
                                ) {
                                    items(
                                        items = state.visibleNodes,
                                        key = { it.id }
                                    ) { node ->
                                        val isExpanded = state.expandedFolderPaths.contains(node.path)
                                        FileTreeNodeRow(
                                            node = node,
                                            isExpanded = isExpanded,
                                            searchQuery = state.searchQuery,
                                            isFlatSearchMode = isSearchActive,
                                            onClick = { onOpenFile(node) }
                                        )
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(48.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Proper Loader Effect when switching branches
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.isSwitchingBranch,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f))
                            .testTag("branch_switching_loader"),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Switching Branch…",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Checking out '${state.switchingToBranchName ?: activeBranch}' & indexing Git tree",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBranchSelectorSheet) {
        SwitchBranchBottomSheet(
            activeBranch = activeBranch,
            branches = availableBranches,
            isLoadingBranches = state.isLoadingBranches,
            onDismiss = { showBranchSelectorSheet = false },
            onRefreshBranches = onRequestBranchListRefresh,
            onSelectBranch = { chosenBranch ->
                showBranchSelectorSheet = false
                if (chosenBranch != activeBranch) {
                    onSwitchBranch(chosenBranch)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchBranchBottomSheet(
    activeBranch: String,
    branches: List<String>,
    isLoadingBranches: Boolean,
    onDismiss: () -> Unit,
    onRefreshBranches: () -> Unit,
    onSelectBranch: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filterQuery by rememberSaveable { mutableStateOf("") }

    val filteredBranches = remember(branches, filterQuery) {
        val q = filterQuery.trim().lowercase()
        if (q.isEmpty()) branches
        else branches.filter { it.lowercase().contains(q) }
    }

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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Switch Branch",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Current branch: $activeBranch · ${branches.size} branches available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onRefreshBranches) {
                    if (isLoadingBranches) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh branches",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (branches.size > 4) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = { Text("Filter branches…") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("branch_selector_list")
            ) {
                items(
                    items = filteredBranches,
                    key = { it }
                ) { branchName ->
                    val isCurrent = branchName == activeBranch
                    Surface(
                        onClick = { onSelectBranch(branchName) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("switch_branch_item_$branchName")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountTree,
                                    contentDescription = null,
                                    tint = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = branchName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Active branch",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
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
private fun FileTreeNodeRow(
    node: RepoFileNode,
    isExpanded: Boolean,
    searchQuery: String,
    isFlatSearchMode: Boolean,
    onClick: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "folder_chevron"
    )

    val badgeColor = when {
        node.isDirectory -> ElectricAmber
        node.isPureMarkdown -> CyberMint
        node.name.uppercase().startsWith("LICENSE") || node.name.uppercase().startsWith("COPYING") -> LavenderPop
        else -> CobaltAccent
    }

    val visualDepth = if (isFlatSearchMode) 0 else node.depth
    val highlightColor = MaterialTheme.colorScheme.primary

    val highlightedName = remember(node.name, searchQuery, highlightColor) {
        buildSearchHighlightedText(node.name, searchQuery, highlightColor)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (node.isDirectory) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (node.isDirectory) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tree_node_${node.path}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = (12 + (visualDepth * 18)).dp,
                    end = 12.dp,
                    top = 11.dp,
                    bottom = 11.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (visualDepth > 0) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (node.isDirectory) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse folder" else "Expand folder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                val fileIcon = when {
                    node.name.uppercase().startsWith("LICENSE") || node.name.uppercase().startsWith("COPYING") -> Icons.Default.Gavel
                    node.isPureMarkdown -> Icons.Default.Description
                    else -> Icons.Default.Code
                }
                Icon(
                    imageVector = fileIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightedName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (node.isDirectory || node.isLicenseOrReadme) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if ((isFlatSearchMode || !node.isDirectory) && node.parentPath.isNotEmpty()) {
                    Text(
                        text = node.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (node.isDirectory) {
                Surface(
                    color = badgeColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isExpanded) "OPEN" else "DIR",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = node.extension.uppercase().ifBlank { "DOC" },
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (node.sizeBytes > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formatByteSize(node.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun buildSearchHighlightedText(
    text: String,
    rawQuery: String,
    highlightColor: Color
): AnnotatedString {
    val query = rawQuery.trim()
    if (query.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        append(text)
        val lowerText = text.lowercase()
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            var startIndex = lowerText.indexOf(token)
            while (startIndex >= 0) {
                addStyle(
                    style = SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.Bold
                    ),
                    start = startIndex,
                    end = (startIndex + token.length).coerceAtMost(text.length)
                )
                startIndex = lowerText.indexOf(token, startIndex + token.length)
            }
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> String.format("%.1fKB", bytes / 1024f)
        else -> String.format("%.1fMB", bytes / (1024f * 1024f))
    }
}
