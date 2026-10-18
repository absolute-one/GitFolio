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
            extractSharedGitPayload(intent)?.let { sharedText ->
                viewModel.handleIncomingSharedGitUrl(sharedText)
            }
        }

        setContent {
            val darkTheme by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = darkTheme) {
                GitFolioRootApp(
                    viewModel = viewModel,
                    onCloseAppToSource = {
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedGitPayload(intent)?.let { sharedText ->
            viewModel.handleIncomingSharedGitUrl(sharedText)
        }
    }

    private fun extractSharedGitPayload(incomingIntent: Intent?): String? {
        if (incomingIntent == null) return null
        return when (incomingIntent.action) {
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
    }
}

@Composable
fun GitFolioRootApp(
    viewModel: GitFolioViewModel,
    onCloseAppToSource: () -> Unit = {}
) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val repositories by viewModel.repositories.collectAsStateWithLifecycle()
    val syncingRepoIds by viewModel.syncingRepoIds.collectAsStateWithLifecycle()
    val isAddingRepo by viewModel.isAddingRepo.collectAsStateWithLifecycle()
    val feedbackMessage by viewModel.homeFeedbackMessage.collectAsStateWithLifecycle()
    val prefilledRepoUrl by viewModel.prefilledRepoUrlForAddSheet.collectAsStateWithLifecycle()
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
                        syncingRepoIds = syncingRepoIds,
                        isAddingRepo = isAddingRepo,
                        feedbackMessage = feedbackMessage,
                        prefilledRepoUrlForAddSheet = prefilledRepoUrl,
                        darkThemeEnabled = darkThemeEnabled,
                        onToggleTheme = viewModel::toggleTheme,
                        onDismissFeedback = viewModel::clearHomeFeedback,
                        onConsumePrefilledRepoUrl = viewModel::consumePrefilledRepoUrl,
                        onOpenRepository = viewModel::openRepositoryExplorer,
                        onRefreshRepository = viewModel::refreshRepositoryOnTile,
                        onConfirmRemoveRepository = viewModel::removeRepository,
                        onAddRepositorySubmit = viewModel::addRepository
                    )
                }

                is ScreenDestination.RepoExplorer -> {
                    RepoExplorerScreen(
                        state = explorerState,
                        onBack = viewModel::navigateBack,
                        onRefreshTree = viewModel::refreshCurrentExplorerTree,
                        onToggleFolder = viewModel::toggleFolderExpanded,
                        onExpandAll = viewModel::expandAllFolders,
                        onCollapseAll = viewModel::collapseAllFolders,
                        onSearchQueryChange = viewModel::updateExplorerSearch,
                        onToggleMarkdownFilter = viewModel::toggleMarkdownOnlyFilter,
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
                viewModel.dismissSharedOverlay()
                onCloseAppToSource()
            },
            onReadRepositoryNow = { mountedRepo ->
                viewModel.openSharedRepositoryImmediately(mountedRepo)
            },
            onStayOnShelf = {
                viewModel.dismissSharedOverlay()
            },
            onRetryWithToken = { rawUrl ->
                viewModel.retrySharedRepoWithToken(rawUrl)
            }
        )
    }
}
