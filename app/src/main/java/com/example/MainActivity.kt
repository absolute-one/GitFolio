package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frontend.KeystoneInspectorSheet
import com.example.ui.GitFolioViewModel
import com.example.ui.ScreenDestination
import com.example.ui.explorer.RepoExplorerScreen
import com.example.ui.home.HomeBentoScreen
import com.example.ui.reader.MarkdownReaderScreen
import com.example.ui.share.SharedRepoAcknowledgementOverlay
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: GitFolioViewModel by viewModels {
        GitFolioViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            consumeIncomingShareIntent(intent)
        }

        setContent {
            val darkTheme by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = darkTheme) {
                GitFolioRootApp(
                    viewModel = viewModel,
                    onCloseAppToSource = {
                        viewModel.dismissSharedOverlay()
                        moveTaskToBack(true)
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIncomingShareIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        viewModel.dismissSharedOverlay()
    }

    private fun consumeIncomingShareIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        if ((incomingIntent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return
        }

        val sharedPayload = when (incomingIntent.action) {
            Intent.ACTION_SEND -> {
                if (incomingIntent.type?.startsWith("text/") == true) {
                    incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
                } else {
                    null
                }
            }
            Intent.ACTION_VIEW -> {
                incomingIntent.dataString
            }
            else -> null
        }

        incomingIntent.removeExtra(Intent.EXTRA_TEXT)
        incomingIntent.data = null
        incomingIntent.action = Intent.ACTION_MAIN
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })

        if (!sharedPayload.isNullOrBlank()) {
            viewModel.handleIncomingSharedGitUrl(sharedPayload)
        }
    }
}

/**
 * Keystone Architecture — Crown Binder (Section 5):
 * Uses fine-grained state scoping so each screen destination and overlay only collects
 * the StateFlows it actively renders, preventing cross-screen recomposition storms.
 */
@Composable
fun GitFolioRootApp(
    viewModel: GitFolioViewModel,
    onCloseAppToSource: () -> Unit = {}
) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = currentScreen,
            animationSpec = tween(durationMillis = 180),
            label = "screen_navigation"
        ) { destination ->
            when (destination) {
                ScreenDestination.HomeBento -> {
                    HomeDestinationHost(viewModel = viewModel)
                }

                is ScreenDestination.RepoExplorer -> {
                    ExplorerDestinationHost(viewModel = viewModel)
                }

                is ScreenDestination.MarkdownReader -> {
                    ReaderDestinationHost(viewModel = viewModel)
                }
            }
        }

        KeystoneStatusPill(viewModel = viewModel)

        KeystoneInspectorHost(viewModel = viewModel)

        SharedRepoOverlayHost(
            viewModel = viewModel,
            onCloseAppToSource = onCloseAppToSource
        )
    }
}

@Composable
private fun HomeDestinationHost(viewModel: GitFolioViewModel) {
    val repositories by viewModel.repositories.collectAsStateWithLifecycle()
    val providerCredentials by viewModel.providerCredentials.collectAsStateWithLifecycle()
    val syncingRepoIds by viewModel.syncingRepoIds.collectAsStateWithLifecycle()
    val addRepoProbeState by viewModel.addRepoProbeState.collectAsStateWithLifecycle()
    val isAddingRepo by viewModel.isAddingRepo.collectAsStateWithLifecycle()
    val feedbackMessage by viewModel.homeFeedbackMessage.collectAsStateWithLifecycle()
    val darkThemeEnabled by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()

    HomeBentoScreen(
        repositories = repositories,
        providerCredentials = providerCredentials,
        syncingRepoIds = syncingRepoIds,
        addRepoProbeState = addRepoProbeState,
        isAddingRepo = isAddingRepo,
        feedbackMessage = feedbackMessage,
        darkThemeEnabled = darkThemeEnabled,
        onToggleTheme = viewModel::toggleTheme,
        onDismissFeedback = viewModel::clearHomeFeedback,
        onOpenRepository = viewModel::openRepositoryExplorer,
        onRefreshRepository = viewModel::refreshRepositoryOnTile,
        onConfirmRemoveRepository = viewModel::removeRepository,
        onSaveProviderToken = viewModel::saveProviderToken,
        onClearProviderToken = viewModel::clearProviderToken,
        onProbeRepositoryInput = viewModel::probeRepositoryInput,
        onResetRepoProbe = viewModel::resetRepoProbe,
        onAddRepositorySubmit = viewModel::addRepository
    )
}

