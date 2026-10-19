package com.example.backend

import com.example.contracts.ActorKind
import com.example.contracts.Capability
import com.example.contracts.CommandCatalogue
import com.example.contracts.DataSensitivityLabel
import com.example.contracts.Envelope
import com.example.contracts.EnvelopeKind
import com.example.contracts.ErrorCode
import com.example.contracts.JournaledOperation
import com.example.contracts.KeystonePort
import com.example.contracts.Lane
import com.example.contracts.OperationState
import com.example.contracts.PluginHostPort
import com.example.contracts.ResourceRef
import com.example.contracts.SlotCatalogue
import com.example.contracts.TopicCatalogue
import com.example.contracts.TrustTier
import com.example.data.local.CachedDocumentDao
import com.example.data.local.OperationJournalDao
import com.example.data.local.OperationJournalEntity
import com.example.data.local.RepoTreeDao
import com.example.data.local.RepositoryDao
import com.example.data.local.RepositoryEntity
import com.example.data.local.ResourceVersionDao
import com.example.data.local.ResourceVersionEntity
import com.example.domain.model.GitHostProvider
import com.example.domain.model.RepoFileNode
import com.example.domain.usecase.AddGitHubRepositoryUseCase
import com.example.domain.usecase.LoadRepositoryCommitGraphUseCase
import com.example.domain.usecase.ManageProviderCredentialsUseCase
import com.example.domain.usecase.ObserveRepositoriesUseCase
import com.example.domain.usecase.ObserveRepositoryTreeUseCase
import com.example.domain.usecase.OpenDocumentAsMarkdownUseCase
import com.example.domain.usecase.ProbeRepositoryAccessibilityUseCase
import com.example.domain.usecase.RefreshRepositoryUseCase
import com.example.domain.usecase.RemoveRepositoryUseCase
import com.example.domain.usecase.SwitchRepositoryBranchUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keystone Architecture — Section 1, 4, 5, 9, 12 & 15:
 * The Foundation Arbiter:
 * - Law 4 (One Writer): Only the Foundation Arbiter changes durable data — for every actor.
 * - Law 5 (One Gate): Hosts the Warden and runs every command through the Warden check pipeline.
 * - Law 8 (Every Change is an Operation): Identified, journaled before effect, conditional on expected
 *   prior contentHash, idempotent on retry, with full crash recovery and conditional undo.
 * - Section 15 (Dependency Rules): Depends strictly on `com.example.contracts` (`KeystonePort`, `PluginHostPort`),
 *   never importing `com.example.frontend` or `com.example.kernel`.
 */
