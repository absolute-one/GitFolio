package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.engine.CommonMarkDocumentEngine
import com.example.data.engine.GitGraphLayoutEngine
import com.example.data.local.GitFolioDatabase
import com.example.data.remote.GitHubRestClient
import com.example.data.repository.GitHubRepositoryGatewayImpl
import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitHostProvider
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.ProviderCredential
import com.example.domain.model.RepoAccessibilityProbe
import com.example.domain.model.RepoFileNode
import com.example.domain.usecase.AddGitHubRepositoryUseCase
import com.example.domain.usecase.ComputeVisibleTreeNodesUseCase
import com.example.domain.usecase.LoadRepositoryCommitGraphUseCase
import com.example.domain.usecase.ManageProviderCredentialsUseCase
import com.example.domain.usecase.ObserveRepositoriesUseCase
import com.example.domain.usecase.ObserveRepositoryTreeUseCase
import com.example.domain.usecase.OpenDocumentAsMarkdownUseCase
import com.example.domain.usecase.ProbeRepositoryAccessibilityUseCase
import com.example.domain.usecase.RefreshRepositoryUseCase
import com.example.domain.usecase.RemoveRepositoryUseCase
import com.example.domain.usecase.SwitchRepositoryBranchUseCase
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

sealed interface AddRepoProbeUiState {
    data object Idle : AddRepoProbeUiState
    data class Checking(val rawInput: String) : AddRepoProbeUiState
    data class Verified(val probe: RepoAccessibilityProbe) : AddRepoProbeUiState
    data class Error(val rawInput: String, val message: String) : AddRepoProbeUiState
}

enum class ExplorerViewMode {
    FILES_TREE,
    GIT_COMMIT_GRAPH
}

data class ExplorerUiState(
    val repository: GitRepositoryCard? = null,
    val viewMode: ExplorerViewMode = ExplorerViewMode.FILES_TREE,
    val availableBranches: List<String> = emptyList(),
    val isLoadingBranches: Boolean = false,
    val isSwitchingBranch: Boolean = false,
    val switchingToBranchName: String? = null,
    val allNodes: List<RepoFileNode> = emptyList(),
    val visibleNodes: List<RepoFileNode> = emptyList(),
    val expandedFolderPaths: Set<String> = emptySet(),
    val searchQuery: String = "",
    val markdownOnlyFilter: Boolean = false,
    val isRefreshingTree: Boolean = false,
    val commitGraph: GitRepoCommitGraph? = null,
    val filteredCommitRows: List<GitGraphCommitRow> = emptyList(),
    val isLoadingCommitGraph: Boolean = false,
    val commitSearchQuery: String = "",
    val selectedCommitBranchFilter: String? = null,
    val highScaleGraphBenchmarkMode: Boolean = false,
    val statusBanner: String? = null
)

