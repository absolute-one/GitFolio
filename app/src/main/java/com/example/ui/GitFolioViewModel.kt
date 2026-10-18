package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.engine.CommonMarkDocumentEngine
import com.example.data.local.GitFolioDatabase
import com.example.data.remote.GitHubRestClient
import com.example.data.repository.GitHubRepositoryGatewayImpl
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.RepoFileNode
import com.example.domain.usecase.AddGitHubRepositoryUseCase
import com.example.domain.usecase.ComputeVisibleTreeNodesUseCase
import com.example.domain.usecase.ObserveRepositoriesUseCase
import com.example.domain.usecase.ObserveRepositoryTreeUseCase
import com.example.domain.usecase.OpenDocumentAsMarkdownUseCase
import com.example.domain.usecase.RefreshRepositoryUseCase
import com.example.domain.usecase.RemoveRepositoryUseCase
import com.example.ui.share.SharedRepoOverlayState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ScreenDestination {
    data object HomeBento : ScreenDestination
    data class RepoExplorer(val repoId: String) : ScreenDestination
    data class MarkdownReader(val repoId: String, val filePath: String) : ScreenDestination
}

sealed interface ReaderLoadState {
    data object Loading : ReaderLoadState
    data class Ready(val document: ParsedMarkdownDocument) : ReaderLoadState
    data class Error(val message: String) : ReaderLoadState
}

data class ExplorerUiState(
    val repository: GitRepositoryCard? = null,
    val allNodes: List<RepoFileNode> = emptyList(),
    val visibleNodes: List<RepoFileNode> = emptyList(),
    val expandedFolderPaths: Set<String> = emptySet(),
    val searchQuery: String = "",
    val markdownOnlyFilter: Boolean = false,
    val isRefreshingTree: Boolean = false,
    val statusBanner: String? = null
)

