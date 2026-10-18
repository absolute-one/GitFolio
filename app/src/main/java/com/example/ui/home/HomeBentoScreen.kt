package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.BentoTileStyle
import com.example.domain.model.GitRepositoryCard
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CoralRose
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LavenderPop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeBentoScreen(
    repositories: List<GitRepositoryCard>,
    syncingRepoIds: Set<String>,
    isAddingRepo: Boolean,
    feedbackMessage: String?,
    prefilledRepoUrlForAddSheet: String?,
    darkThemeEnabled: Boolean,
    onToggleTheme: () -> Unit,
    onDismissFeedback: () -> Unit,
    onConsumePrefilledRepoUrl: () -> Unit,
    onOpenRepository: (String) -> Unit,
    onRefreshRepository: (String) -> Unit,
    onConfirmRemoveRepository: (String) -> Unit,
    onAddRepositorySubmit: (rawRepo: String, branch: String?, token: String, onDone: () -> Unit) -> Unit
) {
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var initialRepoInput by rememberSaveable { mutableStateOf("") }
    var repoPendingDeletion by remember { mutableStateOf<GitRepositoryCard?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(prefilledRepoUrlForAddSheet) {
        if (!prefilledRepoUrlForAddSheet.isNullOrBlank()) {
            initialRepoInput = prefilledRepoUrlForAddSheet
            showAddSheet = true
            onConsumePrefilledRepoUrl()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // ONLY the bottom-right [+] button to add a repository
            FloatingActionButton(
                onClick = {
                    initialRepoInput = ""
                    showAddSheet = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .size(60.dp)
                    .testTag("add_repo_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_repository),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val gridColumns = if (maxWidth >= 640.dp) 3 else 2

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                // Minimalist Header (no extra [+] button on top)
                HomeMinimalHeader(
                    repoCount = repositories.size,
                    darkThemeEnabled = darkThemeEnabled,
                    onToggleTheme = onToggleTheme
                )

                // Status feedback pill
                AnimatedVisibility(
                    visible = feedbackMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    feedbackMessage?.let { msg ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clickable { onDismissFeedback() }
                                .testTag("home_status_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (repositories.isEmpty()) {
                    EmptyBentoShelfView()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        contentPadding = PaddingValues(bottom = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("bento_repo_grid")
                    ) {
                        itemsIndexed(
                            items = repositories,
                            key = { _, repo -> repo.id },
                            span = { index, repo ->
                                val spanCount = if (index == 0 || repo.tileStyle == BentoTileStyle.HERO_WIDE) {
                                    minOf(2, gridColumns)
                                } else {
                                    1
                                }
                                GridItemSpan(spanCount)
                            }
                        ) { index, repo ->
                            val isHero = index == 0 || repo.tileStyle == BentoTileStyle.HERO_WIDE
                            BentoRepositoryTile(
                                repo = repo,
                                isHeroSpan = isHero,
                                isSyncing = syncingRepoIds.contains(repo.id),
                                onClick = { onOpenRepository(repo.id) },
                                onOpenInBrowserClick = {
                                    runCatching { uriHandler.openUri(repo.htmlUrl) }
                                },
                                onRefreshClick = { onRefreshRepository(repo.id) },
                                onRequestRemoveClick = { repoPendingDeletion = repo }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Repository Confirmation Popup
    repoPendingDeletion?.let { targetRepo ->
        AlertDialog(
            onDismissRequest = { repoPendingDeletion = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = "Remove Repository?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Remove \"${targetRepo.fullSlug}\" and its repository-specific credentials from your reading shelf?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idToRemove = targetRepo.id
                        repoPendingDeletion = null
                        onConfirmRemoveRepository(idToRemove)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoralRose,
                        contentColor = Color(0xFF14161B)
                    ),
                    modifier = Modifier.testTag("confirm_delete_repo_button")
                ) {
                    Text("Remove", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { repoPendingDeletion = null },
                    modifier = Modifier.testTag("cancel_delete_repo_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddSheet) {
        AddRepositoryBottomSheet(
            initialRepoUrl = initialRepoInput,
            isSubmitting = isAddingRepo,
            errorFeedback = feedbackMessage,
            onDismiss = { if (!isAddingRepo) showAddSheet = false },
            onSubmit = { rawRepo, branch, token ->
                onAddRepositorySubmit(rawRepo, branch, token) {
                    showAddSheet = false
                }
            }
        )
    }
}

@Composable
private fun HomeMinimalHeader(
    repoCount: Int,
    darkThemeEnabled: Boolean,
    onToggleTheme: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "LIVE GIT MARKDOWN READER",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$repoCount REPOS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        IconButton(
            onClick = onToggleTheme,
            modifier = Modifier.testTag("toggle_theme_button")
        ) {
            Icon(
                imageVector = if (darkThemeEnabled) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = "Toggle theme",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BentoRepositoryTile(
    repo: GitRepositoryCard,
    isHeroSpan: Boolean,
    isSyncing: Boolean,
    onClick: () -> Unit,
    onOpenInBrowserClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onRequestRemoveClick: () -> Unit
) {
    val accentColor = when (repo.accentIndex % 4) {
        0 -> ElectricAmber
        1 -> CyberMint
        2 -> CobaltAccent
        else -> LavenderPop
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin")
    val spinDegrees by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_rotation"
    )

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val syncedTimeLabel = remember(repo.lastSyncedEpochMs) {
        timeFormatter.format(Date(repo.lastSyncedEpochMs))
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isHeroSpan) 1.5.dp else 1.dp,
            color = if (isHeroSpan) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (isHeroSpan) 184.dp else 178.dp)
            .testTag("repo_tile_${repo.id}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Funky Bento geometric background art in Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val glowRadius = if (isHeroSpan) size.minDimension * 0.85f else size.minDimension * 0.7f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.92f, size.height * 0.12f),
                        radius = glowRadius
                    ),
                    center = Offset(size.width * 0.92f, size.height * 0.12f),
                    radius = glowRadius
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.12f),
                    center = Offset(size.width * 0.95f, size.height * 0.95f),
                    radius = if (isHeroSpan) 58.dp.toPx() else 38.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: Owner + Browser, Refresh, and Delete actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "@${repo.owner}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onOpenInBrowserClick,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("open_browser_${repo.id}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open repository in browser",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = onRefreshClick,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("refresh_repo_${repo.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh_repository),
                                tint = if (isSyncing) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .rotate(if (isSyncing) spinDegrees else 0f)
                            )
                        }
                        IconButton(
                            onClick = onRequestRemoveClick,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("remove_repo_${repo.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.remove_repository),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Middle: Repository Title & Description
                Column {
                    Text(
                        text = repo.name,
                        style = if (isHeroSpan) {
                            MaterialTheme.typography.headlineLarge
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (isHeroSpan) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = repo.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Row: Bento Metadata Pills (Branch, File count, Per-repo Auth indicator)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = accentColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountTree,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = repo.defaultBranch,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentColor
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${repo.totalFilesCount} files",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (isHeroSpan) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (repo.hasAuthToken) Icons.Default.Lock else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = if (repo.hasAuthToken) CyberMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (repo.hasAuthToken) "TOKEN · $syncedTimeLabel" else "PUBLIC · $syncedTimeLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun EmptyBentoShelfView() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .testTag("empty_repos_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.empty_repos_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap the + button in the bottom-right corner—or share a GitHub link from any app—to mount a repository with its own credentials.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRepositoryBottomSheet(
    initialRepoUrl: String,
    isSubmitting: Boolean,
    errorFeedback: String?,
    onDismiss: () -> Unit,
    onSubmit: (rawRepo: String, branch: String?, token: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var repoInput by rememberSaveable(initialRepoUrl) { mutableStateOf(initialRepoUrl) }
    var branchInput by rememberSaveable { mutableStateOf("") }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var showTokenPlain by rememberSaveable { mutableStateOf(false) }

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
            Text(
                text = "Add GitHub Repository",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Provide a GitHub repository URL or owner/repo slug along with its repository-specific OAuth / Personal Access Token.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                label = { Text("GitHub Repository (owner/repo or URL)") },
                placeholder = { Text("https://github.com/owner/repo") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("repo_url_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Repository OAuth / PAT Token (Repo-Specific)") },
                placeholder = { Text("ghp_... or github_pat_... (optional for public)") },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { showTokenPlain = !showTokenPlain }) {
                        Icon(
                            imageVector = if (showTokenPlain) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = "Toggle token visibility"
                        )
                    }
                },
                visualTransformation = if (showTokenPlain) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("repo_token_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = branchInput,
                onValueChange = { branchInput = it },
                label = { Text("Branch (optional — auto-detects default)") },
                placeholder = { Text("main or master") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("repo_branch_input")
            )

            if (!errorFeedback.isNullOrBlank() && errorFeedback.contains("40", ignoreCase = true) ||
                (!errorFeedback.isNullOrBlank() && errorFeedback.contains("Failed", ignoreCase = true)) ||
                (!errorFeedback.isNullOrBlank() && errorFeedback.contains("not found", ignoreCase = true)) ||
                (!errorFeedback.isNullOrBlank() && errorFeedback.contains("Invalid", ignoreCase = true))
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorFeedback.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = CoralRose
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    onSubmit(
                        repoInput,
                        branchInput.takeIf { it.isNotBlank() },
                        tokenInput
                    )
                },
                enabled = repoInput.isNotBlank() && !isSubmitting,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_add_repo_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Connecting to GitHub…", fontFamily = JetBrainsMonoFontFamily)
                } else {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add Repository",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