@OptIn(FlowPreview::class)
class GitFolioViewModel(
    private val observeRepositoriesUseCase: ObserveRepositoriesUseCase,
    private val manageProviderCredentialsUseCase: ManageProviderCredentialsUseCase,
    private val probeRepositoryAccessibilityUseCase: ProbeRepositoryAccessibilityUseCase,
    private val addGitHubRepositoryUseCase: AddGitHubRepositoryUseCase,
    private val refreshRepositoryUseCase: RefreshRepositoryUseCase,
    private val switchRepositoryBranchUseCase: SwitchRepositoryBranchUseCase,
    private val removeRepositoryUseCase: RemoveRepositoryUseCase,
    private val observeRepositoryTreeUseCase: ObserveRepositoryTreeUseCase,
    private val loadRepositoryCommitGraphUseCase: LoadRepositoryCommitGraphUseCase,
    private val openDocumentAsMarkdownUseCase: OpenDocumentAsMarkdownUseCase,
    private val computeVisibleTreeNodesUseCase: ComputeVisibleTreeNodesUseCase
) : ViewModel() {

    val repositories: StateFlow<List<GitRepositoryCard>> = observeRepositoriesUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val providerCredentials: StateFlow<List<ProviderCredential>> =
        manageProviderCredentialsUseCase.observeCredentials()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = GitHostProvider.entries.map {
                    ProviderCredential(it, "", 0L)
                }
            )

    private val _currentScreen = MutableStateFlow<ScreenDestination>(ScreenDestination.HomeBento)
    val currentScreen: StateFlow<ScreenDestination> = _currentScreen.asStateFlow()

    private val _syncingRepoIds = MutableStateFlow<Set<String>>(emptySet())
    val syncingRepoIds: StateFlow<Set<String>> = _syncingRepoIds.asStateFlow()

    private val _addRepoProbeState = MutableStateFlow<AddRepoProbeUiState>(AddRepoProbeUiState.Idle)
    val addRepoProbeState: StateFlow<AddRepoProbeUiState> = _addRepoProbeState.asStateFlow()

    private val _isAddingRepo = MutableStateFlow(false)
    val isAddingRepo: StateFlow<Boolean> = _isAddingRepo.asStateFlow()

    private val _homeFeedbackMessage = MutableStateFlow<String?>(null)
    val homeFeedbackMessage: StateFlow<String?> = _homeFeedbackMessage.asStateFlow()

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
    private var commitGraphJob: Job? = null
    private var probeJob: Job? = null

    init {
        // Seed the official "git/git" repository once on first install as a welcoming gesture.
        // If the user removes it, it stays deleted and their list remains empty.
        viewModelScope.launch {
            observeRepositoriesUseCase.ensureDefaultGitSeededOnce()
        }

        // Reactive pipeline for hierarchical / fuzzy-searched file tree nodes
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

        // Reactive pipeline for filtering 1,000+ commit rows in the Git Graph view
        viewModelScope.launch {
            val debouncedCommitQueryFlow = _explorerState
                .map { it.commitSearchQuery }
                .distinctUntilChanged()
                .debounce { q -> if (q.isBlank()) 0L else 50L }

            val commitGraphInputsFlow = _explorerState
                .map { it.commitGraph?.rows.orEmpty() to it.selectedCommitBranchFilter }
                .distinctUntilChanged()

            combine(commitGraphInputsFlow, debouncedCommitQueryFlow) { (allRows, branchFilter), query ->
                loadRepositoryCommitGraphUseCase.filterCommitRows(
                    allRows = allRows,
                    rawQuery = query,
                    branchFilter = branchFilter
                )
            }.collectLatest { filtered ->
                _explorerState.update { it.copy(filteredCommitRows = filtered) }
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

    fun saveProviderToken(provider: GitHostProvider, token: String) {
        viewModelScope.launch {
            manageProviderCredentialsUseCase.saveToken(provider, token)
            _homeFeedbackMessage.value = if (token.isNotBlank()) {
                "${provider.displayName} OAuth token saved"
            } else {
                "${provider.displayName} token cleared"
            }
        }
    }

    fun clearProviderToken(provider: GitHostProvider) {
        viewModelScope.launch {
            manageProviderCredentialsUseCase.clearToken(provider)
            _homeFeedbackMessage.value = "${provider.displayName} token cleared"
        }
    }

    fun probeRepositoryInput(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        probeJob?.cancel()
        _addRepoProbeState.value = AddRepoProbeUiState.Checking(trimmed)

        probeJob = viewModelScope.launch {
            val result = probeRepositoryAccessibilityUseCase(trimmed)
            result.onSuccess { probe ->
                _addRepoProbeState.value = AddRepoProbeUiState.Verified(probe)
            }.onFailure { err ->
                _addRepoProbeState.value = AddRepoProbeUiState.Error(
                    rawInput = trimmed,
                    message = err.message ?: "Unable to verify repository access."
                )
            }
        }
    }

    fun resetRepoProbe() {
        probeJob?.cancel()
        _addRepoProbeState.value = AddRepoProbeUiState.Idle
    }

    fun addRepository(
        rawInput: String,
        selectedBranch: String,
        knownBranches: List<String>,
        onSuccess: () -> Unit
    ) {
        if (_isAddingRepo.value) return
        _isAddingRepo.value = true
        _homeFeedbackMessage.value = null

        viewModelScope.launch {
            val result = addGitHubRepositoryUseCase(
                rawRepoInput = rawInput,
                branchOverride = selectedBranch,
                knownBranches = knownBranches,
                saveUrlEvenIfPrivateOrUnreachable = false
            )
            _isAddingRepo.value = false
            result.onSuccess { card ->
                _addRepoProbeState.value = AddRepoProbeUiState.Idle
                _homeFeedbackMessage.value = "Added ${card.fullSlug} [${card.defaultBranch}] (${card.totalFilesCount} files)"
                onSuccess()
            }.onFailure { err ->
                _homeFeedbackMessage.value = err.message ?: "Failed to add repository"
            }
        }
    }

    fun handleIncomingSharedGitUrl(rawSharedText: String) {
        val trimmed = rawSharedText.trim()
        if (trimmed.isBlank()) return

        _sharedOverlayState.value = SharedRepoOverlayState.Mounting(trimmed)
        viewModelScope.launch {
            val result = addGitHubRepositoryUseCase(
                rawRepoInput = trimmed,
                branchOverride = null,
                saveUrlEvenIfPrivateOrUnreachable = true
            )
            result.onSuccess { card ->
                _sharedOverlayState.value = SharedRepoOverlayState.MountedSuccess(card)
            }.onFailure { err ->
                _sharedOverlayState.value = SharedRepoOverlayState.MountError(
                    rawSharedUrl = trimmed,
                    message = err.message ?: "Unable to parse shared GitHub URL."
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

    fun refreshRepositoryOnTile(repoId: String) {
        if (_syncingRepoIds.value.contains(repoId)) return
        _syncingRepoIds.update { it + repoId }

        viewModelScope.launch {
            val result = refreshRepositoryUseCase(repoId)
            _syncingRepoIds.update { it - repoId }
            result.onSuccess { updated ->
                _homeFeedbackMessage.value = "Synced ${updated.fullSlug} [${updated.defaultBranch}] (${updated.totalFilesCount} files)"
            }.onFailure { err ->
                _homeFeedbackMessage.value = "Sync failed: ${err.message ?: "Configure GitHub OAuth token if private"}"
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
        commitGraphJob?.cancel()
        _currentScreen.value = ScreenDestination.RepoExplorer(repoId)
        _explorerState.value = ExplorerUiState(isRefreshingTree = true)

        treeObserveJob = viewModelScope.launch {
            val repo = observeRepositoryTreeUseCase.getRepository(repoId)
            val useScaleByDefault = repo?.id == "git/git"
            _explorerState.update {
                it.copy(
                    repository = repo,
                    availableBranches = repo?.availableBranches.orEmpty(),
                    highScaleGraphBenchmarkMode = useScaleByDefault
                )
            }

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

            launch {
                val branchesResult = switchRepositoryBranchUseCase.fetchBranches(repoId)
                branchesResult.onSuccess { list ->
                    _explorerState.update { it.copy(availableBranches = list) }
                }
            }

            // Preload Commit Graph in background so switching to Git Graph tab is instantaneous
            loadCommitGraphInternal(
                repoId = repoId,
                highScaleMode = useScaleByDefault,
                forceRefresh = false
            )

            observeRepositoryTreeUseCase.refreshTree(repoId, forceNetwork = false)
            _explorerState.update { it.copy(isRefreshingTree = false) }
        }
    }

    fun selectExplorerViewMode(mode: ExplorerViewMode) {
        _explorerState.update { it.copy(viewMode = mode) }
        val current = _explorerState.value
        if (mode == ExplorerViewMode.GIT_COMMIT_GRAPH && current.commitGraph == null && !current.isLoadingCommitGraph) {
            current.repository?.id?.let { repoId ->
                loadCommitGraphInternal(
                    repoId = repoId,
                    highScaleMode = current.highScaleGraphBenchmarkMode,
                    forceRefresh = false
                )
            }
        }
    }

    fun toggleHighScaleCommitGraphMode() {
        val current = _explorerState.value
        val repoId = current.repository?.id ?: return
        val newMode = !current.highScaleGraphBenchmarkMode
        _explorerState.update {
            it.copy(
                highScaleGraphBenchmarkMode = newMode,
                selectedCommitBranchFilter = null
            )
        }
        loadCommitGraphInternal(
            repoId = repoId,
            highScaleMode = newMode,
            forceRefresh = true
        )
    }

    fun updateCommitGraphSearch(query: String) {
        _explorerState.update { it.copy(commitSearchQuery = query) }
    }

    fun selectCommitGraphBranchFilter(branch: String?) {
        _explorerState.update { it.copy(selectedCommitBranchFilter = branch) }
    }

    private fun loadCommitGraphInternal(
        repoId: String,
        highScaleMode: Boolean,
        forceRefresh: Boolean
    ) {
        commitGraphJob?.cancel()
        _explorerState.update { it.copy(isLoadingCommitGraph = true) }
        commitGraphJob = viewModelScope.launch {
            val res = loadRepositoryCommitGraphUseCase(
                repoId = repoId,
                highScaleBenchmarkMode = highScaleMode,
                forceRefresh = forceRefresh
            )
            res.onSuccess { graph ->
                _explorerState.update {
                    it.copy(
                        commitGraph = graph,
                        isLoadingCommitGraph = false
                    )
                }
            }.onFailure {
                _explorerState.update { it.copy(isLoadingCommitGraph = false) }
            }
        }
    }

    fun refreshCurrentExplorerBranches() {
        val repoId = _explorerState.value.repository?.id ?: return
        _explorerState.update { it.copy(isLoadingBranches = true) }
        viewModelScope.launch {
            val result = switchRepositoryBranchUseCase.fetchBranches(repoId)
            val updatedRepo = observeRepositoryTreeUseCase.getRepository(repoId)
            _explorerState.update { current ->
                current.copy(
                    repository = updatedRepo ?: current.repository,
                    availableBranches = result.getOrElse { current.availableBranches },
                    isLoadingBranches = false
                )
            }
        }
    }

    fun switchCurrentExplorerBranch(targetBranch: String) {
        val repo = _explorerState.value.repository ?: return
        if (targetBranch.isBlank() || targetBranch == repo.defaultBranch) return

        _explorerState.update {
            it.copy(
                isSwitchingBranch = true,
                switchingToBranchName = targetBranch,
                statusBanner = "Switching to branch '$targetBranch'…"
            )
        }

        viewModelScope.launch {
            val switchResult = switchRepositoryBranchUseCase.switchBranch(repo.id, targetBranch)
            switchResult.onSuccess { updatedRepo ->
                _explorerState.update {
                    it.copy(
                        repository = updatedRepo,
                        availableBranches = updatedRepo.availableBranches,
                        expandedFolderPaths = emptySet(),
                        isSwitchingBranch = false,
                        switchingToBranchName = null,
                        statusBanner = "Switched to branch '${updatedRepo.defaultBranch}'"
                    )
                }
                loadCommitGraphInternal(
                    repoId = updatedRepo.id,
                    highScaleMode = _explorerState.value.highScaleGraphBenchmarkMode,
                    forceRefresh = true
                )
            }.onFailure { err ->
                _explorerState.update {
                    it.copy(
                        isSwitchingBranch = false,
                        switchingToBranchName = null,
                        statusBanner = err.message ?: "Failed to switch to branch '$targetBranch'"
                    )
                }
            }
        }
    }

    fun refreshCurrentExplorerTree() {
        val current = _explorerState.value
        val repoId = current.repository?.id ?: return

        if (current.viewMode == ExplorerViewMode.GIT_COMMIT_GRAPH) {
            loadCommitGraphInternal(
                repoId = repoId,
                highScaleMode = current.highScaleGraphBenchmarkMode,
                forceRefresh = true
            )
            return
        }

        _explorerState.update { it.copy(isRefreshingTree = true, statusBanner = "Fetching latest Git tree from GitHub…") }
        viewModelScope.launch {
            val res = observeRepositoryTreeUseCase.refreshTree(repoId, forceNetwork = true)
            val refreshedRepo = observeRepositoryTreeUseCase.getRepository(repoId)
            _explorerState.update {
                it.copy(
                    repository = refreshedRepo ?: it.repository,
                    availableBranches = refreshedRepo?.availableBranches ?: it.availableBranches,
                    isRefreshingTree = false,
                    statusBanner = if (res.isSuccess) "Synced file tree with GitHub" else "Could not refresh tree"
                )
            }
            loadCommitGraphInternal(
                repoId = repoId,
                highScaleMode = _explorerState.value.highScaleGraphBenchmarkMode,
                forceRefresh = true
            )
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
            val graphEngine = GitGraphLayoutEngine()
            val gateway = GitHubRepositoryGatewayImpl(
                appContext = appContext,
                providerCredentialDao = database.providerCredentialDao(),
                repositoryDao = database.repositoryDao(),
                repoTreeDao = database.repoTreeDao(),
                cachedDocumentDao = database.cachedDocumentDao(),
                gitHubClient = restClient,
                gitGraphLayoutEngine = graphEngine
            )
            val engine = CommonMarkDocumentEngine()

            return GitFolioViewModel(
                observeRepositoriesUseCase = ObserveRepositoriesUseCase(gateway),
                manageProviderCredentialsUseCase = ManageProviderCredentialsUseCase(gateway, gateway),
                probeRepositoryAccessibilityUseCase = ProbeRepositoryAccessibilityUseCase(gateway),
                addGitHubRepositoryUseCase = AddGitHubRepositoryUseCase(gateway),
                refreshRepositoryUseCase = RefreshRepositoryUseCase(gateway),
                switchRepositoryBranchUseCase = SwitchRepositoryBranchUseCase(gateway),
                removeRepositoryUseCase = RemoveRepositoryUseCase(gateway),
                observeRepositoryTreeUseCase = ObserveRepositoryTreeUseCase(gateway, gateway),
                loadRepositoryCommitGraphUseCase = LoadRepositoryCommitGraphUseCase(gateway, gateway),
                openDocumentAsMarkdownUseCase = OpenDocumentAsMarkdownUseCase(gateway, gateway, engine),
                computeVisibleTreeNodesUseCase = ComputeVisibleTreeNodesUseCase()
            ) as T
        }
    }
}