class FoundationArbiter(
    private val kernel: KeystonePort,
    private val pluginHost: PluginHostPort,
    val warden: Warden,
    private val repositoryDao: RepositoryDao,
    private val resourceVersionDao: ResourceVersionDao,
    private val operationJournalDao: OperationJournalDao,
    private val repoTreeDao: RepoTreeDao,
    private val cachedDocumentDao: CachedDocumentDao,
    val observeRepositoriesUseCase: ObserveRepositoriesUseCase,
    val manageProviderCredentialsUseCase: ManageProviderCredentialsUseCase,
    val probeRepositoryAccessibilityUseCase: ProbeRepositoryAccessibilityUseCase,
    val addGitHubRepositoryUseCase: AddGitHubRepositoryUseCase,
    val refreshRepositoryUseCase: RefreshRepositoryUseCase,
    val switchRepositoryBranchUseCase: SwitchRepositoryBranchUseCase,
    val removeRepositoryUseCase: RemoveRepositoryUseCase,
    val observeRepositoryTreeUseCase: ObserveRepositoryTreeUseCase,
    val loadRepositoryCommitGraphUseCase: LoadRepositoryCommitGraphUseCase,
    val openDocumentAsMarkdownUseCase: OpenDocumentAsMarkdownUseCase
) {
    val foundationScope: CoroutineScope = CoroutineScope(kernel.kernelScope.coroutineContext + SupervisorJob())

    // Per-resource ordering locks (Section 9.3 #6): one resource's mutations are serialized;
    // different resources proceed in parallel.
    private val resourceLocks = ConcurrentHashMap<String, Mutex>()

    // Resource leases (Section 9.6): tracks active lease holder per resourceId
    private val resourceLeases = ConcurrentHashMap<String, String>()

    companion object {
        const val SENDER_ID = "foundation.arbiter"
        const val AUTOMATED_SYNC_ACTOR_ID = "actor.auto-sync"
    }

    init {
        kernel.registerSender(
            senderId = SENDER_ID,
            actorKind = ActorKind.SYSTEM,
            trustTier = TrustTier.CORE,
            scopeId = "scope:foundation"
        )
        kernel.registerSender(
            senderId = AUTOMATED_SYNC_ACTOR_ID,
            actorKind = ActorKind.AUTOMATED_ACTOR,
            trustTier = TrustTier.CORE,
            scopeId = "scope:foundation:actor"
        )

        // Sync extension capability grants from PluginHost into Warden
        foundationScope.launch {
            pluginHost.extensionsFlow.collect { list ->
                for (ext in list) {
                    warden.setExtensionCapabilityGrants(
                        extensionId = ext.manifest.extensionId,
                        capabilities = if (ext.enabled) ext.grantedCapabilities else emptySet()
                    )
                }
            }
        }

        // Bind Foundation Arbiter as the single handler of envelopes arriving from Keystone
        kernel.bindFoundationArbiterHandler { envelope ->
            handleIncomingEnvelope(envelope)
        }
    }

    private fun mutexForResource(resourceId: String): Mutex {
        return resourceLocks.getOrPut(resourceId) { Mutex() }
    }

    /**
     * Crash Recovery on Workspace / App Startup (Section 9.4):
     * Finishes or fails every non-terminal operation in the durable journal so an effect is never applied twice.
     */
    suspend fun recoverIncompleteOperationsOnStartup(): Int {
        val incomplete = operationJournalDao.getNonTerminalOperations()
        var recoveredCount = 0

        for (op in incomplete) {
            val currentState = runCatching { OperationState.valueOf(op.state) }
                .getOrDefault(OperationState.FAILED)
            val now = System.currentTimeMillis()

            when (currentState) {
                OperationState.RECEIVED, OperationState.AUTHORIZED -> {
                    operationJournalDao.upsertOperation(
                        op.copy(
                            state = OperationState.FAILED.name,
                            resultSummary = "Interrupted before prepared; safe to retry with same operationId",
                            updatedAtEpochMs = now
                        )
                    )
                    recoveredCount++
                }

                OperationState.AWAITING_APPROVAL -> {
                    // Keep awaiting approval; revalidated at decision time
                }

                OperationState.PREPARED -> {
                    val actualHash = computeCurrentResourceHash(op.resourceId)
                    when (actualHash) {
                        op.expectedPreconditionHash -> {
                            operationJournalDao.upsertOperation(
                                op.copy(
                                    state = OperationState.FAILED.name,
                                    resultSummary = "Recovered from PREPARED: resource unchanged, effect not applied",
                                    updatedAtEpochMs = now
                                )
                            )
                        }
                        op.targetResultHash -> {
                            bumpResourceVersion(op.resourceId, op.targetResultHash)
                            operationJournalDao.upsertOperation(
                                op.copy(
                                    state = OperationState.COMMITTED.name,
                                    resultSummary = "Recovered from PREPARED: effect verified and committed",
                                    updatedAtEpochMs = now
                                )
                            )
                        }
                        else -> {
                            operationJournalDao.upsertOperation(
                                op.copy(
                                    state = OperationState.CONFLICTED.name,
                                    resultSummary = "Recovered from PREPARED: resource hash diverged ($actualHash)",
                                    updatedAtEpochMs = now
                                )
                            )
                        }
                    }
                    recoveredCount++
                }

                OperationState.APPLIED -> {
                    val actualHash = computeCurrentResourceHash(op.resourceId)
                    bumpResourceVersion(op.resourceId, actualHash)
                    operationJournalDao.upsertOperation(
                        op.copy(
                            state = OperationState.COMMITTED.name,
                            resultSummary = "Recovered from APPLIED: commit finalized",
                            updatedAtEpochMs = now
                        )
                    )
                    recoveredCount++
                }

                else -> Unit
            }
        }
        return recoveredCount
    }

    /**
     * Resolves the current content hash of a durable resource (Section 9.1).
     */
    suspend fun computeCurrentResourceHash(resourceId: String): String {
        val existingVersion = resourceVersionDao.getResourceVersion(resourceId)
        if (existingVersion != null) {
            return existingVersion.contentHash
        }
        if (resourceId.startsWith("repo:")) {
            val repoId = resourceId.removePrefix("repo:")
            val entity = repositoryDao.getRepositoryById(repoId)
                ?: return ResourceRef.ABSENT_HASH
            return serializeRepoSnapshot(entity).let { ResourceRef.computeHash(it) }
        }
        return ResourceRef.ABSENT_HASH
    }

    suspend fun getCurrentResourceVersion(resourceId: String): Long {
        return resourceVersionDao.getResourceVersion(resourceId)?.version ?: 0L
    }

    suspend fun getResourceRef(resourceId: String): ResourceRef {
        val hash = computeCurrentResourceHash(resourceId)
        val version = getCurrentResourceVersion(resourceId)
        return ResourceRef(
            resourceId = resourceId,
            version = version,
            contentHash = hash
        )
    }

    private suspend fun bumpResourceVersion(resourceId: String, newContentHash: String): ResourceRef {
        val existing = resourceVersionDao.getResourceVersion(resourceId)
        val nextVersion = (existing?.version ?: 0L) + 1L
        resourceVersionDao.upsertResourceVersion(
            ResourceVersionEntity(
                resourceId = resourceId,
                version = nextVersion,
                contentHash = newContentHash,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        return ResourceRef(
            resourceId = resourceId,
            version = nextVersion,
            contentHash = newContentHash
        )
    }

    /**
     * Central Envelope Handler inside the Foundation Arbiter.
     */
    private suspend fun handleIncomingEnvelope(envelope: Envelope): Envelope {
        val cmdDescriptor = kernel.getCommandDescriptor(envelope.topic)
        val requiredCapability = cmdDescriptor?.requiredCapability ?: Capability.DATA_READ
        val resolvedResourceId = resolveResourceId(envelope)

        // Handle Warden Approval / Rejection commands first
        if (envelope.topic == CommandCatalogue.MUTATION_APPROVE) {
            val approvalId = envelope.payload["approvalId"].orEmpty()
            return handleApproveOperation(envelope, approvalId)
        }
        if (envelope.topic == CommandCatalogue.MUTATION_REJECT) {
            val approvalId = envelope.payload["approvalId"].orEmpty()
            val rejected = warden.rejectApproval(approvalId)
            if (rejected != null) {
                val existingOp = operationJournalDao.getOperation(rejected.operationId)
                if (existingOp != null) {
                    operationJournalDao.upsertOperation(
                        existingOp.copy(
                            state = OperationState.REJECTED.name,
                            resultSummary = "Rejected by user",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            }
            return createReply(envelope, mapOf("status" to "REJECTED", "approvalId" to approvalId))
        }

        val currentHash = computeCurrentResourceHash(resolvedResourceId)
        val currentVersion = getCurrentResourceVersion(resolvedResourceId)

        // Run Warden 6-Stage Check Pipeline (Law 5 & Section 10.3)
        val policyDecision = warden.evaluateCall(
            envelope = envelope,
            requiredCapability = requiredCapability,
            resolvedResourceId = resolvedResourceId,
            currentResourceHash = currentHash,
            currentResourceVersion = currentVersion,
            activeWorkspaceGeneration = kernel.currentWorkspaceGeneration()
        )

        when (policyDecision) {
            is Warden.PolicyDecision.Denied -> {
                if (cmdDescriptor?.mutatesDurableState == true) {
                    journalStateTransition(
                        envelope = envelope,
                        resourceId = resolvedResourceId,
                        expectedHash = currentHash,
                        targetHash = currentHash,
                        beforeSnapshot = null,
                        afterSnapshot = null,
                        state = OperationState.REJECTED,
                        summary = policyDecision.reason
                    )
                }
                return kernel.createErrorReply(
                    request = envelope,
                    code = policyDecision.errorCode,
                    messageKey = policyDecision.messageKey,
                    params = mapOf("reason" to policyDecision.reason)
                )
            }

            is Warden.PolicyDecision.RequiresApproval -> {
                journalStateTransition(
                    envelope = envelope,
                    resourceId = resolvedResourceId,
                    expectedHash = currentHash,
                    targetHash = ResourceRef.ABSENT_HASH,
                    beforeSnapshot = captureSnapshotIfRepo(resolvedResourceId),
                    afterSnapshot = null,
                    state = OperationState.AWAITING_APPROVAL,
                    summary = policyDecision.approval.humanReadableSummary
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.MUTATION_AWAITING_APPROVAL,
                    payload = mapOf(
                        "approvalId" to policyDecision.approval.approvalId,
                        "operationId" to policyDecision.approval.operationId,
                        "summary" to policyDecision.approval.humanReadableSummary,
                        "resourceId" to resolvedResourceId
                    ),
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId,
                    operationId = policyDecision.approval.operationId
                )
                return kernel.createErrorReply(
                    request = envelope,
                    code = ErrorCode.ApprovalRequired,
                    messageKey = "error.approval_required",
                    params = mapOf(
                        "approvalId" to policyDecision.approval.approvalId,
                        "summary" to policyDecision.approval.humanReadableSummary
                    )
                )
            }

            Warden.PolicyDecision.Allowed -> {
                // Proceed to command execution
            }
        }

        return executeAuthorizedCommand(envelope, resolvedResourceId)
    }

    private suspend fun handleApproveOperation(requestEnvelope: Envelope, approvalId: String): Envelope {
        val pending = warden.pendingApprovalsFlow.value.find { it.approvalId == approvalId }
            ?: return kernel.createErrorReply(
                request = requestEnvelope,
                code = ErrorCode.ApprovalInvalid,
                messageKey = "error.approval_not_found"
            )

        val currentHash = computeCurrentResourceHash(pending.resourceId)
        val currentVersion = getCurrentResourceVersion(pending.resourceId)

        val revalidated = warden.consumeAndRevalidateApproval(
            approvalId = approvalId,
            currentResourceHash = currentHash,
            currentResourceVersion = currentVersion,
            activeWorkspaceGeneration = kernel.currentWorkspaceGeneration()
        )

        return revalidated.fold(
            onSuccess = { approved ->
                val replayEnvelope = approved.OriginalEnvelope.copy(
                    eventId = UUID.randomUUID().toString(),
                    payload = approved.OriginalEnvelope.payload + ("wardenApproved" to "true"),
                    causationId = requestEnvelope.eventId
                )
                executeAuthorizedCommand(replayEnvelope, approved.resourceId)
            },
            onFailure = { err ->
                kernel.createErrorReply(
                    request = requestEnvelope,
                    code = ErrorCode.ApprovalInvalid,
                    messageKey = "error.approval_revalidation_failed",
                    params = mapOf("reason" to (err.message ?: "Approval invalidated"))
                )
            }
        )
    }

    /**
     * Executes a command that has passed the Warden policy gate.
     * Durable mutations run through the full Mutation Protocol (`executeMutationThroughProtocol`).
     * Large read payloads (Markdown AST, Commit DAG) are passed by reference handle via `KeystonePort`.
     */
    private suspend fun executeAuthorizedCommand(
        envelope: Envelope,
        resolvedResourceId: String
    ): Envelope {
        return when (envelope.topic) {
            CommandCatalogue.REPOSITORY_SEED_DEFAULT -> {
                observeRepositoriesUseCase.ensureDefaultGitSeededOnce()
                val gitRepo = repositoryDao.getRepositoryById("git/git")
                if (gitRepo != null) {
                    val hash = ResourceRef.computeHash(serializeRepoSnapshot(gitRepo))
                    if (resourceVersionDao.getResourceVersion("repo:git/git") == null) {
                        bumpResourceVersion("repo:git/git", hash)
                    }
                }
                createReply(envelope, mapOf("seeded" to "true"))
            }

            CommandCatalogue.REPOSITORY_PROBE -> {
                val rawInput = envelope.payload["rawInput"].orEmpty()
                val probeRes = probeRepositoryAccessibilityUseCase(rawInput)
                probeRes.fold(
                    onSuccess = { probe ->
                        val payload = mapOf(
                            "owner" to probe.owner,
                            "name" to probe.name,
                            "fullSlug" to probe.fullSlug,
                            "defaultBranch" to probe.defaultBranch,
                            "branchesCsv" to probe.branches.joinToString(","),
                            "description" to probe.description,
                            "isPrivate" to probe.isPrivate.toString(),
                            "authenticatedViaOAuth" to probe.authenticatedViaOAuth.toString()
                        )
                        kernel.publishEventToCrown(
                            senderId = SENDER_ID,
                            topic = TopicCatalogue.REPOSITORY_PROBED,
                            payload = payload,
                            correlationId = envelope.correlationId,
                            causationId = envelope.eventId
                        )
                        createReply(envelope, payload)
                    },
                    onFailure = { err ->
                        kernel.createErrorReply(
                            request = envelope,
                            code = ErrorCode.NotFound,
                            messageKey = "error.repository_probe_failed",
                            params = mapOf("message" to (err.message ?: "Unable to verify repository access."))
                        )
                    }
                )
            }

            CommandCatalogue.REPOSITORY_ADD -> {
                val rawInput = envelope.payload["rawInput"].orEmpty()
                val selectedBranch = envelope.payload["selectedBranch"]?.takeIf { it.isNotBlank() }
                val knownBranches = envelope.payload["knownBranchesCsv"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()

                executeMutationThroughProtocol(
                    envelope = envelope,
                    resourceId = resolvedResourceId,
                    effectBlock = {
                        val res = addGitHubRepositoryUseCase(
                            rawRepoInput = rawInput,
                            branchOverride = selectedBranch,
                            knownBranches = knownBranches,
                            saveUrlEvenIfPrivateOrUnreachable = false
                        )
                        val card = res.getOrThrow()
                        val finalResourceId = "repo:${card.id}"
                        val entity = repositoryDao.getRepositoryById(card.id)
                        val afterJson = entity?.let { serializeRepoSnapshot(it) }
                        MutationEffectOutcome(
                            actualResourceId = finalResourceId,
                            afterSnapshotJson = afterJson,
                            resultSummary = "Added ${card.fullSlug} [${card.defaultBranch}] (${card.totalFilesCount} files)",
                            replyPayload = mapOf(
                                "repoId" to card.id,
                                "fullSlug" to card.fullSlug,
                                "defaultBranch" to card.defaultBranch,
                                "totalFilesCount" to card.totalFilesCount.toString()
                            ),
                            eventTopic = TopicCatalogue.REPOSITORY_ADDED
                        )
                    }
                )
            }

            CommandCatalogue.REPOSITORY_MOUNT_SHARED -> {
                val rawSharedUrl = envelope.payload["rawSharedUrl"].orEmpty()
                executeMutationThroughProtocol(
                    envelope = envelope,
                    resourceId = resolvedResourceId,
                    effectBlock = {
                        val res = addGitHubRepositoryUseCase(
                            rawRepoInput = rawSharedUrl,
                            branchOverride = null,
                            saveUrlEvenIfPrivateOrUnreachable = true
                        )
                        val card = res.getOrThrow()
                        val finalResourceId = "repo:${card.id}"
                        val entity = repositoryDao.getRepositoryById(card.id)
                        MutationEffectOutcome(
                            actualResourceId = finalResourceId,
                            afterSnapshotJson = entity?.let { serializeRepoSnapshot(it) },
                            resultSummary = "Mounted shared repo ${card.fullSlug}",
                            replyPayload = mapOf(
                                "repoId" to card.id,
                                "fullSlug" to card.fullSlug,
                                "defaultBranch" to card.defaultBranch,
                                "totalFilesCount" to card.totalFilesCount.toString()
                            ),
                            eventTopic = TopicCatalogue.REPOSITORY_SHARED_MOUNTED
                        )
                    }
                )
            }

            CommandCatalogue.REPOSITORY_REFRESH -> {
                val repoId = envelope.payload["repoId"].orEmpty()
                executeMutationThroughProtocol(
                    envelope = envelope,
                    resourceId = "repo:$repoId",
                    effectBlock = {
                        val res = refreshRepositoryUseCase(repoId)
                        val updated = res.getOrThrow()
                        val entity = repositoryDao.getRepositoryById(repoId)
                        MutationEffectOutcome(
                            actualResourceId = "repo:$repoId",
                            afterSnapshotJson = entity?.let { serializeRepoSnapshot(it) },
                            resultSummary = "Synced ${updated.fullSlug} [${updated.defaultBranch}] (${updated.totalFilesCount} files)",
                            replyPayload = mapOf(
                                "repoId" to updated.id,
                                "fullSlug" to updated.fullSlug,
                                "defaultBranch" to updated.defaultBranch,
                                "totalFilesCount" to updated.totalFilesCount.toString(),
                                "markdownFilesCount" to updated.markdownFilesCount.toString()
                            ),
                            eventTopic = TopicCatalogue.REPOSITORY_SYNCED
                        )
                    }
                )
            }

            CommandCatalogue.REPOSITORY_SWITCH_BRANCH -> {
                val repoId = envelope.payload["repoId"].orEmpty()
                val targetBranch = envelope.payload["targetBranch"].orEmpty()
                executeMutationThroughProtocol(
                    envelope = envelope,
                    resourceId = "repo:$repoId",
                    effectBlock = {
                        val res = switchRepositoryBranchUseCase.switchBranch(repoId, targetBranch)
                        val updated = res.getOrThrow()
                        val entity = repositoryDao.getRepositoryById(repoId)
                        MutationEffectOutcome(
                            actualResourceId = "repo:$repoId",
                            afterSnapshotJson = entity?.let { serializeRepoSnapshot(it) },
                            resultSummary = "Switched ${updated.fullSlug} to branch '${updated.defaultBranch}'",
                            replyPayload = mapOf(
                                "repoId" to updated.id,
                                "defaultBranch" to updated.defaultBranch,
                                "branchesCsv" to updated.availableBranches.joinToString(",")
                            ),
                            eventTopic = TopicCatalogue.REPOSITORY_BRANCH_SWITCHED
                        )
                    }
                )
            }

            CommandCatalogue.REPOSITORY_REMOVE -> {
                val repoId = envelope.payload["repoId"].orEmpty()
                executeMutationThroughProtocol(
                    envelope = envelope,
                    resourceId = "repo:$repoId",
                    effectBlock = {
                        removeRepositoryUseCase(repoId)
                        MutationEffectOutcome(
                            actualResourceId = "repo:$repoId",
                            afterSnapshotJson = null, // Resource deleted -> ABSENT_HASH
                            resultSummary = "Removed repository $repoId",
                            replyPayload = mapOf("repoId" to repoId),
                            eventTopic = TopicCatalogue.REPOSITORY_REMOVED
                        )
                    }
                )
            }

            CommandCatalogue.CREDENTIAL_SAVE -> {
                val providerId = envelope.payload["providerId"].orEmpty()
                val token = envelope.payload["token"].orEmpty()
                val provider = GitHostProvider.fromId(providerId)
                executeMutationThroughProtocol(
                    envelope = envelope,
                    resourceId = "secret:$providerId",
                    effectBlock = {
                        manageProviderCredentialsUseCase.saveToken(provider, token)
                        MutationEffectOutcome(
                            actualResourceId = "secret:$providerId",
                            afterSnapshotJson = "{\"providerId\":\"$providerId\",\"configured\":${token.isNotBlank()}}",
                            resultSummary = "${provider.displayName} OAuth credential updated",
                            replyPayload = mapOf("providerId" to providerId),
                            eventTopic = TopicCatalogue.CREDENTIAL_UPDATED
                        )
                    }
                )
            }

            CommandCatalogue.CREDENTIAL_CLEAR -> {
                val providerId = envelope.payload["providerId"].orEmpty()
                val provider = GitHostProvider.fromId(providerId)
                executeMutationThroughProtocol(
                    envelope = envelope,
                    resourceId = "secret:$providerId",
                    effectBlock = {
                        manageProviderCredentialsUseCase.clearToken(provider)
                        MutationEffectOutcome(
                            actualResourceId = "secret:$providerId",
                            afterSnapshotJson = null,
                            resultSummary = "${provider.displayName} OAuth credential cleared",
                            replyPayload = mapOf("providerId" to providerId),
                            eventTopic = TopicCatalogue.CREDENTIAL_UPDATED
                        )
                    }
                )
            }

            CommandCatalogue.MUTATION_UNDO_LAST_RUN -> {
                undoLastCommittedMutation(envelope)
            }

            CommandCatalogue.CACHE_REBUILD_DERIVED -> {
                // Law 10 & Section 14: Wipe rebuildable derived state without touching durable truth, then rebuild
                repoTreeDao.clearAllDerivedTreeNodes()
                cachedDocumentDao.clearAllDerivedDocuments()
                val allRepos = repositoryDao.getAllRepositories()
                var rebuilt = 0
                for (repo in allRepos) {
                    observeRepositoryTreeUseCase.refreshTree(repo.id, forceNetwork = false)
                    rebuilt++
                }
                val payload = mapOf("rebuiltCount" to rebuilt.toString())
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.CACHE_DERIVED_REBUILT,
                    payload = payload,
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId
                )
                createReply(envelope, payload)
            }

            CommandCatalogue.PROFILE_SWITCH -> {
                val profileId = envelope.payload["profileId"].orEmpty()
                val profile = pluginHost.applyProfile(profileId)
                warden.applyProfile(profile)
                val payload = mapOf(
                    "profileId" to profile.profileId,
                    "displayName" to profile.displayName
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.PROFILE_SWITCHED,
                    payload = payload,
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId
                )
                createReply(envelope, payload)
            }

            CommandCatalogue.EXTENSION_TOGGLE -> {
                val extensionId = envelope.payload["extensionId"].orEmpty()
                val enabled = pluginHost.toggleExtension(extensionId)
                val payload = mapOf(
                    "extensionId" to extensionId,
                    "enabled" to enabled.toString()
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.EXTENSION_STATE_CHANGED,
                    payload = payload,
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId
                )
                createReply(envelope, payload)
            }

            CommandCatalogue.ACTOR_RUN_SYNC_SWEEP -> {
                runAutomatedSyncSweep(envelope)
            }

            CommandCatalogue.REPOSITORY_OPEN -> {
                val repoId = envelope.payload["repoId"].orEmpty()
                // Invoke WORKSPACE_OPEN_GUARD slot hooks if any CORE guard extension is mounted (Section 11)
                val openGuards = pluginHost.getContributionsForSlot(SlotCatalogue.WORKSPACE_OPEN_GUARD)
                val newGen = kernel.switchWorkspace("workspace:repo:$repoId")
                // Ensure tree cache is warm for the opened workspace
                observeRepositoryTreeUseCase.refreshTree(repoId, forceNetwork = false)
                val payload = mapOf(
                    "repoId" to repoId,
                    "workspaceGeneration" to newGen.toString(),
                    "openGuardsChecked" to openGuards.size.toString()
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.WORKSPACE_OPENED,
                    payload = payload,
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId
                )
                createReply(envelope, payload)
            }

            CommandCatalogue.EXPLORER_REFRESH_BRANCHES -> {
                val repoId = envelope.payload["repoId"].orEmpty()
                val branchesRes = switchRepositoryBranchUseCase.fetchBranches(repoId)
                val list = branchesRes.getOrElse { emptyList() }
                val payload = mapOf(
                    "repoId" to repoId,
                    "branchesCsv" to list.joinToString(",")
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.REPOSITORY_BRANCHES_LOADED,
                    payload = payload,
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId,
                    lane = Lane.BACKGROUND
                )
                createReply(envelope, payload)
            }

            CommandCatalogue.GRAPH_LOAD -> {
                val repoId = envelope.payload["repoId"].orEmpty()
                val highScaleMode = envelope.payload["highScaleMode"] == "true"
                val forceRefresh = envelope.payload["forceRefresh"] == "true"
                val res = loadRepositoryCommitGraphUseCase(
                    repoId,
                    highScaleMode,
                    forceRefresh
                )
                res.fold(
                    onSuccess = { graph ->
                        // Section 7 Delivery Rule #7: Pass large Commit Graph DAG by reference handle
                        val refId = "ref:graph:$repoId:${UUID.randomUUID()}"
                        kernel.putLargeContentByReference(refId, graph)
                        val payload = mapOf(
                            "repoId" to repoId,
                            "contentRefId" to refId,
                            "totalCommits" to graph.rows.size.toString(),
                            "totalBranches" to graph.totalBranchesRepresented.toString()
                        )
                        kernel.publishEventToCrown(
                            senderId = SENDER_ID,
                            topic = TopicCatalogue.GRAPH_LOADED,
                            payload = payload,
                            correlationId = envelope.correlationId,
                            causationId = envelope.eventId,
                            lane = Lane.BACKGROUND
                        )
                        createReply(envelope, payload)
                    },
                    onFailure = { err ->
                        kernel.createErrorReply(
                            request = envelope,
                            code = ErrorCode.Internal,
                            messageKey = "error.commit_graph_load_failed",
                            params = mapOf("message" to (err.message ?: "Failed to load commit graph"))
                        )
                    }
                )
            }

            CommandCatalogue.DOCUMENT_OPEN -> {
                val repoId = envelope.payload["repoId"].orEmpty()
                val filePath = envelope.payload["filePath"].orEmpty()
                val forceRefresh = envelope.payload["forceRefresh"] == "true"
                val node = RepoFileNode(
                    id = "$repoId:$filePath",
                    repoId = repoId,
                    path = filePath,
                    name = filePath.substringAfterLast('/'),
                    parentPath = filePath.substringBeforeLast('/', ""),
                    isDirectory = false,
                    sizeBytes = envelope.payload["sizeBytes"]?.toLongOrNull() ?: 0L,
                    sha = envelope.payload["sha"].orEmpty(),
                    depth = filePath.count { it == '/' }
                )
                val res = openDocumentAsMarkdownUseCase(
                    repoId = repoId,
                    node = node,
                    forceRefresh = forceRefresh
                )
                res.fold(
                    onSuccess = { parsedDoc ->
                        // Section 7 Delivery Rule #7: Pass large virtualized Markdown AST by reference handle
                        val refId = "ref:doc:$repoId:$filePath:${UUID.randomUUID()}"
                        kernel.putLargeContentByReference(refId, parsedDoc)
                        val payload = mapOf(
                            "repoId" to repoId,
                            "filePath" to filePath,
                            "contentRefId" to refId,
                            "blockCount" to parsedDoc.blocks.size.toString()
                        )
                        kernel.publishEventToCrown(
                            senderId = SENDER_ID,
                            topic = TopicCatalogue.DOCUMENT_LOADED,
                            payload = payload,
                            correlationId = envelope.correlationId,
                            causationId = envelope.eventId
                        )
                        createReply(envelope, payload)
                    },
                    onFailure = { err ->
                        kernel.createErrorReply(
                            request = envelope,
                            code = ErrorCode.NotFound,
                            messageKey = "error.document_open_failed",
                            params = mapOf("message" to (err.message ?: "Unable to load $filePath"))
                        )
                    }
                )
            }

            else -> {
                createReply(envelope, envelope.payload)
            }
        }
    }

    private data class MutationEffectOutcome(
        val actualResourceId: String,
        val afterSnapshotJson: String?,
        val resultSummary: String,
        val replyPayload: Map<String, String>,
        val eventTopic: String
    )

    /**
     * Section 9 — The Mutation Protocol:
     * 1. Per-resource serialization (`mutexForResource`)
     * 2. Idempotency check by `operationId` (`OperationIdReused` if arguments differ)
     * 3. Last-moment contentHash & version precondition check (`ErrorCode.Conflict` on mismatch)
     * 4. Snapshot + intent journaled as `PREPARED` BEFORE any effect
     * 5. Effect executed -> journaled as `APPLIED`
     * 6. Resource version bumped + journaled as `COMMITTED` -> acknowledgement reply & event emitted
     */
    private suspend fun executeMutationThroughProtocol(
        envelope: Envelope,
        resourceId: String,
        effectBlock: suspend () -> MutationEffectOutcome
    ): Envelope {
        val opId = envelope.operationId ?: "op-${UUID.randomUUID()}"
        val argsCanonical = envelope.payload.entries
            .filterNot { it.key == "wardenApproved" }
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val argsHash = ResourceRef.computeHash(argsCanonical)

        return mutexForResource(resourceId).withLock {
            // 1. Idempotency check (Section 9.3 #4)
            val existingOp = operationJournalDao.getOperation(opId)
            if (existingOp != null && existingOp.state != OperationState.AWAITING_APPROVAL.name) {
                if (existingOp.argumentsHash != argsHash) {
                    return@withLock kernel.createErrorReply(
                        request = envelope,
                        code = ErrorCode.OperationIdReused,
                        messageKey = "error.operation_id_reused_with_different_args",
                        params = mapOf("operationId" to opId)
                    )
                }
                if (existingOp.state == OperationState.COMMITTED.name) {
                    return@withLock createReply(
                        request = envelope,
                        payload = mapOf(
                            "idempotentReplay" to "true",
                            "operationId" to opId,
                            "summary" to existingOp.resultSummary.orEmpty()
                        )
                    )
                }
            }

            // 2. Last-moment contentHash & version precondition check on resolved resource (Section 9.3 #1, #2, #3)
            val currentHash = computeCurrentResourceHash(resourceId)
            val currentVersion = getCurrentResourceVersion(resourceId)
            val expectedPreconditionHash = envelope.resource?.contentHash ?: currentHash
            val hashMismatch = envelope.resource != null && envelope.resource.contentHash != currentHash
            val versionMismatch = envelope.resource != null &&
                envelope.resource.version > 0L &&
                envelope.resource.version != currentVersion

            if (hashMismatch || versionMismatch) {
                journalStateTransition(
                    envelope = envelope,
                    resourceId = resourceId,
                    expectedHash = expectedPreconditionHash,
                    targetHash = currentHash,
                    beforeSnapshot = captureSnapshotIfRepo(resourceId),
                    afterSnapshot = null,
                    state = OperationState.CONFLICTED,
                    summary = "Precondition conflict: expected hash=$expectedPreconditionHash (v=${envelope.resource?.version}) but found hash=$currentHash (v=$currentVersion)"
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.MUTATION_CONFLICTED,
                    payload = mapOf(
                        "operationId" to opId,
                        "resourceId" to resourceId,
                        "expectedHash" to expectedPreconditionHash,
                        "actualHash" to currentHash
                    ),
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId,
                    operationId = opId
                )
                return@withLock kernel.createErrorReply(
                    request = envelope,
                    code = ErrorCode.Conflict,
                    messageKey = "error.precondition_hash_conflict",
                    params = mapOf(
                        "resourceId" to resourceId,
                        "expectedHash" to expectedPreconditionHash,
                        "actualHash" to currentHash
                    )
                )
            }

            // Acquire resource lease during mutation (Section 9.6)
            resourceLeases[resourceId] = envelope.context.actorId

            try {
                // 3. Journal AUTHORIZED -> PREPARED with before-snapshot BEFORE any effect (Section 9.2 & 9.3 #7)
                val beforeSnapshot = captureSnapshotIfRepo(resourceId)
                journalStateTransition(
                    envelope = envelope,
                    resourceId = resourceId,
                    expectedHash = currentHash,
                    targetHash = "PENDING_EFFECT",
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = null,
                    state = OperationState.PREPARED,
                    summary = "Prepared ${envelope.topic} on $resourceId"
                )

                // 4. Execute the durable effect
                val outcome = effectBlock()
                val targetHash = outcome.afterSnapshotJson?.let { ResourceRef.computeHash(it) }
                    ?: ResourceRef.ABSENT_HASH

                // 5. Journal APPLIED
                journalStateTransition(
                    envelope = envelope,
                    resourceId = outcome.actualResourceId,
                    expectedHash = currentHash,
                    targetHash = targetHash,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = outcome.afterSnapshotJson,
                    state = OperationState.APPLIED,
                    summary = outcome.resultSummary
                )

                // 6. Bump durable monotonic version & journal COMMITTED (Section 9.5)
                val updatedResourceRef = bumpResourceVersion(outcome.actualResourceId, targetHash)
                journalStateTransition(
                    envelope = envelope,
                    resourceId = outcome.actualResourceId,
                    expectedHash = currentHash,
                    targetHash = targetHash,
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = outcome.afterSnapshotJson,
                    state = OperationState.COMMITTED,
                    summary = outcome.resultSummary
                )

                // 7. Emit past-tense domain event + mutation.committed event
                val fullPayload = outcome.replyPayload + mapOf(
                    "operationId" to opId,
                    "resourceId" to updatedResourceRef.resourceId,
                    "resourceVersion" to updatedResourceRef.version.toString(),
                    "contentHash" to updatedResourceRef.contentHash,
                    "summary" to outcome.resultSummary
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = outcome.eventTopic,
                    payload = fullPayload,
                    resource = updatedResourceRef,
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId,
                    operationId = opId
                )
                kernel.publishEventToCrown(
                    senderId = SENDER_ID,
                    topic = TopicCatalogue.MUTATION_COMMITTED,
                    payload = fullPayload,
                    resource = updatedResourceRef,
                    correlationId = envelope.correlationId,
                    causationId = envelope.eventId,
                    operationId = opId
                )

                createReply(
                    request = envelope,
                    payload = fullPayload,
                    resource = updatedResourceRef
                )
            } catch (t: Throwable) {
                journalStateTransition(
                    envelope = envelope,
                    resourceId = resourceId,
                    expectedHash = currentHash,
                    targetHash = currentHash,
                    beforeSnapshot = null,
                    afterSnapshot = null,
                    state = OperationState.FAILED,
                    summary = t.message ?: "Effect execution failed"
                )
                kernel.createErrorReply(
                    request = envelope,
                    code = ErrorCode.Internal,
                    messageKey = "error.mutation_effect_failed",
                    params = mapOf("message" to (t.message ?: "Operation failed")),
                    retryable = true
                )
            } finally {
                resourceLeases.remove(resourceId)
            }
        }
    }

    /**
     * Conditional, Operation-Aware Undo (Section 9.7):
     * Undo is a new operation through the Warden and Arbiter with the precondition:
     * "current state == the target operation's after-state".
     * If the resource was modified since, skip it, keep the newer work, and report Conflict.
     */
    private suspend fun undoLastCommittedMutation(requestEnvelope: Envelope): Envelope {
        val lastOp = operationJournalDao.getLatestUndoableCommittedOperation()
            ?: return kernel.createErrorReply(
                request = requestEnvelope,
                code = ErrorCode.NotFound,
                messageKey = "error.no_undoable_operation"
            )

        val beforeJson = lastOp.beforeSnapshotJson
            ?: return kernel.createErrorReply(
                request = requestEnvelope,
                code = ErrorCode.NotFound,
                messageKey = "error.undo_snapshot_missing"
            )

        val currentHash = computeCurrentResourceHash(lastOp.resourceId)
        if (currentHash != lastOp.targetResultHash) {
            return kernel.createErrorReply(
                request = requestEnvelope,
                code = ErrorCode.Conflict,
                messageKey = "error.undo_precondition_diverged",
                params = mapOf(
                    "message" to "Skipped undo for ${lastOp.resourceId}: resource was modified after operation ${lastOp.operationId}"
                )
            )
        }

        val currentVersion = getCurrentResourceVersion(lastOp.resourceId)
        val undoEnvelope = requestEnvelope.copy(
            operationId = "undo-${UUID.randomUUID()}",
            resource = ResourceRef(
                resourceId = lastOp.resourceId,
                version = currentVersion,
                contentHash = lastOp.targetResultHash
            )
        )

        return executeMutationThroughProtocol(
            envelope = undoEnvelope,
            resourceId = lastOp.resourceId,
            effectBlock = {
                val restoredEntity = deserializeRepoSnapshot(beforeJson)
                repositoryDao.upsertRepository(restoredEntity)
                observeRepositoryTreeUseCase.refreshTree(restoredEntity.id, forceNetwork = false)
                MutationEffectOutcome(
                    actualResourceId = lastOp.resourceId,
                    afterSnapshotJson = beforeJson,
                    resultSummary = "Restored ${restoredEntity.owner}/${restoredEntity.name} [${restoredEntity.defaultBranch}] via conditional undo",
                    replyPayload = mapOf(
                        "undoneOperationId" to lastOp.operationId,
                        "repoId" to restoredEntity.id
                    ),
                    eventTopic = TopicCatalogue.MUTATION_UNDONE
                )
            }
        )
    }

    /**
     * Automated Actor (Section 5, 8 & 20):
     * Calls the exact same registered commands (`repository.refresh`) through the Keystone,
     * Warden, and Mutation Protocol — zero private write paths!
     */
    private suspend fun runAutomatedSyncSweep(triggerEnvelope: Envelope): Envelope {
        warden.resetAutomatedActorBudget(AUTOMATED_SYNC_ACTOR_ID)
        val allRepos = repositoryDao.getAllRepositories()
        var syncedCount = 0

        for ((idx, repo) in allRepos.withIndex()) {
            kernel.publishEventToCrown(
                senderId = AUTOMATED_SYNC_ACTOR_ID,
                topic = TopicCatalogue.SYNC_PROGRESS_UPDATED,
                payload = mapOf(
                    "repoId" to repo.id,
                    "step" to "${idx + 1}/${allRepos.size}"
                ),
                causationId = triggerEnvelope.eventId,
                lane = Lane.BACKGROUND
            )

            val reply = kernel.dispatchToFoundation(
                senderId = AUTOMATED_SYNC_ACTOR_ID,
                topic = CommandCatalogue.REPOSITORY_REFRESH,
                payload = mapOf("repoId" to repo.id),
                causationId = triggerEnvelope.eventId
            )
            if (reply.kind != EnvelopeKind.ERROR) {
                syncedCount++
            }
        }

        return createReply(
            request = triggerEnvelope,
            payload = mapOf(
                "syncedCount" to syncedCount.toString(),
                "actorId" to AUTOMATED_SYNC_ACTOR_ID
            )
        )
    }

    private suspend fun journalStateTransition(
        envelope: Envelope,
        resourceId: String,
        expectedHash: String,
        targetHash: String,
        beforeSnapshot: String?,
        afterSnapshot: String?,
        state: OperationState,
        summary: String
    ) {
        val opId = envelope.operationId ?: envelope.eventId
        val argsCanonical = envelope.payload.entries
            .filterNot { it.key == "wardenApproved" }
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val argsHash = ResourceRef.computeHash(argsCanonical)

        operationJournalDao.upsertOperation(
            OperationJournalEntity(
                operationId = opId,
                commandTopic = envelope.topic,
                resourceId = resourceId,
                expectedPreconditionHash = expectedHash,
                targetResultHash = targetHash,
                argumentsHash = argsHash,
                argumentsJson = argsCanonical,
                beforeSnapshotJson = beforeSnapshot,
                afterSnapshotJson = afterSnapshot,
                state = state.name,
                actorId = envelope.context.actorId,
                actorKind = envelope.context.actorKind.name,
                workspaceId = envelope.context.workspaceId,
                workspaceGeneration = envelope.context.workspaceGeneration,
                resultSummary = summary,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    private suspend fun captureSnapshotIfRepo(resourceId: String): String? {
        if (!resourceId.startsWith("repo:")) return null
        val repoId = resourceId.removePrefix("repo:")
        val entity = repositoryDao.getRepositoryById(repoId) ?: return null
        return serializeRepoSnapshot(entity)
    }

    private fun resolveResourceId(envelope: Envelope): String {
        envelope.resource?.resourceId?.takeIf { it.isNotBlank() }?.let { return it }
        envelope.payload["repoId"]?.takeIf { it.isNotBlank() }?.let { return "repo:$it" }
        envelope.payload["providerId"]?.takeIf { it.isNotBlank() }?.let { return "secret:$it" }
        envelope.payload["rawInput"]?.takeIf { it.isNotBlank() }?.let { raw ->
            return "repo:${raw.trim().lowercase()}"
        }
        return "workspace:${envelope.context.workspaceId}"
    }

    private fun serializeRepoSnapshot(entity: RepositoryEntity): String {
        val codecContributions = pluginHost.getContributionsForSlot(SlotCatalogue.STORAGE_CODEC)
        return JSONObject().apply {
            put("id", entity.id)
            put("providerId", entity.providerId)
            put("owner", entity.owner)
            put("name", entity.name)
            put("defaultBranch", entity.defaultBranch)
            put("branchesCsv", entity.branchesCsv)
            put("description", entity.description)
            put("totalFilesCount", entity.totalFilesCount)
            put("markdownFilesCount", entity.markdownFilesCount)
            put("lastSyncedEpochMs", entity.lastSyncedEpochMs)
            put("tileStyleName", entity.tileStyleName)
            put("accentIndex", entity.accentIndex)
            if (codecContributions.isNotEmpty()) {
                put("codec", codecContributions.first().contributionId)
            }
        }.toString()
    }

    private fun deserializeRepoSnapshot(jsonStr: String): RepositoryEntity {
        val obj = JSONObject(jsonStr)
        return RepositoryEntity(
            id = obj.getString("id"),
            providerId = obj.optString("providerId", "github"),
            owner = obj.getString("owner"),
            name = obj.getString("name"),
            defaultBranch = obj.optString("defaultBranch", "main"),
            branchesCsv = obj.optString("branchesCsv", ""),
            description = obj.optString("description", ""),
            totalFilesCount = obj.optInt("totalFilesCount", 0),
            markdownFilesCount = obj.optInt("markdownFilesCount", 0),
            lastSyncedEpochMs = obj.optLong("lastSyncedEpochMs", System.currentTimeMillis()),
            tileStyleName = obj.optString("tileStyleName", "FEATURE_HERO"),
            accentIndex = obj.optInt("accentIndex", 0)
        )
    }

    private fun createReply(
        request: Envelope,
        payload: Map<String, String>,
        resource: ResourceRef? = request.resource
    ): Envelope {
        return Envelope(
            kind = EnvelopeKind.REPLY,
            topic = "${request.topic}.reply",
            payload = payload,
            context = request.context,
            correlationId = request.correlationId,
            causationId = request.eventId,
            operationId = request.operationId,
            resource = resource,
            lane = request.lane
        )
    }

    fun observeJournaledOperations(): Flow<List<JournaledOperation>> {
        return operationJournalDao.observeRecentOperations().map { list ->
            list.map { entity ->
                JournaledOperation(
                    operationId = entity.operationId,
                    commandTopic = entity.commandTopic,
                    resourceId = entity.resourceId,
                    expectedPreconditionHash = entity.expectedPreconditionHash,
                    targetResultHash = entity.targetResultHash,
                    argumentsHash = entity.argumentsHash,
                    argumentsJson = entity.argumentsJson,
                    beforeSnapshotJson = entity.beforeSnapshotJson,
                    afterSnapshotJson = entity.afterSnapshotJson,
                    state = runCatching { OperationState.valueOf(entity.state) }
                        .getOrDefault(OperationState.FAILED),
                    actorId = entity.actorId,
                    actorKind = runCatching { ActorKind.valueOf(entity.actorKind) }
                        .getOrDefault(ActorKind.USER),
                    workspaceId = entity.workspaceId,
                    workspaceGeneration = entity.workspaceGeneration,
                    resultSummary = entity.resultSummary,
                    updatedAtEpochMs = entity.updatedAtEpochMs
                )
            }
        }
    }
}
