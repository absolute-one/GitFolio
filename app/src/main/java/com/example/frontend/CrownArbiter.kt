package com.example.frontend

import com.example.contracts.ActorKind
import com.example.contracts.AdapterCapabilities
import com.example.contracts.AuditLogEntry
import com.example.contracts.CommandCatalogue
import com.example.contracts.CommandDescriptor
import com.example.contracts.DataSensitivityLabel
import com.example.contracts.Envelope
import com.example.contracts.EnvelopeKind
import com.example.contracts.ErrorCode
import com.example.contracts.InstalledExtensionDescriptor
import com.example.contracts.JournaledOperation
import com.example.contracts.KeystoneArchitectureAuditReport
import com.example.contracts.KeystoneArchitectureAuditor
import com.example.contracts.KeystonePort
import com.example.contracts.MountedSlotContribution
import com.example.contracts.PendingApproval
import com.example.contracts.PluginHostPort
import com.example.contracts.ResourceRef
import com.example.contracts.SlotCatalogue
import com.example.contracts.SlotContributionSpec
import com.example.contracts.SystemProfile
import com.example.contracts.TopicCatalogue
import com.example.contracts.TraceLogEntry
import com.example.contracts.TrustTier
import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitHostProvider
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.ProviderCredential
import com.example.domain.model.RepoAccessibilityProbe
import com.example.domain.model.RepoFileNode
import com.example.domain.usecase.ComputeVisibleTreeNodesUseCase
import com.example.ui.AddRepoProbeUiState
import com.example.ui.ExplorerUiState
import com.example.ui.ExplorerViewMode
import com.example.ui.ReaderLoadState
import com.example.ui.ScreenDestination
import com.example.ui.share.SharedRepoOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only subscription ports provided to the Crown Arbiter by the Composition Root
 * so the Crown never imports `com.example.backend` or `com.example.kernel`
 * (Section 8 "Commands write, subscriptions read" & Section 15 Dependency Rules).
 */
interface CrownSubscriptionPorts {
    fun observeRepositories(): Flow<List<GitRepositoryCard>>
    fun observeCredentials(): Flow<List<ProviderCredential>>
    fun observeRepositoryTree(repoId: String): Flow<List<RepoFileNode>>
    suspend fun getRepository(repoId: String): GitRepositoryCard?
    suspend fun filterCommitRows(
        allRows: List<GitGraphCommitRow>,
        rawQuery: String,
        branchFilter: String?
    ): List<GitGraphCommitRow>
    fun observeJournaledOperations(): Flow<List<JournaledOperation>>
    fun observeAuditLog(): Flow<List<AuditLogEntry>>
    fun observePendingApprovals(): StateFlow<List<PendingApproval>>
    suspend fun verifyAuditChainIntegrity(): Boolean
    fun getDeclaredAdapterCapabilities(): List<AdapterCapabilities>
}

/**
 * In-Crown View-to-View Event Bus (Section 6: "View -> view (same side): Crown bus, never direct").
 */
data class CrownUiEvent(
    val topic: String,
    val params: Map<String, String> = emptyMap()
)

/**
 * Keystone Architecture — Section 1, 4, 5, 6, 8 & 15:
 * The Crown Arbiter:
 * - Owns the UI state store, the Crown Bus, optimistic updates, acknowledgement tracking, and drafts.
 * - Dispatches every mutation and cross-side command/request exclusively as an Envelope through `KeystonePort`.
 * - Reads large AST/DAG payloads via reference handle (`getLargeContentByReference`).
 * - Depends strictly on `com.example.contracts` — never imports `com.example.backend` or `com.example.kernel`.
 */
