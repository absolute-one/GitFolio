package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.domain.model.GitHostProvider
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ProviderCredential
import com.example.domain.model.RepoAccessibilityProbe
import com.example.ui.AddRepoProbeUiState
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
    providerCredentials: List<ProviderCredential>,
    syncingRepoIds: Set<String>,
    addRepoProbeState: AddRepoProbeUiState,
    isAddingRepo: Boolean,
    feedbackMessage: String?,
    darkThemeEnabled: Boolean,
    onToggleTheme: () -> Unit,
    onDismissFeedback: () -> Unit,
    onOpenRepository: (String) -> Unit,
    onRefreshRepository: (String) -> Unit,
    onConfirmRemoveRepository: (String) -> Unit,
    onSaveProviderToken: (GitHostProvider, String) -> Unit,
    onClearProviderToken: (GitHostProvider) -> Unit,
    onProbeRepositoryInput: (String) -> Unit,
    onResetRepoProbe: () -> Unit,
    onAddRepositorySubmit: (rawRepo: String, selectedBranch: String, knownBranches: List<String>, onDone: () -> Unit) -> Unit
) {
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showCredentialsSheet by rememberSaveable { mutableStateOf(false) }
    var repoPendingDeletion by remember { mutableStateOf<GitRepositoryCard?>(null) }
    val uriHandler = LocalUriHandler.current

    val gitHubCredential = remember(providerCredentials) {
        providerCredentials.firstOrNull { it.provider == GitHostProvider.GITHUB }
    }
    val hasGitHubToken = gitHubCredential?.isConfigured == true

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // ONLY the bottom-right [+] button to add a repository
            FloatingActionButton(
                onClick = {
                    onResetRepoProbe()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                HomeMinimalHeader(
                    repoCount = repositories.size,
                    hasGitHubToken = hasGitHubToken,
                    darkThemeEnabled = darkThemeEnabled,
                    onOpenCredentialsVault = { showCredentialsSheet = true },
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
                    EmptyRepositoryListView(
                        hasGitHubToken = hasGitHubToken,
                        onOpenCredentialsVault = { showCredentialsSheet = true }
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("repo_tile_list")
                    ) {
                        items(
                            items = repositories,
                            key = { repo -> repo.id }
                        ) { repo ->
                            SwipeableRepositoryTile(
                                repo = repo,
                                isSyncing = syncingRepoIds.contains(repo.id),
                                onClick = { onOpenRepository(repo.id) },
                                onOpenInBrowserClick = {
                                    runCatching { uriHandler.openUri(repo.htmlUrl) }
                                },
                                onRefreshClick = { onRefreshRepository(repo.id) },
                                onSwipeLeftToDelete = { repoPendingDeletion = repo }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Repository Confirmation Popup (triggered on Swipe Left)
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
                    text = "Remove \"${targetRepo.fullSlug}\" from your reading shelf?",
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
        ProgressiveAddRepositoryBottomSheet(
            probeState = addRepoProbeState,
            isSubmitting = isAddingRepo,
            hasGitHubToken = hasGitHubToken,
            onDismiss = {
                if (!isAddingRepo) {
                    showAddSheet = false
                    onResetRepoProbe()
                }
            },
            onOpenCredentialsVault = {
                showAddSheet = false
                showCredentialsSheet = true
            },
            onInputChanged = { onResetRepoProbe() },
            onCheckAccessibility = { rawInput ->
                onProbeRepositoryInput(rawInput)
            },
            onAddRepoConfirm = { rawRepo, selectedBranch, branches ->
                onAddRepositorySubmit(rawRepo, selectedBranch, branches) {
                    showAddSheet = false
                    onResetRepoProbe()
                }
            }
        )
    }

    if (showCredentialsSheet) {
        ProviderCredentialsBottomSheet(
            credentials = providerCredentials,
            onDismiss = { showCredentialsSheet = false },
            onSaveToken = { provider, token ->
                onSaveProviderToken(provider, token)
            },
            onClearToken = { provider ->
                onClearProviderToken(provider)
            }
        )
    }
}

@Composable
private fun HomeMinimalHeader(
    repoCount: Int,
    hasGitHubToken: Boolean,
    darkThemeEnabled: Boolean,
    onOpenCredentialsVault: () -> Unit,
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onOpenCredentialsVault,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (hasGitHubToken) CyberMint.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.testTag("provider_credentials_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (hasGitHubToken) CyberMint else MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Git OAuth & Tokens",
                        tint = if (hasGitHubToken) CyberMint else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (hasGitHubToken) "OAuth" else "Keys",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

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
}

/**
 * Full-width Repository Tile wrapped in a Swipe-Left-to-Delete gesture box
 * with a dynamic crimson reveal effect and confirmation dialog trigger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRepositoryTile(
    repo: GitRepositoryCard,
    isSyncing: Boolean,
    onClick: () -> Unit,
    onOpenInBrowserClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSwipeLeftToDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { targetValue ->
            if (targetValue == SwipeToDismissBoxValue.EndToStart) {
                onSwipeLeftToDelete()
                // Return false so the tile snaps back smoothly while the confirmation dialog is shown
                false
            } else {
                false
            }
        }
    )

    val isSwipingLeft = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart ||
        dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart

    val iconScale by animateFloatAsState(
        targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 1.28f else 0.92f,
        animationSpec = spring(dampingRatio = 0.55f),
        label = "swipe_delete_icon_scale"
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                CoralRose.copy(alpha = if (isSwipingLeft) 0.32f else 0.12f),
                                CoralRose.copy(alpha = if (isSwipingLeft) 0.85f else 0.35f)
                            )
                        )
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF14161B)
                    )
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.remove_repository),
                        tint = Color(0xFF14161B),
                        modifier = Modifier
                            .size(24.dp)
                            .scale(iconScale)
                    )
                }
            }
        }
    ) {
        RepositoryTileCard(
            repo = repo,
            isSyncing = isSyncing,
            onClick = onClick,
            onOpenInBrowserClick = onOpenInBrowserClick,
            onRefreshClick = onRefreshClick
        )
    }
}

@Composable
private fun RepositoryTileCard(
    repo: GitRepositoryCard,
    isSyncing: Boolean,
    onClick: () -> Unit,
    onOpenInBrowserClick: () -> Unit,
    onRefreshClick: () -> Unit
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
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("repo_tile_${repo.id}")
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val glowRadius = size.minDimension * 0.9f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.92f, size.height * 0.15f),
                        radius = glowRadius
                    ),
                    center = Offset(size.width * 0.92f, size.height * 0.15f),
                    radius = glowRadius
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Top Row: Owner + Browser & Refresh actions
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
                                .size(9.dp)
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
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "· Swipe left to remove",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            maxLines = 1
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
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
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

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Row: Active Branch Pill, File count, Synced time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = accentColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccountTree,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
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
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (repo.isPendingPrivateSync) {
                                        "URL SAVED"
                                    } else {
                                        "${repo.totalFilesCount} files (${repo.markdownFilesCount} .md)"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = syncedTimeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRepositoryListView(
    hasGitHubToken: Boolean,
    onOpenCredentialsVault: () -> Unit
) {
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
                text = "Tap the + button in the bottom-right corner—or share any GitHub repository URL from another app—to start reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            OutlinedButton(
                onClick = onOpenCredentialsVault,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasGitHubToken) "GitHub OAuth Token Configured" else "Configure GitHub OAuth Token"
                )
            }
        }
    }
}

/**
 * Progressive Repository Addition Bottom Sheet:
 * 1. Enter repository link / owner/repo -> Check accessibility (public or via central OAuth).
 * 2. Once verified, reveals the repository's branches with the default branch pre-selected
 *    and the "Add Repo" button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProgressiveAddRepositoryBottomSheet(
    probeState: AddRepoProbeUiState,
    isSubmitting: Boolean,
    hasGitHubToken: Boolean,
    onDismiss: () -> Unit,
    onOpenCredentialsVault: () -> Unit,
    onInputChanged: () -> Unit,
    onCheckAccessibility: (String) -> Unit,
    onAddRepoConfirm: (rawRepo: String, selectedBranch: String, knownBranches: List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var repoInput by rememberSaveable { mutableStateOf("") }
    var selectedBranch by rememberSaveable { mutableStateOf("") }
    var branchSearchFilter by rememberSaveable { mutableStateOf("") }

    val verifiedProbe: RepoAccessibilityProbe? =
        (probeState as? AddRepoProbeUiState.Verified)?.probe

    // Automatically pre-select the repository's default branch as soon as verification succeeds
    androidx.compose.runtime.LaunchedEffect(verifiedProbe) {
        if (verifiedProbe != null) {
            selectedBranch = verifiedProbe.defaultBranch
            branchSearchFilter = ""
        }
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
                Text(
                    text = "Add Repository",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    onClick = onOpenCredentialsVault,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (hasGitHubToken) CyberMint.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (hasGitHubToken) CyberMint else MaterialTheme.colorScheme.outline)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (hasGitHubToken) "OAuth Active" else "Set OAuth",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Step 1: Enter a GitHub repository link to verify access and load branches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = repoInput,
                onValueChange = { newValue ->
                    repoInput = newValue
                    if (probeState !is AddRepoProbeUiState.Idle) {
                        onInputChanged()
                    }
                },
                label = { Text("GitHub Repository Link or owner/repo") },
                placeholder = { Text("https://github.com/owner/repo") },
                singleLine = true,
                enabled = !isSubmitting && probeState !is AddRepoProbeUiState.Checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("repo_url_input")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Step 1 Action / Error / Checking State (shown before verification completes)
            if (verifiedProbe == null) {
                if (probeState is AddRepoProbeUiState.Error) {
                    Surface(
                        color = CoralRose.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CoralRose.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = probeState.message,
                                style = MaterialTheme.typography.labelLarge,
                                color = CoralRose
                            )
                            if (!hasGitHubToken) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = onOpenCredentialsVault) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Configure GitHub OAuth Token for Private Repos")
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Button(
                    onClick = { onCheckAccessibility(repoInput) },
                    enabled = repoInput.isNotBlank() && probeState !is AddRepoProbeUiState.Checking,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("check_repo_accessibility_button")
                ) {
                    if (probeState is AddRepoProbeUiState.Checking) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Checking Access & Branches…",
                            fontFamily = JetBrainsMonoFontFamily
                        )
                    } else {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Check Repository",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Step 2: Progressively revealed once Repository is Verified!
            AnimatedVisibility(
                visible = verifiedProbe != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                verifiedProbe?.let { probe ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Verified Access Summary Banner
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CyberMint.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, CyberMint.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CyberMint,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = probe.fullSlug,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (probe.isPrivate) {
                                            "Private repository · Accessible via OAuth"
                                        } else if (probe.authenticatedViaOAuth) {
                                            "Public repository · Authenticated via OAuth"
                                        } else {
                                            "Public repository · Accessible"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberMint
                                    )
                                }
                                Icon(
                                    imageVector = if (probe.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = CyberMint,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "SELECT BRANCH (${probe.branches.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Default: ${probe.defaultBranch}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (probe.branches.size > 8) {
                            OutlinedTextField(
                                value = branchSearchFilter,
                                onValueChange = { branchSearchFilter = it },
                                placeholder = { Text("Filter branches…") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )
                        }

                        val filteredBranches = remember(probe.branches, branchSearchFilter) {
                            val q = branchSearchFilter.trim().lowercase()
                            if (q.isEmpty()) probe.branches
                            else probe.branches.filter { it.lowercase().contains(q) }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            filteredBranches.take(18).forEach { branchName ->
                                val isSelected = branchName == selectedBranch
                                val isDefault = branchName == probe.defaultBranch
                                Surface(
                                    onClick = { selectedBranch = branchName },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    border = BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    ),
                                    modifier = Modifier.testTag("branch_option_$branchName")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) {
                                                Icons.Default.Check
                                            } else {
                                                Icons.Outlined.AccountTree
                                            },
                                            contentDescription = null,
                                            tint = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isDefault) "$branchName (default)" else branchName,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                onAddRepoConfirm(
                                    probe.fullSlug,
                                    selectedBranch.ifBlank { probe.defaultBranch },
                                    probe.branches
                                )
                            },
                            enabled = !isSubmitting,
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
                                Text(
                                    text = "Indexing $selectedBranch…",
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            } else {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Add Repo (${selectedBranch.ifBlank { probe.defaultBranch }})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Single Source of Truth Credentials Vault Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCredentialsBottomSheet(
    credentials: List<ProviderCredential>,
    onDismiss: () -> Unit,
    onSaveToken: (GitHostProvider, String) -> Unit,
    onClearToken: (GitHostProvider) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gitHubCred = remember(credentials) {
        credentials.firstOrNull { it.provider == GitHostProvider.GITHUB }
    }
    var gitHubTokenInput by rememberSaveable(gitHubCred?.accessToken) {
        mutableStateOf(gitHubCred?.accessToken.orEmpty())
    }
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
                text = "Git Providers & OAuth",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Single source of truth for your Git hosting credentials. Configure once—every public and private repository uses it automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (gitHubCred?.isConfigured == true) {
                        CyberMint.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = GitHostProvider.GITHUB.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = GitHostProvider.GITHUB.protocolSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (gitHubCred?.isConfigured == true) {
                            Surface(
                                color = CyberMint.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = CyberMint,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberMint
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = gitHubTokenInput,
                        onValueChange = { gitHubTokenInput = it },
                        label = { Text("GitHub OAuth / Personal Access Token") },
                        placeholder = { Text(GitHostProvider.GITHUB.tokenPlaceholder) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Key, contentDescription = null)
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
                            .testTag("github_global_token_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (gitHubCred?.isConfigured == true) {
                            OutlinedButton(
                                onClick = {
                                    gitHubTokenInput = ""
                                    onClearToken(GitHostProvider.GITHUB)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Clear")
                            }
                        }

                        Button(
                            onClick = {
                                onSaveToken(GitHostProvider.GITHUB, gitHubTokenInput)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .testTag("save_github_token_button")
                        ) {
                            Text("Save GitHub Token", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "EXTENSIBLE PROVIDER SLOTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            GitHostProvider.entries
                .filter { !it.isEnabledInCurrentRelease }
                .forEach { futureProvider ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "${futureProvider.displayName} (${futureProvider.baseDomain})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = futureProvider.protocolSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "SOON",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
