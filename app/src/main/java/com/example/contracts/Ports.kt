package com.example.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keystone Architecture — Section 10.5, 11 & 15:
 * Ports, Adapter Capability declarations, Outbound Policy contracts, Extension Manifests,
 * and Kernel/PluginHost contracts so Crown and Foundation depend strictly on `com.example.contracts`.
 */

data class AdapterCapabilities(
    val adapterName: String,
    val atomicReplace: Boolean,
    val coordinationWithOtherWriters: Boolean,
    val durableFlush: Boolean,
    val reliableChangeNotifications: Boolean
)

enum class OutboundPurpose {
    PROBE_REPOSITORY,
    FETCH_BRANCHES,
    FETCH_GIT_TREE,
    FETCH_COMMIT_GRAPH,
    FETCH_MARKDOWN_BLOB
}

data class OutboundRequestDescriptor(
    val purpose: OutboundPurpose,
    val destinationHost: String,
    val url: String,
    val workspaceId: String,
    val sensitivityLabel: DataSensitivityLabel
)

/**
 * Single Outbound Policy Gate (Section 10.5):
 * Every byte leaving the process passes one port, classified by purpose and destination.
 */
interface OutboundPolicyGatePort {
    fun checkOutboundAllowed(descriptor: OutboundRequestDescriptor): Result<Unit>
    fun setDefaultOutboundAllowed(allowed: Boolean)
    fun grantDestinationConsent(purpose: OutboundPurpose, destinationHost: String, workspaceId: String)
}

/**
 * Extension Manifest & Dual-Owner Slot Contribution (Section 11).
 */
data class ExtensionManifest(
    val extensionId: String,
    val name: String,
    val version: String,
    val trustTier: TrustTier, // Determined by installation source, never self-asserted
    val permissions: Set<Capability>,
    val allowedHosts: Set<String> = emptySet(),
    val lane: Lane = Lane.BACKGROUND,
    val publisherSignature: String,
    val contributedCommands: List<CommandDescriptor> = emptyList(),
    val slotContributions: List<SlotContributionSpec> = emptyList()
)

data class SlotContributionSpec(
    val contributionId: String,
    val slotId: String,
    val label: String,
    val commandIdToDispatch: String? = null,
    val badgeValueProviderKey: String? = null
)

data class InstalledExtensionDescriptor(
    val manifest: ExtensionManifest,
    val enabled: Boolean,
    val grantedCapabilities: Set<Capability>,
    val failureCount: Int = 0,
    val disabledReason: String? = null
)

/**
 * Active mounted contribution with two owners (Section 11.2):
 * 1. Extension scope (`extensionId`)
 * 2. Mount owner scope (`mountOwnerScopeId` — view or workspace)
 * Terminates immediately when EITHER owner ends.
 */
data class MountedSlotContribution(
    val contributionId: String,
    val extensionId: String,
    val extensionName: String,
    val trustTier: TrustTier,
    val slotId: String,
    val mountOwnerScopeId: String,
    val label: String,
    val commandIdToDispatch: String?,
    val badgeText: String? = null,
    val failureCount: Int = 0
)

/**
 * Contract for the Keystone router and registries (Section 5 & 15).
 * Both Crown (`frontend`) and Foundation (`backend`) talk to the Keystone through this contract.
 */
interface KeystonePort {
    val kernelScope: CoroutineScope
    val commandsFlow: StateFlow<List<CommandDescriptor>>
    val traceLog: StateFlow<List<TraceLogEntry>>
    val toFoundation: SharedFlow<Envelope>
    val toCrown: SharedFlow<Envelope>

    fun registerSender(
        senderId: String,
        actorKind: ActorKind,
        trustTier: TrustTier,
        scopeId: String
    )

    fun unregisterSenderScope(scopeId: String)
    fun switchWorkspace(newWorkspaceId: String): Long
    fun currentWorkspaceId(): String
    fun currentWorkspaceGeneration(): Long

    fun registerExtensionCommand(extensionId: String, descriptor: CommandDescriptor): Result<Unit>
    fun unregisterExtensionCommands(commandIds: Collection<String>)
    fun getCommandDescriptor(commandId: String): CommandDescriptor?
    fun getSlotDescriptor(slotId: String): SlotDescriptor?

    fun bindFoundationArbiterHandler(handler: suspend (Envelope) -> Envelope?)

    fun stampCallContext(
        senderId: String,
        workspaceOverride: String? = null,
        generationOverride: Long? = null,
        sensitivityLabel: DataSensitivityLabel = DataSensitivityLabel.PUBLIC
    ): CallContext

    suspend fun dispatchToFoundation(
        senderId: String,
        topic: String,
        kind: EnvelopeKind = EnvelopeKind.COMMAND,
        payload: Map<String, String> = emptyMap(),
        operationId: String? = null,
        resource: ResourceRef? = null,
        correlationId: String? = null,
        causationId: String? = null,
        workspaceOverride: String? = null,
        generationOverride: Long? = null,
        sensitivityLabel: DataSensitivityLabel = DataSensitivityLabel.PUBLIC
    ): Envelope

    suspend fun routeEnvelopeToFoundation(envelope: Envelope): Envelope

    suspend fun publishEventToCrown(
        senderId: String,
        topic: String,
        payload: Map<String, String> = emptyMap(),
        resource: ResourceRef? = null,
        correlationId: String? = null,
        causationId: String? = null,
        operationId: String? = null,
        lane: Lane = Lane.INTERACTIVE
    )

    fun createErrorReply(
        request: Envelope,
        code: ErrorCode,
        messageKey: String,
        params: Map<String, String> = emptyMap(),
        retryable: Boolean = false
    ): Envelope

    /**
     * Section 7 Delivery Rule #7:
     * Large content is passed by reference (`contentRefId`) rather than bloating envelope payloads,
     * and read by handle/range.
     */
    fun putLargeContentByReference(refId: String, value: Any)
    fun <T> getLargeContentByReference(refId: String): T?
}

/**
 * Contract for the Plugin Host (Section 5, 11 & 15).
 */
interface PluginHostPort {
    val extensionsFlow: StateFlow<List<InstalledExtensionDescriptor>>
    val mountedContributions: StateFlow<List<MountedSlotContribution>>
    val activeProfile: StateFlow<SystemProfile>

    fun installExtension(
        manifest: ExtensionManifest,
        installedTrustTier: TrustTier,
        userGrantedCapabilities: Set<Capability>,
        enabledByDefault: Boolean = true
    ): Result<Unit>

    fun mountContribution(
        extensionId: String,
        spec: SlotContributionSpec,
        mountOwnerScopeId: String
    ): Result<MountedSlotContribution>

    fun disableExtension(extensionId: String, reason: String? = null)
    fun enableExtension(extensionId: String)
    fun toggleExtension(extensionId: String): Boolean
    fun onMountOwnerClosed(mountOwnerScopeId: String)
    fun reportContributionFailure(extensionId: String, contributionId: String, errorSummary: String): Boolean
    fun applyProfile(profileId: String): SystemProfile
    fun getContributionsForSlot(slotId: String): List<MountedSlotContribution>
}