@OptIn(FlowPreview::class)
class CrownArbiter(
    private val kernel: KeystonePort,
    private val pluginHost: PluginHostPort,
    private val subscriptions: CrownSubscriptionPorts,
    private val computeVisibleTreeNodesUseCase: ComputeVisibleTreeNodesUseCase = ComputeVisibleTreeNodesUseCase()
) {
    val crownScope: CoroutineScope = CoroutineScope(kernel.kernelScope.coroutineContext + SupervisorJob())

    companion object {
        const val SENDER_ID = "crown.arbiter"
    }

    // In-Crown publish/subscribe bus for view-to-view communication
    private val _crownBus = MutableSharedFlow<CrownUiEvent>(extraBufferCapacity = 64)
    val crownBus: SharedFlow<CrownUiEvent> = _crownBus.asSharedFlow()

    // Known ResourceRef versions & contentHashes observed by Crown for precondition enforcement (Section 9.3)
    private val knownResourceRefs = ConcurrentHashMap<String, ResourceRef>()

    // Deduplication set for incoming Foundation events (Loop prevention — Section 7)
    private val processedCrownEventIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 1024
            }
        })
    )

    val repositories: StateFlow<List<GitRepositoryCard>> = subscriptions.observeRepositories()
        .stateIn(
            scope = crownScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val providerCredentials: StateFlow<List<ProviderCredential>> = subscriptions.observeCredentials()
        .stateIn(
            scope = crownScope,
            started = SharingStarted.Eagerly,
            initialValue = GitHostProvider.entries.map { ProviderCredential(it, "", 0L) }
        )

    val traceLog: StateFlow<List<TraceLogEntry>> = kernel.traceLog
    val registeredCommands: StateFlow<List<CommandDescriptor>> = kernel.commandsFlow
    val activeProfile: StateFlow<SystemProfile> = pluginHost.activeProfile
    val installedExtensions: StateFlow<List<InstalledExtensionDescriptor>> = pluginHost.extensionsFlow
    val mountedContributions: StateFlow<List<MountedSlotContribution>> = pluginHost.mountedContributions
    val pendingApprovals: StateFlow<List<PendingApproval>> = subscriptions.observePendingApprovals()

    val journaledOperations: StateFlow<List<JournaledOperation>> = subscriptions.observeJournaledOperations()
        .stateIn(
            scope = crownScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val auditLog: StateFlow<List<AuditLogEntry>> = subscriptions.observeAuditLog()
        .stateIn(
            scope = crownScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _auditReport = MutableStateFlow<KeystoneArchitectureAuditReport?>(null)
    val auditReport: StateFlow<KeystoneArchitectureAuditReport?> = _auditReport.asStateFlow()

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

    // Unsaved user input drafts owned by Crown Arbiter (Section 5 & 9.6)
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private var treeObserveJob: Job? = null
    private var commitGraphJob: Job? = null
    private var probeJob: Job? = null

    init {
        kernel.registerSender(
            senderId = SENDER_ID,
            actorKind = ActorKind.USER,
            trustTier = TrustTier.CORE,
            scopeId = "scope:crown"
        )

        // Subscribe to cross-side Foundation events & replies arriving via Keystone
        crownScope.launch {
            kernel.toCrown.collect { envelope ->
                envelope.resource?.let { ref ->
                    knownResourceRefs[ref.resourceId] = ref
                }
                if (!processedCrownEventIds.add(envelope.eventId)) return@collect
                if (envelope.kind == EnvelopeKind.EVENT) {
                    handleIncomingFoundationEvent(envelope)
                }
            }
        }

        // Dispatch initial `repository.seedDefault` command through Keystone on startup
        crownScope.launch {
            kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_SEED_DEFAULT
            )
            refreshArchitecturalAudit()
        }

        // Reactive pipeline for hierarchical / fuzzy-searched file tree nodes
        crownScope.launch {
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
        crownScope.launch {
            val debouncedCommitQueryFlow = _explorerState
                .map { it.commitSearchQuery }
                .distinctUntilChanged()
                .debounce { q -> if (q.isBlank()) 0L else 50L }

            val commitGraphInputsFlow = _explorerState
                .map { it.commitGraph?.rows.orEmpty() to it.selectedCommitBranchFilter }
                .distinctUntilChanged()

            combine(commitGraphInputsFlow, debouncedCommitQueryFlow) { (allRows, branchFilter), query ->
                subscriptions.filterCommitRows(
                    allRows = allRows,
                    rawQuery = query,
                    branchFilter = branchFilter
                )
            }.collectLatest { filtered ->
                _explorerState.update { it.copy(filteredCommitRows = filtered) }
            }
        }
    }

    fun refreshArchitecturalAudit() {
        crownScope.launch {
            val chainIntact = subscriptions.verifyAuditChainIntegrity()
            val report = KeystoneArchitectureAuditor.runAudit(
                kernel = kernel,
                pluginHost = pluginHost,
                adapterCapabilities = subscriptions.getDeclaredAdapterCapabilities(),
                auditChainIntact = chainIntact,
                journalCount = journaledOperations.value.size,
                auditEntryCount = auditLog.value.size
            )
            _auditReport.value = report
        }
    }

    private fun handleIncomingFoundationEvent(envelope: Envelope) {
        when (envelope.topic) {
            TopicCatalogue.MUTATION_AWAITING_APPROVAL -> {
                val summary = envelope.payload["summary"] ?: "Warden approval required"
                _homeFeedbackMessage.value = "Warden Gate: $summary (Approve in Keystone Inspector)"
                refreshArchitecturalAudit()
            }
            TopicCatalogue.MUTATION_COMMITTED -> {
                refreshArchitecturalAudit()
            }
            TopicCatalogue.MUTATION_CONFLICTED -> {
                val resId = envelope.payload["resourceId"].orEmpty()
                _homeFeedbackMessage.value = "Precondition conflict on $resId — preserved existing version"
                refreshArchitecturalAudit()
            }
            TopicCatalogue.MUTATION_UNDONE -> {
                val summary = envelope.payload["summary"] ?: "Operation undone"
                _homeFeedbackMessage.value = summary
                refreshArchitecturalAudit()
            }
            TopicCatalogue.CACHE_DERIVED_REBUILT -> {
                val count = envelope.payload["rebuiltCount"] ?: "0"
                _homeFeedbackMessage.value = "Rebuilt derived caches for $count repositories from durable truth"
                refreshArchitecturalAudit()
            }
            TopicCatalogue.PROFILE_SWITCHED -> {
                val name = envelope.payload["displayName"] ?: "Profile"
                _homeFeedbackMessage.value = "Activated $name"
                refreshArchitecturalAudit()
            }
            TopicCatalogue.EXTENSION_STATE_CHANGED -> {
                refreshArchitecturalAudit()
            }
        }
    }

    fun publishToCrownBus(topic: String, params: Map<String, String> = emptyMap()) {
        _crownBus.tryEmit(CrownUiEvent(topic, params))
    }

    fun updateDraft(key: String, value: String) {
        _drafts.update { it + (key to value) }
    }

    /**
     * Unified Command Dispatcher (Law 6: "Everything is a command"):
     * Powers buttons, shortcuts, command palette, automated actors, and extensions.
     */
    fun dispatchCommandFromPalette(commandId: String, args: Map<String, String> = emptyMap()) {
        when (commandId) {
            CommandCatalogue.UI_TOGGLE_THEME -> toggleTheme()
            CommandCatalogue.UI_NAVIGATE_BACK -> navigateBack()
            CommandCatalogue.READER_TOGGLE_SERIF -> toggleReaderBodyFont()
            CommandCatalogue.READER_ADJUST_FONT_SCALE -> {
                val delta = args["delta"]?.toFloatOrNull() ?: 0.1f
                adjustReaderFontScale(delta)
            }
            CommandCatalogue.EXPLORER_TOGGLE_MARKDOWN_ONLY -> toggleMarkdownOnlyFilter()
            CommandCatalogue.EXPLORER_EXPAND_ALL -> expandAllFolders()
            CommandCatalogue.EXPLORER_COLLAPSE_ALL -> collapseAllFolders()
            CommandCatalogue.GRAPH_TOGGLE_HIGH_SCALE -> toggleHighScaleCommitGraphMode()
            else -> {
                crownScope.launch {
                    val reply = kernel.dispatchToFoundation(
                        senderId = SENDER_ID,
                        topic = commandId,
                        payload = args
                    )
                    if (reply.kind == EnvelopeKind.ERROR) {
                        val reason = reply.payload["reason"]
                            ?: reply.payload["message"]
                            ?: reply.error?.messageKey
                            ?: "Command failed"
                        _homeFeedbackMessage.value = reason
                    } else {
                        reply.payload["summary"]?.let { _homeFeedbackMessage.value = it }
                    }
                    refreshArchitecturalAudit()
                }
            }
        }
    }

    fun approvePendingOperation(approvalId: String) {
        crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.MUTATION_APPROVE,
                payload = mapOf("approvalId" to approvalId)
            )
            if (reply.kind == EnvelopeKind.ERROR) {
                _homeFeedbackMessage.value = reply.payload["reason"] ?: "Approval revalidation failed"
            } else {
                _homeFeedbackMessage.value = reply.payload["summary"] ?: "Approved & committed operation"
            }
            refreshArchitecturalAudit()
        }
    }

    fun rejectPendingOperation(approvalId: String) {
        crownScope.launch {
            kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.MUTATION_REJECT,
                payload = mapOf("approvalId" to approvalId)
            )
            _homeFeedbackMessage.value = "Rejected pending operation"
            refreshArchitecturalAudit()
        }
    }

    fun undoLastMutation() {
        crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.MUTATION_UNDO_LAST_RUN
            )
            if (reply.kind == EnvelopeKind.ERROR) {
                _homeFeedbackMessage.value = reply.payload["message"] ?: "Nothing to undo or resource modified since"
            } else {
                _homeFeedbackMessage.value = reply.payload["summary"] ?: "Undid last mutation"
            }
            refreshArchitecturalAudit()
        }
    }

    fun switchProfile(profileId: String) {
        crownScope.launch {
            kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.PROFILE_SWITCH,
                payload = mapOf("profileId" to profileId)
            )
            refreshArchitecturalAudit()
        }
    }

    fun toggleExtension(extensionId: String) {
        crownScope.launch {
            kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.EXTENSION_TOGGLE,
                payload = mapOf("extensionId" to extensionId)
            )
            refreshArchitecturalAudit()
        }
    }

    fun rebuildDerivedCaches() {
        crownScope.launch {
            kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.CACHE_REBUILD_DERIVED
            )
            refreshArchitecturalAudit()
        }
    }

    fun triggerAutomatedSyncActor() {
        crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.ACTOR_RUN_SYNC_SWEEP
            )
            if (reply.kind != EnvelopeKind.ERROR) {
                val count = reply.payload["syncedCount"] ?: "0"
                _homeFeedbackMessage.value = "Automated Actor synced $count repositories via Keystone"
            }
            refreshArchitecturalAudit()
        }
    }

    fun toggleTheme() {
        _darkThemeEnabled.update { !it }
        publishToCrownBus(CommandCatalogue.UI_TOGGLE_THEME, mapOf("dark" to _darkThemeEnabled.value.toString()))
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
        crownScope.launch {
            val resId = "secret:${provider.providerId}"
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.CREDENTIAL_SAVE,
                payload = mapOf(
                    "providerId" to provider.providerId,
                    "token" to token
                ),
                resource = knownResourceRefs[resId],
                sensitivityLabel = DataSensitivityLabel.SECRET_CREDENTIAL
            )
            if (reply.kind == EnvelopeKind.ERROR) {
                _homeFeedbackMessage.value = reply.payload["reason"] ?: "Failed to save token"
            } else {
                _homeFeedbackMessage.value = if (token.isNotBlank()) {
                    "${provider.displayName} OAuth token saved"
                } else {
                    "${provider.displayName} token cleared"
                }
            }
        }
    }

    fun clearProviderToken(provider: GitHostProvider) {
        crownScope.launch {
            val resId = "secret:${provider.providerId}"
            kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.CREDENTIAL_CLEAR,
                payload = mapOf("providerId" to provider.providerId),
                resource = knownResourceRefs[resId],
                sensitivityLabel = DataSensitivityLabel.SECRET_CREDENTIAL
            )
            _homeFeedbackMessage.value = "${provider.displayName} token cleared"
        }
    }

    fun probeRepositoryInput(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        updateDraft("repo_probe_input", trimmed)
        probeJob?.cancel()
        _addRepoProbeState.value = AddRepoProbeUiState.Checking(trimmed)

        probeJob = crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_PROBE,
                kind = EnvelopeKind.REQUEST,
                payload = mapOf("rawInput" to trimmed)
            )
            if (reply.kind == EnvelopeKind.ERROR) {
                _addRepoProbeState.value = AddRepoProbeUiState.Error(
                    rawInput = trimmed,
                    message = reply.payload["message"] ?: reply.payload["reason"] ?: "Unable to verify repository access."
                )
            } else {
                val branches = reply.payload["branchesCsv"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                val probe = RepoAccessibilityProbe(
                    owner = reply.payload["owner"].orEmpty(),
                    name = reply.payload["name"].orEmpty(),
                    defaultBranch = reply.payload["defaultBranch"].orEmpty().ifEmpty { "main" },
                    branches = branches.ifEmpty { listOf("main") },
                    description = reply.payload["description"].orEmpty(),
                    isPrivate = reply.payload["isPrivate"] == "true",
                    authenticatedViaOAuth = reply.payload["authenticatedViaOAuth"] == "true"
                )
                _addRepoProbeState.value = AddRepoProbeUiState.Verified(probe)
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

        crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_ADD,
                payload = mapOf(
                    "rawInput" to rawInput,
                    "selectedBranch" to selectedBranch,
                    "knownBranchesCsv" to knownBranches.joinToString(",")
                )
            )
            _isAddingRepo.value = false
            if (reply.kind == EnvelopeKind.ERROR) {
                _homeFeedbackMessage.value = reply.payload["message"]
                    ?: reply.payload["reason"]
                    ?: "Failed to add repository"
            } else {
                _addRepoProbeState.value = AddRepoProbeUiState.Idle
                _homeFeedbackMessage.value = reply.payload["summary"]
                    ?: "Added ${reply.payload["fullSlug"]}"
                onSuccess()
            }
        }
    }

    fun handleIncomingSharedGitUrl(rawSharedText: String) {
        val trimmed = rawSharedText.trim()
        if (trimmed.isBlank()) return

        _sharedOverlayState.value = SharedRepoOverlayState.Mounting(trimmed)
        crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_MOUNT_SHARED,
                payload = mapOf("rawSharedUrl" to trimmed)
            )
            if (reply.kind == EnvelopeKind.ERROR) {
                _sharedOverlayState.value = SharedRepoOverlayState.MountError(
                    rawSharedUrl = trimmed,
                    message = reply.payload["message"]
                        ?: reply.payload["reason"]
                        ?: "Unable to parse shared GitHub URL."
                )
            } else {
                val repoId = reply.payload["repoId"].orEmpty()
                val card = subscriptions.getRepository(repoId)
                if (card != null) {
                    _sharedOverlayState.value = SharedRepoOverlayState.MountedSuccess(card)
                } else {
                    _sharedOverlayState.value = SharedRepoOverlayState.MountError(
                        rawSharedUrl = trimmed,
                        message = "Repository could not be loaded after mount."
                    )
                }
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

        crownScope.launch {
            val resId = "repo:$repoId"
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_REFRESH,
                payload = mapOf("repoId" to repoId),
                resource = knownResourceRefs[resId]
            )
            _syncingRepoIds.update { it - repoId }
            if (reply.kind == EnvelopeKind.ERROR) {
                _homeFeedbackMessage.value = "Sync failed: ${reply.payload["message"] ?: reply.payload["reason"] ?: "Configure GitHub OAuth token if private"}"
            } else {
                _homeFeedbackMessage.value = reply.payload["summary"] ?: "Synced $repoId"
            }
        }
    }

    fun removeRepository(repoId: String) {
        crownScope.launch {
            val resId = "repo:$repoId"
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_REMOVE,
                payload = mapOf("repoId" to repoId),
                resource = knownResourceRefs[resId]
            )
            if (reply.kind == EnvelopeKind.ERROR) {
                if (reply.error?.code == ErrorCode.ApprovalRequired) {
                    _homeFeedbackMessage.value = "Warden Gate: Deletion of '$repoId' awaits approval in Keystone Inspector"
                } else {
                    _homeFeedbackMessage.value = reply.payload["reason"] ?: "Could not remove repository"
                }
            } else {
                knownResourceRefs.remove(resId)
                _homeFeedbackMessage.value = "Repository removed"
            }
        }
    }

    fun openRepositoryExplorer(repoId: String) {
        treeObserveJob?.cancel()
        commitGraphJob?.cancel()
        _currentScreen.value = ScreenDestination.RepoExplorer(repoId)
        _explorerState.value = ExplorerUiState(isRefreshingTree = true)

        // Mount workspace-scoped contribution for dual-owner lifecycle (Section 11.2)
        pluginHost.mountContribution(
            extensionId = "ext.commit-stats",
            spec = SlotContributionSpec(
                contributionId = "contrib.workspace.dag.$repoId",
                slotId = SlotCatalogue.EXPLORER_HEADER_BADGES,
                label = "Workspace DAG [$repoId]",
                commandIdToDispatch = CommandCatalogue.GRAPH_TOGGLE_HIGH_SCALE
            ),
            mountOwnerScopeId = "scope:workspace:$repoId"
        )

        treeObserveJob = crownScope.launch {
            val repo = subscriptions.getRepository(repoId)
            val useScaleByDefault = repo?.id == "git/git"
            _explorerState.update {
                it.copy(
                    repository = repo,
                    availableBranches = repo?.availableBranches.orEmpty(),
                    highScaleGraphBenchmarkMode = useScaleByDefault
                )
            }

            launch {
                subscriptions.observeRepositoryTree(repoId).collect { nodes ->
                    val topFolders = HashSet<String>()
                    var fileCount = 0
                    val quickList = ArrayList<RepoFileNode>(6)
                    for (i in 0 until nodes.size) {
                        val n = nodes[i]
                        if (n.isDirectory) {
                            if (n.depth == 0) topFolders.add(n.path)
                        } else {
                            fileCount++
                            if (quickList.size < 6 && (n.isLicenseOrReadme || n.isPureMarkdown)) {
                                quickList.add(n)
                            }
                        }
                    }

                    val currentSnap = _explorerState.value
                    val expanded = if (currentSnap.expandedFolderPaths.isEmpty()) {
                        topFolders
                    } else {
                        currentSnap.expandedFolderPaths
                    }
                    val initialVisible = computeVisibleTreeNodesUseCase(
                        allNodes = nodes,
                        expandedFolders = expanded,
                        rawQuery = currentSnap.searchQuery,
                        markdownOnly = currentSnap.markdownOnlyFilter
                    )

                    _explorerState.update { current ->
                        current.copy(
                            allNodes = nodes,
                            totalFileCount = fileCount,
                            quickFiles = quickList,
                            visibleNodes = initialVisible,
                            expandedFolderPaths = expanded,
                            isRefreshingTree = false
                        )
                    }
                }
            }

            launch {
                // Notify Keystone & Foundation Arbiter to switch active workspace & warm tree cache
                kernel.dispatchToFoundation(
                    senderId = SENDER_ID,
                    topic = CommandCatalogue.REPOSITORY_OPEN,
                    payload = mapOf("repoId" to repoId)
                )
            }

            launch {
                val reply = kernel.dispatchToFoundation(
                    senderId = SENDER_ID,
                    topic = CommandCatalogue.EXPLORER_REFRESH_BRANCHES,
                    kind = EnvelopeKind.REQUEST,
                    payload = mapOf("repoId" to repoId)
                )
                val branches = reply.payload["branchesCsv"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (branches.isNotEmpty()) {
                    _explorerState.update { it.copy(availableBranches = branches) }
                }
            }

            loadCommitGraphInternal(
                repoId = repoId,
                highScaleMode = useScaleByDefault,
                forceRefresh = false
            )
        }
    }

    fun selectExplorerViewMode(mode: ExplorerViewMode) {
        _explorerState.update { it.copy(viewMode = mode) }
        publishToCrownBus(CommandCatalogue.EXPLORER_SET_VIEW_MODE, mapOf("mode" to mode.name))
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
        updateDraft("commit_search", query)
        _explorerState.update { it.copy(commitSearchQuery = query) }
    }

    fun selectCommitGraphBranchFilter(branch: String?) {
        _explorerState.update { it.copy(selectedCommitBranchFilter = branch) }
    }

    /**
     * Dispatches `graph.load` REQUEST Envelope through Keystone to Foundation Arbiter and reads
     * the resulting `GitRepoCommitGraph` by reference handle (`contentRefId` — Section 7 Rule #7).
     */
    private fun loadCommitGraphInternal(
        repoId: String,
        highScaleMode: Boolean,
        forceRefresh: Boolean
    ) {
        commitGraphJob?.cancel()
        _explorerState.update { it.copy(isLoadingCommitGraph = true) }
        commitGraphJob = crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.GRAPH_LOAD,
                kind = EnvelopeKind.REQUEST,
                payload = mapOf(
                    "repoId" to repoId,
                    "highScaleMode" to highScaleMode.toString(),
                    "forceRefresh" to forceRefresh.toString()
                )
            )
            val refId = reply.payload["contentRefId"]
            val graph = refId?.let { kernel.getLargeContentByReference<GitRepoCommitGraph>(it) }
            if (reply.kind != EnvelopeKind.ERROR && graph != null) {
                val snap = _explorerState.value
                val initialFiltered = subscriptions.filterCommitRows(
                    allRows = graph.rows,
                    rawQuery = snap.commitSearchQuery,
                    branchFilter = snap.selectedCommitBranchFilter
                )
                _explorerState.update {
                    it.copy(
                        commitGraph = graph,
                        filteredCommitRows = initialFiltered,
                        isLoadingCommitGraph = false
                    )
                }
            } else {
                _explorerState.update { it.copy(isLoadingCommitGraph = false) }
            }
        }
    }

    fun refreshCurrentExplorerBranches() {
        val repoId = _explorerState.value.repository?.id ?: return
        _explorerState.update { it.copy(isLoadingBranches = true) }
        crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.EXPLORER_REFRESH_BRANCHES,
                kind = EnvelopeKind.REQUEST,
                payload = mapOf("repoId" to repoId)
            )
            val branches = reply.payload["branchesCsv"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val updatedRepo = subscriptions.getRepository(repoId)
            _explorerState.update { current ->
                current.copy(
                    repository = updatedRepo ?: current.repository,
                    availableBranches = branches.ifEmpty { current.availableBranches },
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

        crownScope.launch {
            val resId = "repo:${repo.id}"
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_SWITCH_BRANCH,
                payload = mapOf(
                    "repoId" to repo.id,
                    "targetBranch" to targetBranch
                ),
                resource = knownResourceRefs[resId]
            )
            if (reply.kind == EnvelopeKind.ERROR) {
                _explorerState.update {
                    it.copy(
                        isSwitchingBranch = false,
                        switchingToBranchName = null,
                        statusBanner = reply.payload["message"]
                            ?: reply.payload["reason"]
                            ?: "Failed to switch to branch '$targetBranch'"
                    )
                }
            } else {
                val updatedRepo = subscriptions.getRepository(repo.id)
                _explorerState.update {
                    it.copy(
                        repository = updatedRepo ?: it.repository,
                        availableBranches = updatedRepo?.availableBranches ?: it.availableBranches,
                        expandedFolderPaths = emptySet(),
                        isSwitchingBranch = false,
                        switchingToBranchName = null,
                        statusBanner = "Switched to branch '${updatedRepo?.defaultBranch ?: targetBranch}'"
                    )
                }
                loadCommitGraphInternal(
                    repoId = repo.id,
                    highScaleMode = _explorerState.value.highScaleGraphBenchmarkMode,
                    forceRefresh = true
                )
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
        crownScope.launch {
            val resId = "repo:$repoId"
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.REPOSITORY_REFRESH,
                payload = mapOf("repoId" to repoId),
                resource = knownResourceRefs[resId]
            )
            val refreshedRepo = subscriptions.getRepository(repoId)
            _explorerState.update {
                it.copy(
                    repository = refreshedRepo ?: it.repository,
                    availableBranches = refreshedRepo?.availableBranches ?: it.availableBranches,
                    isRefreshingTree = false,
                    statusBanner = if (reply.kind != EnvelopeKind.ERROR) {
                        "Synced file tree with GitHub"
                    } else {
                        reply.payload["reason"] ?: "Could not refresh tree"
                    }
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
        updateDraft("explorer_search", query)
        _explorerState.update { it.copy(searchQuery = query) }
    }

    fun toggleMarkdownOnlyFilter() {
        _explorerState.update { it.copy(markdownOnlyFilter = !it.markdownOnlyFilter) }
    }

    /**
     * Dispatches `document.open` REQUEST Envelope through Keystone to Foundation Arbiter and reads
     * the parsed `ParsedMarkdownDocument` by reference handle (`contentRefId` — Section 7 Rule #7).
     */
    fun openFileInMarkdownReader(node: RepoFileNode) {
        if (node.isDirectory) {
            toggleFolderExpanded(node.path)
            return
        }
        val repoId = node.repoId
        _currentScreen.value = ScreenDestination.MarkdownReader(repoId = repoId, filePath = node.path)
        _readerState.value = ReaderLoadState.Loading

        // Mount view-scoped slot contribution for dual-owner lifecycle (Section 11.2)
        pluginHost.mountContribution(
            extensionId = "ext.word-count",
            spec = SlotContributionSpec(
                contributionId = "contrib.reader.view.${node.path}",
                slotId = SlotCatalogue.READER_STATUS_BAR,
                label = "Reader AST [${node.name}]",
                commandIdToDispatch = "wordcount.toggle"
            ),
            mountOwnerScopeId = "scope:view:reader:${node.path}"
        )

        crownScope.launch {
            val reply = kernel.dispatchToFoundation(
                senderId = SENDER_ID,
                topic = CommandCatalogue.DOCUMENT_OPEN,
                kind = EnvelopeKind.REQUEST,
                payload = mapOf(
                    "repoId" to repoId,
                    "filePath" to node.path,
                    "sizeBytes" to node.sizeBytes.toString(),
                    "sha" to node.sha,
                    "forceRefresh" to "false"
                )
            )
            val refId = reply.payload["contentRefId"]
            val parsedDoc = refId?.let { kernel.getLargeContentByReference<ParsedMarkdownDocument>(it) }
            if (reply.kind != EnvelopeKind.ERROR && parsedDoc != null) {
                _readerState.value = ReaderLoadState.Ready(parsedDoc)
            } else {
                _readerState.value = ReaderLoadState.Error(
                    reply.payload["message"]
                        ?: reply.payload["reason"]
                        ?: "Unable to load ${node.path} from repository."
                )
            }
        }
    }

    fun navigateBack() {
        when (val screen = _currentScreen.value) {
            is ScreenDestination.MarkdownReader -> {
                pluginHost.onMountOwnerClosed("scope:view:reader:${screen.filePath}")
                _currentScreen.value = ScreenDestination.RepoExplorer(screen.repoId)
            }
            is ScreenDestination.RepoExplorer -> {
                pluginHost.onMountOwnerClosed("scope:workspace:${screen.repoId}")
                kernel.switchWorkspace("workspace:home")
                _currentScreen.value = ScreenDestination.HomeBento
            }
            ScreenDestination.HomeBento -> Unit
        }
    }
}
