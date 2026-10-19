package com.example.composition

import android.content.Context
import com.example.backend.FoundationArbiter
import com.example.backend.Warden
import com.example.contracts.AdapterCapabilities
import com.example.contracts.AuditLogEntry
import com.example.contracts.JournaledOperation
import com.example.contracts.PendingApproval
import com.example.data.engine.CommonMarkDocumentEngine
import com.example.data.engine.GitGraphLayoutEngine
import com.example.data.local.GitFolioDatabase
import com.example.data.remote.GitHubRestClient
import com.example.data.repository.GitHubRepositoryGatewayImpl
import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ProviderCredential
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
import com.example.frontend.CrownArbiter
import com.example.frontend.CrownSubscriptionPorts
import com.example.kernel.KeystoneKernel
import com.example.kernel.PluginHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keystone Architecture — Section 5 & Section 15:
 * The Composition Root:
 * - The ONLY module that depends on every side (`kernel`, `frontend`, `backend`, `contracts`).
 * - Wires concrete adapters to ports — including wrapping `GitHubRestClient` in the `Warden`
 *   outbound policy gate — and boots crash recovery on the `FoundationArbiter`.
 * - Used only by the app launcher (`MainActivity` / `GitFolioViewModel.Factory`).
 */
class CompositionRoot(
    appContext: Context,
    appScope: CoroutineScope
) {
    val database: GitFolioDatabase = GitFolioDatabase.getInstance(appContext)

    // 1. Keystone Kernel + Plugin Host (Middle)
    val kernel: KeystoneKernel = KeystoneKernel(parentScope = appScope)
    val pluginHost: PluginHost = PluginHost(kernel = kernel)

    // 2. Foundation Warden (Single Policy & Outbound Gate)
    val warden: Warden = Warden(auditChainDao = database.auditChainDao())

    // 3. Guarded Network Adapter (constructed ONLY here, wrapped in Warden OutboundPolicyGatePort)
    private val guardedGitHubClient: GitHubRestClient = GitHubRestClient(
        outboundPolicyGate = warden
    )

    private val gitGraphLayoutEngine = GitGraphLayoutEngine()
    private val markdownEngine = CommonMarkDocumentEngine()

    private val gateway = GitHubRepositoryGatewayImpl(
        appContext = appContext,
        providerCredentialDao = database.providerCredentialDao(),
        repositoryDao = database.repositoryDao(),
        repoTreeDao = database.repoTreeDao(),
        cachedDocumentDao = database.cachedDocumentDao(),
        gitHubClient = guardedGitHubClient,
        gitGraphLayoutEngine = gitGraphLayoutEngine
    )

    private val observeRepositoriesUseCase = ObserveRepositoriesUseCase(gateway)
    private val manageProviderCredentialsUseCase = ManageProviderCredentialsUseCase(gateway, gateway)
    private val probeRepositoryAccessibilityUseCase = ProbeRepositoryAccessibilityUseCase(gateway)
    private val addGitHubRepositoryUseCase = AddGitHubRepositoryUseCase(gateway)
    private val refreshRepositoryUseCase = RefreshRepositoryUseCase(gateway)
    private val switchRepositoryBranchUseCase = SwitchRepositoryBranchUseCase(gateway)
    private val removeRepositoryUseCase = RemoveRepositoryUseCase(gateway)
    private val observeRepositoryTreeUseCase = ObserveRepositoryTreeUseCase(gateway, gateway)
    private val loadRepositoryCommitGraphUseCase = LoadRepositoryCommitGraphUseCase(gateway, gateway)
    private val openDocumentAsMarkdownUseCase = OpenDocumentAsMarkdownUseCase(gateway, gateway, markdownEngine)
    private val computeVisibleTreeNodesUseCase = ComputeVisibleTreeNodesUseCase()

    // 4. Foundation Arbiter (Single Writer & Mutation Protocol Engine)
    val foundationArbiter: FoundationArbiter = FoundationArbiter(
        kernel = kernel,
        pluginHost = pluginHost,
        warden = warden,
        repositoryDao = database.repositoryDao(),
        resourceVersionDao = database.resourceVersionDao(),
        operationJournalDao = database.operationJournalDao(),
        repoTreeDao = database.repoTreeDao(),
        cachedDocumentDao = database.cachedDocumentDao(),
        observeRepositoriesUseCase = observeRepositoriesUseCase,
        manageProviderCredentialsUseCase = manageProviderCredentialsUseCase,
        probeRepositoryAccessibilityUseCase = probeRepositoryAccessibilityUseCase,
        addGitHubRepositoryUseCase = addGitHubRepositoryUseCase,
        refreshRepositoryUseCase = refreshRepositoryUseCase,
        switchRepositoryBranchUseCase = switchRepositoryBranchUseCase,
        removeRepositoryUseCase = removeRepositoryUseCase,
        observeRepositoryTreeUseCase = observeRepositoryTreeUseCase,
        loadRepositoryCommitGraphUseCase = loadRepositoryCommitGraphUseCase,
        openDocumentAsMarkdownUseCase = openDocumentAsMarkdownUseCase
    )

    // 5. Read-only Subscription Bridge for Crown Arbiter (Section 8: "Commands write, subscriptions read")
    private val crownSubscriptions = object : CrownSubscriptionPorts {
        override fun observeRepositories(): Flow<List<GitRepositoryCard>> =
            observeRepositoriesUseCase()

        override fun observeCredentials(): Flow<List<ProviderCredential>> =
            manageProviderCredentialsUseCase.observeCredentials()

        override fun observeRepositoryTree(repoId: String): Flow<List<RepoFileNode>> =
            observeRepositoryTreeUseCase.observe(repoId)

        override suspend fun getRepository(repoId: String): GitRepositoryCard? =
            observeRepositoryTreeUseCase.getRepository(repoId)

        override suspend fun filterCommitRows(
            allRows: List<GitGraphCommitRow>,
            rawQuery: String,
            branchFilter: String?
        ): List<GitGraphCommitRow> =
            loadRepositoryCommitGraphUseCase.filterCommitRows(allRows, rawQuery, branchFilter)

        override fun observeJournaledOperations(): Flow<List<JournaledOperation>> =
            foundationArbiter.observeJournaledOperations()

        override fun observeAuditLog(): Flow<List<AuditLogEntry>> =
            warden.observeRecentAuditLog()

        override fun observePendingApprovals(): StateFlow<List<PendingApproval>> =
            warden.pendingApprovalsFlow

        override suspend fun verifyAuditChainIntegrity(): Boolean =
            warden.verifyAuditChainIntegrity()

        override fun getDeclaredAdapterCapabilities(): List<AdapterCapabilities> =
            listOf(guardedGitHubClient.declaredCapabilities)
    }

    // 6. Crown Arbiter (Frontend UI State Store + Crown Bus)
    val crownArbiter: CrownArbiter = CrownArbiter(
        kernel = kernel,
        pluginHost = pluginHost,
        subscriptions = crownSubscriptions,
        computeVisibleTreeNodesUseCase = computeVisibleTreeNodesUseCase
    )

    init {
        // Run Section 9.4 Crash Recovery on startup before serving user operations
        appScope.launch {
            foundationArbiter.recoverIncompleteOperationsOnStartup()
        }
    }
}
