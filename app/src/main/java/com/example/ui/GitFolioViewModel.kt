package com.example.ui

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.composition.CompositionRoot
import com.example.contracts.AuditLogEntry
import com.example.contracts.CommandDescriptor
import com.example.contracts.InstalledExtensionDescriptor
import com.example.contracts.JournaledOperation
import com.example.contracts.KeystoneArchitectureAuditReport
import com.example.contracts.MountedSlotContribution
import com.example.contracts.PendingApproval
import com.example.contracts.SystemProfile
import com.example.contracts.TraceLogEntry
import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitHostProvider
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.ProviderCredential
import com.example.domain.model.RepoAccessibilityProbe
import com.example.domain.model.RepoFileNode
import com.example.frontend.CrownArbiter
import com.example.ui.share.SharedRepoOverlayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Stable
sealed interface ScreenDestination {
    @Immutable
    data object HomeBento : ScreenDestination
    @Immutable
    data class RepoExplorer(val repoId: String) : ScreenDestination
    @Immutable
    data class MarkdownReader(val repoId: String, val filePath: String) : ScreenDestination
}

@Stable
sealed interface ReaderLoadState {
    @Immutable
    data object Loading : ReaderLoadState
    @Immutable
    data class Ready(val document: ParsedMarkdownDocument) : ReaderLoadState
    @Immutable
    data class Error(val message: String) : ReaderLoadState
}

@Stable
sealed interface AddRepoProbeUiState {
    @Immutable
    data object Idle : AddRepoProbeUiState
    @Immutable
    data class Checking(val rawInput: String) : AddRepoProbeUiState
    @Immutable
    data class Verified(val probe: RepoAccessibilityProbe) : AddRepoProbeUiState
    @Immutable
    data class Error(val rawInput: String, val message: String) : AddRepoProbeUiState
}

@Immutable
enum class ExplorerViewMode {
    FILES_TREE,
    GIT_COMMIT_GRAPH
}

