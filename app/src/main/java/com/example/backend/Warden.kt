package com.example.backend

import com.example.contracts.ActorKind
import com.example.contracts.AuditLogEntry
import com.example.contracts.CallContext
import com.example.contracts.Capability
import com.example.contracts.DataSensitivityLabel
import com.example.contracts.Envelope
import com.example.contracts.ErrorCode
import com.example.contracts.OutboundPolicyGatePort
import com.example.contracts.OutboundPurpose
import com.example.contracts.OutboundRequestDescriptor
import com.example.contracts.PendingApproval
import com.example.contracts.ProfileCatalogue
import com.example.contracts.ResourceRef
import com.example.contracts.SystemProfile
import com.example.contracts.TrustTier
import com.example.data.local.AuditChainDao
import com.example.data.local.AuditChainEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keystone Architecture — Section 10:
 * The Warden: One Policy Gate inside the Foundation Arbiter.
 *
 * Law 5: Only the Warden decides permission, approval, and outbound data.
 * Authority comes from the Keystone-stamped CallContext, never from message payload content.
 */
class Warden(
    private val auditChainDao: AuditChainDao
) : OutboundPolicyGatePort {

    sealed interface PolicyDecision {
        data object Allowed : PolicyDecision
        data class Denied(val errorCode: ErrorCode, val messageKey: String, val reason: String) : PolicyDecision
        data class RequiresApproval(val approval: PendingApproval) : PolicyDecision
    }

    private var activeProfile: SystemProfile = ProfileCatalogue.DEFAULT
    private val policyVersion = AtomicInteger(1)

    // Extension capability grants registered by PluginHost
    private val extensionCapabilityGrants = ConcurrentHashMap<String, Set<Capability>>()

    // Automated actor loop guard & per-run budget counters (Section 10.3 #4)
    private val automatedRunActionCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val automatedRecentCallSignatures = ConcurrentHashMap<String, MutableList<String>>()
    private val maxAutomatedActionsPerRun = 25

    // Bound Pending Approvals (Section 10.4)
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()
    private val consumedApprovalIds = ConcurrentHashMap.newKeySet<String>()

    private val _pendingApprovalsFlow = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovalsFlow: StateFlow<List<PendingApproval>> = _pendingApprovalsFlow.asStateFlow()

    // Outbound Policy state (Section 10.5)
    @Volatile
    private var defaultOutboundAllowed: Boolean = true
    private val outboundConsents = ConcurrentHashMap.newKeySet<String>() // "$purpose|$host|$workspaceId"
    private val allowedTrustedHosts = setOf("api.github.com", "raw.githubusercontent.com", "github.com")

    private val auditMutex = Mutex()
    @Volatile
    private var cachedLatestHash: String? = null
    @Volatile
    private var cachedChainIntact: Boolean? = null

    init {
        // Default consent for GitHub API in default workspace
        OutboundPurpose.entries.forEach { purpose ->
            allowedTrustedHosts.forEach { host ->
                grantDestinationConsent(purpose, host, "workspace:home")
            }
        }
    }

    fun applyProfile(profile: SystemProfile) {
        activeProfile = profile
        defaultOutboundAllowed = profile.allowOutboundNetworkByDefault
        policyVersion.incrementAndGet()
    }

    fun setExtensionCapabilityGrants(extensionId: String, capabilities: Set<Capability>) {
        extensionCapabilityGrants[extensionId] = capabilities
    }

    fun resetAutomatedActorBudget(actorId: String) {
        automatedRunActionCounts.remove(actorId)
        automatedRecentCallSignatures.remove(actorId)
    }

    /**
     * Executes the 6-stage Warden Check Pipeline (Section 10.3):
     * 1. Capability -> 2. Scope (workspace + generation) -> 3. Data labels ->
     * 4. Loop guard + budget -> 5. Approval -> 6. Tamper-evident Audit
     */
    suspend fun evaluateCall(
        envelope: Envelope,
        requiredCapability: Capability,
        resolvedResourceId: String,
        currentResourceHash: String,
        currentResourceVersion: Long,
        activeWorkspaceGeneration: Long
    ): PolicyDecision {
        val ctx: CallContext = envelope.context
        val opId = envelope.operationId ?: envelope.eventId

        // Read-only profile check for write/delete capabilities
        if (activeProfile.readOnlyMode &&
            (requiredCapability == Capability.DATA_WRITE || requiredCapability == Capability.DATA_DELETE)
        ) {
            val decision = PolicyDecision.Denied(
                errorCode = ErrorCode.PermissionDenied,
                messageKey = "error.profile_read_only",
                reason = "Current profile '${activeProfile.displayName}' is read-only"
            )
            recordAudit(opId, ctx, requiredCapability, envelope.topic, resolvedResourceId, "DENIED_READ_ONLY")
            return decision
        }

        // STAGE 1: Capability Check (Section 10.2)
        val capabilityGranted = when (ctx.actorKind) {
            ActorKind.USER, ActorKind.SYSTEM -> true
            ActorKind.AUTOMATED_ACTOR -> {
                // Automated actor may read, write, or fetch, or request delete (which triggers approval in Stage 5)
                requiredCapability != Capability.SECRET_MANAGE && requiredCapability != Capability.SYSTEM_ADMIN
            }
            ActorKind.EXTENSION -> {
                val grants = extensionCapabilityGrants[ctx.actorId].orEmpty()
                grants.contains(requiredCapability)
            }
        }

        if (!capabilityGranted) {
            val decision = PolicyDecision.Denied(
                errorCode = ErrorCode.PermissionDenied,
                messageKey = "error.capability_not_granted",
                reason = "Actor ${ctx.actorId} (${ctx.actorKind}) lacks capability ${requiredCapability.id}"
            )
            recordAudit(opId, ctx, requiredCapability, envelope.topic, resolvedResourceId, "DENIED_CAPABILITY")
            return decision
        }

        // STAGE 2: Scope & Workspace Generation Check (Section 10.1 & 10.3)
        if (ctx.workspaceGeneration < activeWorkspaceGeneration &&
            (requiredCapability == Capability.DATA_WRITE || requiredCapability == Capability.DATA_DELETE)
        ) {
            val decision = PolicyDecision.Denied(
                errorCode = ErrorCode.StaleWorkspace,
                messageKey = "error.stale_workspace_generation",
                reason = "Envelope generation ${ctx.workspaceGeneration} < active generation $activeWorkspaceGeneration"
            )
            recordAudit(opId, ctx, requiredCapability, envelope.topic, resolvedResourceId, "DENIED_STALE_WORKSPACE")
            return decision
        }

        // STAGE 3: Data Sensitivity Labels (Section 10.3 #3)
        if (ctx.sensitivityLabel == DataSensitivityLabel.SECRET_CREDENTIAL &&
            ctx.actorKind != ActorKind.USER &&
            ctx.actorKind != ActorKind.SYSTEM
        ) {
            val decision = PolicyDecision.Denied(
                errorCode = ErrorCode.PermissionDenied,
                messageKey = "error.secret_label_restricted",
                reason = "Non-user actor ${ctx.actorId} cannot access SECRET_CREDENTIAL labelled data"
            )
            recordAudit(opId, ctx, requiredCapability, envelope.topic, resolvedResourceId, "DENIED_DATA_LABEL")
            return decision
        }

        // STAGE 4: Loop Guard & Automated Run Budget (Section 10.3 #4)
        if (ctx.actorKind == ActorKind.AUTOMATED_ACTOR) {
            val count = automatedRunActionCounts.getOrPut(ctx.actorId) { AtomicInteger(0) }.incrementAndGet()
            if (count > maxAutomatedActionsPerRun) {
                val decision = PolicyDecision.Denied(
                    errorCode = ErrorCode.Busy,
                    messageKey = "error.automated_budget_exceeded",
                    reason = "Automated actor ${ctx.actorId} exceeded budget ($maxAutomatedActionsPerRun actions)"
                )
                recordAudit(opId, ctx, requiredCapability, envelope.topic, resolvedResourceId, "DENIED_BUDGET")
                return decision
            }

            val callSignature = "${envelope.topic}:$resolvedResourceId:${envelope.payload.entries.sortedBy { it.key }}"
            val recentList = automatedRecentCallSignatures.getOrPut(ctx.actorId) { mutableListOf() }
            synchronized(recentList) {
                recentList.add(callSignature)
                if (recentList.size > 6) recentList.removeAt(0)
                val identicalCount = recentList.count { it == callSignature }
                if (identicalCount >= 3) {
                    val decision = PolicyDecision.Denied(
                        errorCode = ErrorCode.Busy,
                        messageKey = "error.automated_loop_detected",
                        reason = "Loop guard paused automated actor ${ctx.actorId} after 3 identical calls"
                    )
                    return decision
                }
            }
        }

        // STAGE 5: Bound Approval Check (Section 10.2 & 10.4)
        val needsHumanApproval = when {
            ctx.actorKind == ActorKind.AUTOMATED_ACTOR && requiredCapability == Capability.DATA_DELETE -> true
            ctx.actorKind == ActorKind.EXTENSION &&
                (requiredCapability == Capability.DATA_DELETE || ctx.trustTier != TrustTier.CORE) &&
                requiredCapability == Capability.DATA_WRITE -> true
            ctx.actorKind == ActorKind.USER &&
                requiredCapability == Capability.DATA_DELETE &&
                activeProfile.requireApprovalForUserDeletes &&
                envelope.payload["wardenApproved"] != "true" -> true
            else -> false
        }

        if (needsHumanApproval) {
            val summary = "Allow ${ctx.actorId} (${ctx.actorKind}) to execute '${envelope.topic}' on '$resolvedResourceId'"
            val approval = PendingApproval(
                approvalId = "appr-${UUID.randomUUID()}",
                operationId = opId,
                commandTopic = envelope.topic,
                resourceId = resolvedResourceId,
                expectedContentHash = currentResourceHash,
                expectedVersion = currentResourceVersion,
                promptSummaryHash = ResourceRef.computeHash(summary),
                humanReadableSummary = summary,
                policyVersion = policyVersion.get(),
                scopeId = ctx.scopeId,
                workspaceId = ctx.workspaceId,
                workspaceGeneration = activeWorkspaceGeneration,
                expiresAtEpochMs = System.currentTimeMillis() + 300_000L, // 5 minute window
                OriginalEnvelope = envelope
            )
            pendingApprovals[approval.approvalId] = approval
            refreshPendingApprovals()
            recordAudit(opId, ctx, requiredCapability, envelope.topic, resolvedResourceId, "AWAITING_APPROVAL")
            return PolicyDecision.RequiresApproval(approval)
        }

        // STAGE 6: Append-Only Tamper-Evident Audit Log (Section 10.3 #6)
        recordAudit(opId, ctx, requiredCapability, envelope.topic, resolvedResourceId, "ALLOWED")
        return PolicyDecision.Allowed
    }

    /**
     * Revalidates a bound approval immediately before execution (Section 10.4).
     * Checks: not previously used (no replay), not expired, policy version unchanged,
     * workspace generation unchanged, and expected resource hash/version unchanged.
     */
    suspend fun consumeAndRevalidateApproval(
        approvalId: String,
        currentResourceHash: String,
        currentResourceVersion: Long,
        activeWorkspaceGeneration: Long
    ): Result<PendingApproval> {
        if (!consumedApprovalIds.add(approvalId)) {
            return Result.failure(IllegalStateException("Approval $approvalId has already been used (replay rejected)"))
        }
        val approval = pendingApprovals.remove(approvalId)
            ?: return Result.failure(IllegalArgumentException("Approval $approvalId not found"))
        refreshPendingApprovals()

        val now = System.currentTimeMillis()
        if (now > approval.expiresAtEpochMs) {
            return Result.failure(IllegalStateException("Approval $approvalId expired"))
        }
        if (approval.policyVersion != policyVersion.get()) {
            return Result.failure(IllegalStateException("Policy version changed since approval was created"))
        }
        if (approval.workspaceGeneration != activeWorkspaceGeneration) {
            return Result.failure(IllegalStateException("Workspace generation changed since approval was created"))
        }
        if (approval.expectedContentHash != currentResourceHash || approval.expectedVersion != currentResourceVersion) {
            return Result.failure(IllegalStateException("Resource state changed since approval was presented"))
        }
        return Result.success(approval)
    }

    fun rejectApproval(approvalId: String): PendingApproval? {
        consumedApprovalIds.add(approvalId)
        val removed = pendingApprovals.remove(approvalId)
        refreshPendingApprovals()
        return removed
    }

    private fun refreshPendingApprovals() {
        _pendingApprovalsFlow.value = pendingApprovals.values.sortedByDescending { it.expiresAtEpochMs }
    }

    /**
     * Outbound Policy Gate (Section 10.5):
     * Every byte leaving the process passes this single port, classified by purpose and destination.
     */
    override fun checkOutboundAllowed(descriptor: OutboundRequestDescriptor): Result<Unit> {
        if (!defaultOutboundAllowed) {
            return Result.failure(
                SecurityException("Outbound network blocked by active profile (${activeProfile.displayName})")
            )
        }
        if (descriptor.sensitivityLabel == DataSensitivityLabel.SECRET_CREDENTIAL &&
            !allowedTrustedHosts.contains(descriptor.destinationHost.lowercase())
        ) {
            return Result.failure(
                SecurityException("SECRET_CREDENTIAL data cannot leave process to untrusted host ${descriptor.destinationHost}")
            )
        }
        val hostLower = descriptor.destinationHost.lowercase()
        val consentKey = "${descriptor.purpose.name}|$hostLower|${descriptor.workspaceId}"
        val globalConsentKey = "${descriptor.purpose.name}|$hostLower|workspace:home"
        if (!allowedTrustedHosts.contains(hostLower) &&
            !outboundConsents.contains(consentKey) &&
            !outboundConsents.contains(globalConsentKey)
        ) {
            return Result.failure(
                SecurityException("No outbound consent for (${descriptor.purpose}, $hostLower, ${descriptor.workspaceId})")
            )
        }
        return Result.success(Unit)
    }

    override fun setDefaultOutboundAllowed(allowed: Boolean) {
        defaultOutboundAllowed = allowed
    }

    override fun grantDestinationConsent(purpose: OutboundPurpose, destinationHost: String, workspaceId: String) {
        outboundConsents.add("${purpose.name}|${destinationHost.lowercase()}|$workspaceId")
    }

    /**
     * Appends an entry to the tamper-evident hash chain (Section 10.3 #6).
     */
    suspend fun recordAudit(
        operationId: String,
        context: CallContext,
        capability: Capability,
        topic: String,
        resourceId: String,
        decision: String
    ) {
        auditMutex.withLock {
            val prevHash = cachedLatestHash ?: run {
                val latest = auditChainDao.getLatestAuditEntry()
                latest?.entryHash ?: "GENESIS_000000000000"
            }
            val now = System.currentTimeMillis()
            val rawChainInput = "$prevHash|$operationId|${context.actorId}|${capability.id}|$topic|$resourceId|$decision|$now"
            val entryHash = ResourceRef.computeHash(rawChainInput)
            cachedLatestHash = entryHash

            auditChainDao.insertAuditEntry(
                AuditChainEntity(
                    operationId = operationId,
                    actorId = context.actorId,
                    actorKind = context.actorKind.name,
                    capability = capability.id,
                    topic = topic,
                    resourceId = resourceId,
                    decision = decision,
                    timestampEpochMs = now,
                    previousHash = prevHash,
                    entryHash = entryHash
                )
            )
        }
    }

    /**
     * Verifies that the entire append-only audit log hash chain is intact and untampered.
     */
    suspend fun verifyAuditChainIntegrity(): Boolean {
        cachedChainIntact?.let { return it }
        val entries = auditChainDao.getAllAuditEntriesOrdered()
        var expectedPrev = "GENESIS_000000000000"
        for (entry in entries) {
            if (entry.previousHash != expectedPrev) {
                cachedChainIntact = false
                return false
            }
            val raw = "${entry.previousHash}|${entry.operationId}|${entry.actorId}|${entry.capability}|${entry.topic}|${entry.resourceId}|${entry.decision}|${entry.timestampEpochMs}"
            val computed = ResourceRef.computeHash(raw)
            if (entry.entryHash != computed) {
                cachedChainIntact = false
                return false
            }
            expectedPrev = computed
        }
        cachedLatestHash = expectedPrev
        cachedChainIntact = true
        return true
    }

    fun observeRecentAuditLog(): Flow<List<AuditLogEntry>> {
        return auditChainDao.observeRecentAuditEntries().map { list ->
            list.map {
                AuditLogEntry(
                    seq = it.seq,
                    operationId = it.operationId,
                    actorId = it.actorId,
                    actorKind = runCatching { ActorKind.valueOf(it.actorKind) }.getOrDefault(ActorKind.USER),
                    capability = it.capability,
                    topic = it.topic,
                    resourceId = it.resourceId,
                    decision = it.decision,
                    timestampEpochMs = it.timestampEpochMs,
                    previousHash = it.previousHash,
                    entryHash = it.entryHash
                )
            }
        }
    }
}