@Composable
private fun ExplorerDestinationHost(viewModel: GitFolioViewModel) {
    val explorerState by viewModel.explorerState.collectAsStateWithLifecycle()

    RepoExplorerScreen(
        state = explorerState,
        onBack = viewModel::navigateBack,
        onSelectViewMode = viewModel::selectExplorerViewMode,
        onRefreshTree = viewModel::refreshCurrentExplorerTree,
        onRequestBranchListRefresh = viewModel::refreshCurrentExplorerBranches,
        onSwitchBranch = viewModel::switchCurrentExplorerBranch,
        onToggleFolder = viewModel::toggleFolderExpanded,
        onExpandAll = viewModel::expandAllFolders,
        onCollapseAll = viewModel::collapseAllFolders,
        onSearchQueryChange = viewModel::updateExplorerSearch,
        onToggleMarkdownFilter = viewModel::toggleMarkdownOnlyFilter,
        onCommitSearchQueryChange = viewModel::updateCommitGraphSearch,
        onSelectCommitBranchFilter = viewModel::selectCommitGraphBranchFilter,
        onToggleHighScaleGraphMode = viewModel::toggleHighScaleCommitGraphMode,
        onOpenFile = viewModel::openFileInMarkdownReader
    )
}

@Composable
private fun ReaderDestinationHost(viewModel: GitFolioViewModel) {
    val readerState by viewModel.readerState.collectAsStateWithLifecycle()
    val readerFontScale by viewModel.readerFontScale.collectAsStateWithLifecycle()
    val readerUseSerifBody by viewModel.readerUseSerifBody.collectAsStateWithLifecycle()

    MarkdownReaderScreen(
        loadState = readerState,
        fontScale = readerFontScale,
        useSerifBody = readerUseSerifBody,
        onBack = viewModel::navigateBack,
        onAdjustFontScale = viewModel::adjustReaderFontScale,
        onToggleBodyFont = viewModel::toggleReaderBodyFont
    )
}

@Composable
private fun BoxScope.KeystoneStatusPill(viewModel: GitFolioViewModel) {
    val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val traceLog by viewModel.traceLog.collectAsStateWithLifecycle()
    val hasPending = pendingApprovals.isNotEmpty()
    val hopCount = traceLog.size

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (hasPending) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
        },
        border = BorderStroke(
            1.dp,
            if (hasPending) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outline
            }
        ),
        shadowElevation = 6.dp,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(start = 16.dp, bottom = 16.dp)
            .clickable(onClick = viewModel::toggleKeystoneInspector)
            .testTag("open_keystone_inspector_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = "Open Keystone Architecture Inspector",
                tint = if (hasPending) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = if (hasPending) {
                    "KEYSTONE · ${pendingApprovals.size} APPROVAL"
                } else {
                    "KEYSTONE · $hopCount HOP${if (hopCount == 1) "" else "S"}"
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasPending) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun KeystoneInspectorHost(viewModel: GitFolioViewModel) {
    val isKeystoneInspectorOpen by viewModel.isKeystoneInspectorOpen.collectAsStateWithLifecycle()
    if (!isKeystoneInspectorOpen) return

    val auditReport by viewModel.auditReport.collectAsStateWithLifecycle()
    val traceLog by viewModel.traceLog.collectAsStateWithLifecycle()
    val registeredCommands by viewModel.registeredCommands.collectAsStateWithLifecycle()
    val journaledOperations by viewModel.journaledOperations.collectAsStateWithLifecycle()
    val auditLog by viewModel.auditLog.collectAsStateWithLifecycle()
    val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val installedExtensions by viewModel.installedExtensions.collectAsStateWithLifecycle()
    val mountedContributions by viewModel.mountedContributions.collectAsStateWithLifecycle()

    KeystoneInspectorSheet(
        auditReport = auditReport,
        traceLog = traceLog,
        commands = registeredCommands,
        journaledOperations = journaledOperations,
        auditLog = auditLog,
        pendingApprovals = pendingApprovals,
        activeProfile = activeProfile,
        extensions = installedExtensions,
        mountedContributions = mountedContributions,
        onRefreshAudit = viewModel::refreshArchitecturalAudit,
        onExecuteCommand = viewModel::executeCommandFromPalette,
        onApproveOperation = viewModel::approvePendingOperation,
        onRejectOperation = viewModel::rejectPendingOperation,
        onUndoLastMutation = viewModel::undoLastMutation,
        onRebuildDerivedCaches = viewModel::rebuildDerivedCaches,
        onRunAutomatedSyncActor = viewModel::triggerAutomatedSyncActor,
        onSwitchProfile = viewModel::switchProfile,
        onToggleExtension = viewModel::toggleExtension,
        onDismiss = viewModel::closeKeystoneInspector
    )
}

@Composable
private fun SharedRepoOverlayHost(
    viewModel: GitFolioViewModel,
    onCloseAppToSource: () -> Unit
) {
    val sharedOverlayState by viewModel.sharedOverlayState.collectAsStateWithLifecycle()

    SharedRepoAcknowledgementOverlay(
        state = sharedOverlayState,
        onCloseAppImmediately = onCloseAppToSource,
        onReadRepositoryNow = { mountedRepo ->
            viewModel.openSharedRepositoryImmediately(mountedRepo)
        },
        onStayOnShelf = viewModel::dismissSharedOverlay
    )
}