@Immutable
data class ExplorerUiState(
    val repository: GitRepositoryCard? = null,
    val viewMode: ExplorerViewMode = ExplorerViewMode.FILES_TREE,
    val availableBranches: List<String> = emptyList(),
    val isLoadingBranches: Boolean = false,
    val isSwitchingBranch: Boolean = false,
    val switchingToBranchName: String? = null,
    val allNodes: List<RepoFileNode> = emptyList(),
    val totalFileCount: Int = 0,
    val quickFiles: List<RepoFileNode> = emptyList(),
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

/**
 * Android lifecycle holder wrapping the Keystone Architecture's `CrownArbiter`.
 * All UI state lives in `CrownArbiter`, and all cross-side actions travel as Envelopes
 * through `KeystonePort` -> `FoundationArbiter` + `Warden`.
 */
@Stable
class GitFolioViewModel(
    val crownArbiter: CrownArbiter
) : ViewModel() {

    val repositories: StateFlow<List<GitRepositoryCard>> = crownArbiter.repositories
    val providerCredentials: StateFlow<List<ProviderCredential>> = crownArbiter.providerCredentials
    val currentScreen: StateFlow<ScreenDestination> = crownArbiter.currentScreen
    val syncingRepoIds: StateFlow<Set<String>> = crownArbiter.syncingRepoIds
    val addRepoProbeState: StateFlow<AddRepoProbeUiState> = crownArbiter.addRepoProbeState
    val isAddingRepo: StateFlow<Boolean> = crownArbiter.isAddingRepo
    val homeFeedbackMessage: StateFlow<String?> = crownArbiter.homeFeedbackMessage
    val sharedOverlayState: StateFlow<SharedRepoOverlayState> = crownArbiter.sharedOverlayState
    val explorerState: StateFlow<ExplorerUiState> = crownArbiter.explorerState
    val readerState: StateFlow<ReaderLoadState> = crownArbiter.readerState
    val darkThemeEnabled: StateFlow<Boolean> = crownArbiter.darkThemeEnabled
    val readerFontScale: StateFlow<Float> = crownArbiter.readerFontScale
    val readerUseSerifBody: StateFlow<Boolean> = crownArbiter.readerUseSerifBody

    // Keystone Architecture Observability, Audit & Governance Streams
    val auditReport: StateFlow<KeystoneArchitectureAuditReport?> = crownArbiter.auditReport
    val traceLog: StateFlow<List<TraceLogEntry>> = crownArbiter.traceLog
    val registeredCommands: StateFlow<List<CommandDescriptor>> = crownArbiter.registeredCommands
    val journaledOperations: StateFlow<List<JournaledOperation>> = crownArbiter.journaledOperations
    val auditLog: StateFlow<List<AuditLogEntry>> = crownArbiter.auditLog
    val pendingApprovals: StateFlow<List<PendingApproval>> = crownArbiter.pendingApprovals
    val activeProfile: StateFlow<SystemProfile> = crownArbiter.activeProfile
    val installedExtensions: StateFlow<List<InstalledExtensionDescriptor>> = crownArbiter.installedExtensions
    val mountedContributions: StateFlow<List<MountedSlotContribution>> = crownArbiter.mountedContributions

    private val _isKeystoneInspectorOpen = MutableStateFlow(false)
    val isKeystoneInspectorOpen: StateFlow<Boolean> = _isKeystoneInspectorOpen.asStateFlow()

    fun toggleKeystoneInspector() {
        val opening = !_isKeystoneInspectorOpen.value
        _isKeystoneInspectorOpen.value = opening
        if (opening) {
            crownArbiter.refreshArchitecturalAudit()
        }
    }

    fun closeKeystoneInspector() {
        _isKeystoneInspectorOpen.value = false
    }

    fun refreshArchitecturalAudit() =
        crownArbiter.refreshArchitecturalAudit()

    fun executeCommandFromPalette(commandId: String) =
        crownArbiter.dispatchCommandFromPalette(commandId)

    fun approvePendingOperation(approvalId: String) =
        crownArbiter.approvePendingOperation(approvalId)

    fun rejectPendingOperation(approvalId: String) =
        crownArbiter.rejectPendingOperation(approvalId)

    fun undoLastMutation() =
        crownArbiter.undoLastMutation()

    fun rebuildDerivedCaches() =
        crownArbiter.rebuildDerivedCaches()

    fun triggerAutomatedSyncActor() =
        crownArbiter.triggerAutomatedSyncActor()

    fun switchProfile(profileId: String) =
        crownArbiter.switchProfile(profileId)

    fun toggleExtension(extensionId: String) =
        crownArbiter.toggleExtension(extensionId)

    fun toggleTheme() = crownArbiter.toggleTheme()
    fun adjustReaderFontScale(delta: Float) = crownArbiter.adjustReaderFontScale(delta)
    fun toggleReaderBodyFont() = crownArbiter.toggleReaderBodyFont()
    fun clearHomeFeedback() = crownArbiter.clearHomeFeedback()
    fun saveProviderToken(provider: GitHostProvider, token: String) = crownArbiter.saveProviderToken(provider, token)
    fun clearProviderToken(provider: GitHostProvider) = crownArbiter.clearProviderToken(provider)
    fun probeRepositoryInput(rawInput: String) = crownArbiter.probeRepositoryInput(rawInput)
    fun resetRepoProbe() = crownArbiter.resetRepoProbe()

    fun addRepository(
        rawInput: String,
        selectedBranch: String,
        knownBranches: List<String>,
        onSuccess: () -> Unit
    ) = crownArbiter.addRepository(rawInput, selectedBranch, knownBranches, onSuccess)

    fun handleIncomingSharedGitUrl(rawSharedText: String) = crownArbiter.handleIncomingSharedGitUrl(rawSharedText)
    fun dismissSharedOverlay() = crownArbiter.dismissSharedOverlay()
    fun openSharedRepositoryImmediately(card: GitRepositoryCard) = crownArbiter.openSharedRepositoryImmediately(card)
    fun refreshRepositoryOnTile(repoId: String) = crownArbiter.refreshRepositoryOnTile(repoId)
    fun removeRepository(repoId: String) = crownArbiter.removeRepository(repoId)
    fun openRepositoryExplorer(repoId: String) = crownArbiter.openRepositoryExplorer(repoId)
    fun selectExplorerViewMode(mode: ExplorerViewMode) = crownArbiter.selectExplorerViewMode(mode)
    fun toggleHighScaleCommitGraphMode() = crownArbiter.toggleHighScaleCommitGraphMode()
    fun updateCommitGraphSearch(query: String) = crownArbiter.updateCommitGraphSearch(query)
    fun selectCommitGraphBranchFilter(branch: String?) = crownArbiter.selectCommitGraphBranchFilter(branch)
    fun refreshCurrentExplorerBranches() = crownArbiter.refreshCurrentExplorerBranches()
    fun switchCurrentExplorerBranch(targetBranch: String) = crownArbiter.switchCurrentExplorerBranch(targetBranch)
    fun refreshCurrentExplorerTree() = crownArbiter.refreshCurrentExplorerTree()
    fun toggleFolderExpanded(folderPath: String) = crownArbiter.toggleFolderExpanded(folderPath)
    fun expandAllFolders() = crownArbiter.expandAllFolders()
    fun collapseAllFolders() = crownArbiter.collapseAllFolders()
    fun updateExplorerSearch(query: String) = crownArbiter.updateExplorerSearch(query)
    fun toggleMarkdownOnlyFilter() = crownArbiter.toggleMarkdownOnlyFilter()
    fun openFileInMarkdownReader(node: RepoFileNode) = crownArbiter.openFileInMarkdownReader(node)
    fun navigateBack() = crownArbiter.navigateBack()

    class Factory(private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return object : GitFolioViewModelHolder(appContext) {}.viewModel as T
        }
    }

    private abstract class GitFolioViewModelHolder(appContext: Context) {
        val viewModel: GitFolioViewModel by lazy {
            lateinit var vm: GitFolioViewModel
            val holderScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
            )
            val compositionRoot = CompositionRoot(
                appContext = appContext.applicationContext,
                appScope = holderScope
            )
            vm = GitFolioViewModel(crownArbiter = compositionRoot.crownArbiter)
            vm
        }
    }
}
