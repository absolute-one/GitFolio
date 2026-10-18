package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.GitFolioViewModel
import com.example.ui.ScreenDestination
import com.example.ui.explorer.RepoExplorerScreen
import com.example.ui.home.HomeBentoScreen
import com.example.ui.reader.MarkdownReaderScreen
import com.example.ui.share.SharedRepoAcknowledgementOverlay
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

@Composable
fun GitFolioRootApp(
    viewModel: GitFolioViewModel,
    onCloseAppToSource: () -> Unit = {}
) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val repositories by viewModel.repositories.collectAsStateWithLifecycle()
    val providerCredentials by viewModel.providerCredentials.collectAsStateWithLifecycle()
    val syncingRepoIds by viewModel.syncingRepoIds.collectAsStateWithLifecycle()
    val addRepoProbeState by viewModel.addRepoProbeState.collectAsStateWithLifecycle()
    val isAddingRepo by viewModel.isAddingRepo.collectAsStateWithLifecycle()
    val feedbackMessage by viewModel.homeFeedbackMessage.collectAsStateWithLifecycle()
    val sharedOverlayState by viewModel.sharedOverlayState.collectAsStateWithLifecycle()
    val explorerState by viewModel.explorerState.collectAsStateWithLifecycle()
    val readerState by viewModel.readerState.collectAsStateWithLifecycle()
    val darkThemeEnabled by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()
    val readerFontScale by viewModel.readerFontScale.collectAsStateWithLifecycle()
    val readerUseSerifBody by viewModel.readerUseSerifBody.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = currentScreen,
            animationSpec = tween(durationMillis = 220),
            label = "screen_navigation"
        ) { destination ->
            when (destination) {
                ScreenDestination.HomeBento -> {
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

                is ScreenDestination.RepoExplorer -> {
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

                is ScreenDestination.MarkdownReader -> {
                    MarkdownReaderScreen(
                        loadState = readerState,
                        fontScale = readerFontScale,
                        useSerifBody = readerUseSerifBody,
                        onBack = viewModel::navigateBack,
                        onAdjustFontScale = viewModel::adjustReaderFontScale,
                        onToggleBodyFont = viewModel::toggleReaderBodyFont
                    )
                }
            }
        }

        // 3rd-Party Share Acknowledgement Overlay (Animated tick + 3s visual portal auto-close)
        SharedRepoAcknowledgementOverlay(
            state = sharedOverlayState,
            onCloseAppImmediately = {
                onCloseAppToSource()
            },
            onReadRepositoryNow = { mountedRepo ->
                viewModel.openSharedRepositoryImmediately(mountedRepo)
            },
            onStayOnShelf = {
                viewModel.dismissSharedOverlay()
            }
        )
    }
}