@OptIn(FlowPreview::class)
class GitFolioViewModel(
    observeRepositoriesUseCase: ObserveRepositoriesUseCase,
    private val addGitHubRepositoryUseCase: AddGitHubRepositoryUseCase,
    private val refreshRepositoryUseCase: RefreshRepositoryUseCase,
    private val removeRepositoryUseCase: RemoveRepositoryUseCase,
    private val observeRepositoryTreeUseCase: ObserveRepositoryTreeUseCase,
    private val openDocumentAsMarkdownUseCase: OpenDocumentAsMarkdownUseCase,
    private val computeVisibleTreeNodesUseCase: ComputeVisibleTreeNodesUseCase
) : ViewModel() {

    val repositories: StateFlow<List<GitRepositoryCard>> = observeRepositoriesUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _currentScreen = MutableStateFlow<ScreenDestination>(ScreenDestination.HomeBento)
    val currentScreen: StateFlow<ScreenDestination> = _currentScreen.asStateFlow()

    private val _syncingRepoIds = MutableStateFlow<Set<String>>(emptySet())
    val syncingRepoIds: StateFlow<Set<String>> = _syncingRepoIds.asStateFlow()

    private val _isAddingRepo = MutableStateFlow(false)
    val isAddingRepo: StateFlow<Boolean> = _isAddingRepo.asStateFlow()

    private val _homeFeedbackMessage = MutableStateFlow<String?>(null)
    val homeFeedbackMessage: StateFlow<String?> = _homeFeedbackMessage.asStateFlow()

    private val _prefilledRepoUrlForAddSheet = MutableStateFlow<String?>(null)
    val prefilledRepoUrlForAddSheet: StateFlow<String?> = _prefilledRepoUrlForAddSheet.asStateFlow()

    private val _sharedOverlayState = MutableStateFlow<SharedRepoOverlayState>(SharedRepoOverlayState.Hidden)
    val sharedOverlayState: StateFlow<SharedRepoOverlayState> = _sharedOverlayState.asStateFlow()

    private val _explorerState = MutableStateFlow(ExplorerUiState())
    val explorerState: StateFlow<ExplorerUiState> = _explorerState.asStateFlow()

    private val _readerState = MutableStateFlow<ReaderLoadState>(ReaderLoadState.Loading)
    val readerState: StateFlow<ReaderLoadState> = _readerState.asStateFlow()

    private val _darkThemeEnabled = MutableStateFlow(true)
    val darkThemeEnabled: StateFlow<Boolean> = _darkThemeEnabled.asStateFlow()

    private val _readerFontScale = MutableStateFlow(1.0f)
    val readerFontScale: StateFlow<Float> = _readerFontScale.asStateFlow()

    private val _readerUseSerifBody = MutableStateFlow(true)
    val readerUseSerifBody: StateFlow<Boolean> = _readerUseSerifBody.asStateFlow()

    private var treeObserveJob: Job? = null

    init {
        // Reactive background search & tree visibility pipeline
        viewModelScope.launch {
            val debouncedQueryFlow = _explorerState
                .map { it.searchQuery }
                .distinctUntilChanged()
                .debounce { query -> if (query.isBlank()) 0L else 60L }

            val treeStateInputsFlow = _explorerState
                .map { Triple(it.allNodes, it.expandedFolderPaths, it.markdownOnlyFilter) }
                .distinctUntilChanged()

            combine(treeStateInputsFlow, debouncedQueryFlow) { (nodes, expanded, mdOnly), query ->
                computeVisibleTreeNodesUseCase(
                    allNodes = nodes,
                    expandedFolders = expanded,
                    rawQuery = query,
                    markdownOnly = mdOnly
                )
            }.collectLatest { computedVisible ->
                _explorerState.update { it.copy(visibleNodes = computedVisible) }
            }
        }
    }

    fun toggleTheme() {
        _darkThemeEnabled.update { !it }
    }

    fun adjustReaderFontScale(delta: Float) {
        _readerFontScale.update { (it + delta).coerceIn(0.8f, 1.45f) }
    }

    fun toggleReaderBodyFont() {
        _readerUseSerifBody.update { !it }
    }

    fun clearHomeFeedback() {
        _homeFeedbackMessage.value = null
    }

    fun consumePrefilledRepoUrl() {
        _prefilledRepoUrlForAddSheet.value = null
    }

    fun handleIncomingSharedGitUrl(rawSharedText: String) {
        val trimmed = rawSharedText.trim()
        if (trimmed.isBlank()) return

        _sharedOverlayState.value = SharedRepoOverlayState.Mounting(trimmed)
        viewModelScope.launch {
            val result = addGitHubRepositoryUseCase(
                rawRepoInput = trimmed,
                branchOverride = null,
                repoSpecificToken = ""
            )
            result.onSuccess { card ->
                _sharedOverlayState.value = SharedRepoOverlayState.MountedSuccess(card)
            }.onFailure { err ->
                _sharedOverlayState.value = SharedRepoOverlayState.MountError(
                    rawSharedUrl = trimmed,
                    message = err.message ?: "Unable to mount shared GitHub URL."
                )
            }
        }
    }

    fun dismissSharedOverlay() {
        _sharedOverlayState.value = SharedRepoOverlayState.Hidden
    }

    fun openSharedRepositoryImmediately(card: GitRepositoryCard) {
        _sharedOverlayState.value = SharedRepoOverlayState.Hidden
        openRepositoryExplorer(card.id)
    }

    fun retrySharedRepoWithToken(rawSharedUrl: String) {
        _sharedOverlayState.value = SharedRepoOverlayState.Hidden
        _currentScreen.value = ScreenDestination.HomeBento
        _prefilledRepoUrlForAddSheet.value = rawSharedUrl
    }

    fun addRepository(
        rawInput: String,
        branchOverride: String?,
        repoSpecificToken: String,
        onSuccess: () -> Unit
    ) {
        if (_isAddingRepo.value) return
        _isAddingRepo.value = true
        _homeFeedbackMessage.value = null

        viewModelScope.launch {
            val result = addGitHubRepositoryUseCase(
                rawRepoInput = rawInput,
                branchOverride = branchOverride,
                repoSpecificToken = repoSpecificToken
            )
            _isAddingRepo.value = false
            result.onSuccess { card ->
                _homeFeedbackMessage.value = "Added ${card.fullSlug} (${card.totalFilesCount} files indexed)"
                onSuccess()
            }.onFailure { err ->
                _homeFeedbackMessage.value = err.message ?: "Failed to add repository"
            }
        }
    }

    fun refreshRepositoryOnTile(repoId: String) {
        if (_syncingRepoIds.value.contains(repoId)) return
        _syncingRepoIds.update { it + repoId }

        viewModelScope.launch {
            val result = refreshRepositoryUseCase(repoId)
            _syncingRepoIds.update { it - repoId }
            result.onSuccess { updated ->
                _homeFeedbackMessage.value = "Synced ${updated.fullSlug} (${updated.totalFilesCount} files)"
            }.onFailure { err ->
                _homeFeedbackMessage.value = "Sync failed: ${err.message ?: "Check connection or token"}"
            }
        }
    }

    fun removeRepository(repoId: String) {
        viewModelScope.launch {
            removeRepositoryUseCase(repoId)
            _homeFeedbackMessage.value = "Repository removed"
        }
    }

    fun openRepositoryExplorer(repoId: String) {
        treeObserveJob?.cancel()
        _currentScreen.value = ScreenDestination.RepoExplorer(repoId)
        _explorerState.value = ExplorerUiState(isRefreshingTree = true)

        treeObserveJob = viewModelScope.launch {
            val repo = observeRepositoryTreeUseCase.getRepository(repoId)
            _explorerState.update { it.copy(repository = repo) }

            launch {
                observeRepositoryTreeUseCase.observe(repoId).collect { nodes ->
                    val topFolders = nodes.filter { it.isDirectory && it.depth == 0 }
                        .map { it.path }
                        .toSet()
                    _explorerState.update { current ->
                        val expanded = if (current.expandedFolderPaths.isEmpty()) {
                            topFolders
                        } else {
                            current.expandedFolderPaths
                        }
                        current.copy(
                            allNodes = nodes,
                            expandedFolderPaths = expanded,
                            isRefreshingTree = false
                        )
                    }
                }
            }

            observeRepositoryTreeUseCase.refreshTree(repoId, forceNetwork = false)
            _explorerState.update { it.copy(isRefreshingTree = false) }
        }
    }

    fun refreshCurrentExplorerTree() {
        val repoId = _explorerState.value.repository?.id ?: return
        _explorerState.update { it.copy(isRefreshingTree = true, statusBanner = "Fetching latest Git tree from GitHub…") }
        viewModelScope.launch {
            val res = observeRepositoryTreeUseCase.refreshTree(repoId, forceNetwork = true)
            val refreshedRepo = observeRepositoryTreeUseCase.getRepository(repoId)
            _explorerState.update {
                it.copy(
                    repository = refreshedRepo ?: it.repository,
                    isRefreshingTree = false,
                    statusBanner = if (res.isSuccess) "Synced file tree with GitHub" else "Could not refresh tree"
                )
            }
        }
    }

    fun toggleFolderExpanded(folderPath: String) {
        _explorerState.update { current ->
            val updated = if (current.expandedFolderPaths.contains(folderPath)) {
                current.expandedFolderPaths - folderPath
            } else {
                current.expandedFolderPaths + folderPath
            }
            current.copy(expandedFolderPaths = updated)
        }
    }

    fun expandAllFolders() {
        _explorerState.update { current ->
            val allDirs = current.allNodes.filter { it.isDirectory }.map { it.path }.toSet()
            current.copy(expandedFolderPaths = allDirs)
        }
    }

    fun collapseAllFolders() {
        _explorerState.update { current ->
            current.copy(expandedFolderPaths = emptySet())
        }
    }

    fun updateExplorerSearch(query: String) {
        _explorerState.update { it.copy(searchQuery = query) }
    }

    fun toggleMarkdownOnlyFilter() {
        _explorerState.update { it.copy(markdownOnlyFilter = !it.markdownOnlyFilter) }
    }

    fun openFileInMarkdownReader(node: RepoFileNode) {
        if (node.isDirectory) {
            toggleFolderExpanded(node.path)
            return
        }
        val repoId = node.repoId
        _currentScreen.value = ScreenDestination.MarkdownReader(repoId = repoId, filePath = node.path)
        _readerState.value = ReaderLoadState.Loading

        viewModelScope.launch {
            val result = openDocumentAsMarkdownUseCase(
                repoId = repoId,
                node = node,
                forceRefresh = false
            )
            result.onSuccess { parsedDoc ->
                _readerState.value = ReaderLoadState.Ready(parsedDoc)
            }.onFailure { err ->
                _readerState.value = ReaderLoadState.Error(
                    err.message ?: "Unable to load ${node.path} from repository."
                )
            }
        }
    }

    fun navigateBack() {
        when (val screen = _currentScreen.value) {
            is ScreenDestination.MarkdownReader -> {
                _currentScreen.value = ScreenDestination.RepoExplorer(screen.repoId)
            }
            is ScreenDestination.RepoExplorer -> {
                _currentScreen.value = ScreenDestination.HomeBento
            }
            ScreenDestination.HomeBento -> Unit
        }
    }

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val database = GitFolioDatabase.getInstance(appContext)
            val restClient = GitHubRestClient()
            val gateway = GitHubRepositoryGatewayImpl(
                repositoryDao = database.repositoryDao(),
                repoTreeDao = database.repoTreeDao(),
                cachedDocumentDao = database.cachedDocumentDao(),
                gitHubClient = restClient
            )
            val engine = CommonMarkDocumentEngine()

            return GitFolioViewModel(
                observeRepositoriesUseCase = ObserveRepositoriesUseCase(gateway),
                addGitHubRepositoryUseCase = AddGitHubRepositoryUseCase(gateway),
                refreshRepositoryUseCase = RefreshRepositoryUseCase(gateway),
                removeRepositoryUseCase = RemoveRepositoryUseCase(gateway),
                observeRepositoryTreeUseCase = ObserveRepositoryTreeUseCase(gateway, gateway),
                openDocumentAsMarkdownUseCase = OpenDocumentAsMarkdownUseCase(gateway, gateway, engine),
                computeVisibleTreeNodesUseCase = ComputeVisibleTreeNodesUseCase()
            ) as T
        }
    }
}
