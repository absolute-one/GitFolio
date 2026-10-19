package com.example.kernel

import com.example.contracts.ActorKind
import com.example.contracts.Capability
import com.example.contracts.CommandCatalogue
import com.example.contracts.CommandDescriptor
import com.example.contracts.ExtensionManifest
import com.example.contracts.InstalledExtensionDescriptor
import com.example.contracts.KeystonePort
import com.example.contracts.Lane
import com.example.contracts.MountedSlotContribution
import com.example.contracts.PluginHostPort
import com.example.contracts.ProfileCatalogue
import com.example.contracts.SlotCatalogue
import com.example.contracts.SlotContributionSpec
import com.example.contracts.SystemProfile
import com.example.contracts.TrustTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Keystone Architecture — Section 11, 12.3 & 13:
 * Plugin Host managing extension manifests, sandboxes, dual-owner slot contributions,
 * supervision failure recovery, and composition Profiles.
 */
class PluginHost(
    private val kernel: KeystonePort
) : PluginHostPort {

    private val installedExtensions = ConcurrentHashMap<String, InstalledExtensionDescriptor>()
    private val extensionScopes = ConcurrentHashMap<String, CoroutineScope>()

    private val _extensionsFlow = MutableStateFlow<List<InstalledExtensionDescriptor>>(emptyList())
    override val extensionsFlow: StateFlow<List<InstalledExtensionDescriptor>> = _extensionsFlow.asStateFlow()

    private val _mountedContributions = MutableStateFlow<List<MountedSlotContribution>>(emptyList())
    override val mountedContributions: StateFlow<List<MountedSlotContribution>> = _mountedContributions.asStateFlow()

    private val _activeProfile = MutableStateFlow(ProfileCatalogue.DEFAULT)
    override val activeProfile: StateFlow<SystemProfile> = _activeProfile.asStateFlow()

    init {
        registerBuiltInExtensions()
    }

    private fun registerBuiltInExtensions() {
        installExtension(
            manifest = ExtensionManifest(
                extensionId = "ext.word-count",
                name = "Document Word & Reading Time Counter",
                version = "1.2.0",
                trustTier = TrustTier.CORE,
                permissions = setOf(Capability.DATA_READ),
                lane = Lane.BACKGROUND,
                publisherSignature = "sig:core-gitfolio-wordcount",
                contributedCommands = listOf(
                    CommandDescriptor(
                        id = "wordcount.toggle",
                        title = "Show Word Count Telemetry",
                        iconName = "notes",
                        defaultShortcut = "Ctrl+Shift+W",
                        requiredCapability = Capability.DATA_READ,
                        lane = Lane.INTERACTIVE,
                        inputSchema = emptyList(),
                        outputSchema = listOf("words", "minutes"),
                        possibleErrors = emptyList(),
                        callableByAutomatedActors = true
                    )
                ),
                slotContributions = listOf(
                    SlotContributionSpec(
                        contributionId = "contrib.reader.wordcount",
                        slotId = SlotCatalogue.READER_STATUS_BAR,
                        label = "AST Word & Line Telemetry",
                        commandIdToDispatch = "wordcount.toggle",
                        badgeValueProviderKey = "word_count"
                    )
                )
            ),
            installedTrustTier = TrustTier.CORE,
            userGrantedCapabilities = setOf(Capability.DATA_READ),
            enabledByDefault = true
        )

        installExtension(
            manifest = ExtensionManifest(
                extensionId = "ext.commit-stats",
                name = "Topological DAG Lane Analyzer",
                version = "2.0.1",
                trustTier = TrustTier.COMMUNITY,
                permissions = setOf(Capability.DATA_READ),
                lane = Lane.BACKGROUND,
                publisherSignature = "sig:community-dag-analyzer",
                contributedCommands = listOf(
                    CommandDescriptor(
                        id = "dagstats.inspect",
                        title = "Inspect DAG Lane Density",
                        iconName = "account_tree",
                        defaultShortcut = "",
                        requiredCapability = Capability.DATA_READ,
                        lane = Lane.INTERACTIVE,
                        inputSchema = emptyList(),
                        outputSchema = listOf("lanes"),
                        possibleErrors = emptyList(),
                        callableByAutomatedActors = true
                    )
                ),
                slotContributions = listOf(
                    SlotContributionSpec(
                        contributionId = "contrib.explorer.dagstats",
                        slotId = SlotCatalogue.EXPLORER_HEADER_BADGES,
                        label = "DAG Zero-Stub Verified",
                        commandIdToDispatch = CommandCatalogue.GRAPH_TOGGLE_HIGH_SCALE,
                        badgeValueProviderKey = "dag_lanes"
                    )
                )
            ),
            installedTrustTier = TrustTier.COMMUNITY,
            userGrantedCapabilities = setOf(Capability.DATA_READ),
            enabledByDefault = true
        )

        installExtension(
            manifest = ExtensionManifest(
                extensionId = "ext.security-guard",
                name = "Sealed Checksum Storage & Open Guard",
                version = "1.0.0",
                trustTier = TrustTier.CORE,
                permissions = setOf(Capability.SYSTEM_ADMIN, Capability.DATA_WRITE),
                lane = Lane.INTERACTIVE,
                publisherSignature = "sig:core-warden-guard",
                contributedCommands = emptyList(),
                slotContributions = listOf(
                    SlotContributionSpec(
                        contributionId = "contrib.workspace.guard",
                        slotId = SlotCatalogue.WORKSPACE_OPEN_GUARD,
                        label = "Warden Workspace Integrity Guard"
                    ),
                    SlotContributionSpec(
                        contributionId = "contrib.storage.codec",
                        slotId = SlotCatalogue.STORAGE_CODEC,
                        label = "SHA-256 Precondition Codec"
                    )
                )
            ),
            installedTrustTier = TrustTier.CORE,
            userGrantedCapabilities = setOf(Capability.SYSTEM_ADMIN, Capability.DATA_WRITE),
            enabledByDefault = false
        )
    }

    override fun installExtension(
        manifest: ExtensionManifest,
        installedTrustTier: TrustTier,
        userGrantedCapabilities: Set<Capability>,
        enabledByDefault: Boolean
    ): Result<Unit> {
        val enforcedManifest = manifest.copy(trustTier = installedTrustTier)
        val effectiveGrants = enforcedManifest.permissions.intersect(userGrantedCapabilities)

        for (contrib in enforcedManifest.slotContributions) {
            val slot = kernel.getSlotDescriptor(contrib.slotId)
                ?: return Result.failure(IllegalArgumentException("Unknown slot ${contrib.slotId}"))
            if (!slot.allowedTiers.contains(installedTrustTier)) {
                return Result.failure(
                    SecurityException(
                        "Extension ${enforcedManifest.extensionId} (tier=$installedTrustTier) is not permitted in slot ${slot.slotId}"
                    )
                )
            }
            if (slot.requiredCapability != null && !effectiveGrants.contains(slot.requiredCapability)) {
                return Result.failure(
                    SecurityException(
                        "Extension ${enforcedManifest.extensionId} lacks required capability ${slot.requiredCapability} for slot ${slot.slotId}"
                    )
                )
            }
        }

        val state = InstalledExtensionDescriptor(
            manifest = enforcedManifest,
            enabled = enabledByDefault,
            grantedCapabilities = effectiveGrants
        )
        installedExtensions[enforcedManifest.extensionId] = state

        if (enabledByDefault) {
            activateExtensionRuntime(state)
        }
        refreshExtensionStateSnapshot()
        return Result.success(Unit)
    }

    private fun activateExtensionRuntime(state: InstalledExtensionDescriptor) {
        val extId = state.manifest.extensionId
        val extScope = CoroutineScope(kernel.kernelScope.coroutineContext + SupervisorJob())
        extensionScopes[extId] = extScope

        kernel.registerSender(
            senderId = extId,
            actorKind = ActorKind.EXTENSION,
            trustTier = state.manifest.trustTier,
            scopeId = "scope:ext:$extId"
        )

        for (cmd in state.manifest.contributedCommands) {
            kernel.registerExtensionCommand(extId, cmd)
        }

        for (spec in state.manifest.slotContributions) {
            mountContribution(
                extensionId = extId,
                spec = spec,
                mountOwnerScopeId = "scope:global"
            )
        }
    }

    override fun mountContribution(
        extensionId: String,
        spec: SlotContributionSpec,
        mountOwnerScopeId: String
    ): Result<MountedSlotContribution> {
        val extState = installedExtensions[extensionId]
            ?: return Result.failure(IllegalArgumentException("Extension not installed: $extensionId"))
        if (!extState.enabled) {
            return Result.failure(IllegalStateException("Extension disabled: $extensionId"))
        }
        val slot = kernel.getSlotDescriptor(spec.slotId)
            ?: return Result.failure(IllegalArgumentException("Slot not in catalogue: ${spec.slotId}"))
        if (!slot.allowedTiers.contains(extState.manifest.trustTier)) {
            return Result.failure(SecurityException("Tier ${extState.manifest.trustTier} denied for slot ${slot.slotId}"))
        }

        val mounted = MountedSlotContribution(
            contributionId = "${spec.contributionId}@$mountOwnerScopeId",
            extensionId = extensionId,
            extensionName = extState.manifest.name,
            trustTier = extState.manifest.trustTier,
            slotId = spec.slotId,
            mountOwnerScopeId = mountOwnerScopeId,
            label = spec.label,
            commandIdToDispatch = spec.commandIdToDispatch
        )
        _mountedContributions.update { current ->
            current.filterNot { it.contributionId == mounted.contributionId } + mounted
        }
        return Result.success(mounted)
    }

    override fun disableExtension(extensionId: String, reason: String?) {
        val existing = installedExtensions[extensionId] ?: return
        installedExtensions[extensionId] = existing.copy(
            enabled = false,
            disabledReason = reason
        )

        extensionScopes.remove(extensionId)?.cancel()
        kernel.unregisterSenderScope("scope:ext:$extensionId")
        kernel.unregisterExtensionCommands(existing.manifest.contributedCommands.map { it.id })

        _mountedContributions.update { list ->
            list.filterNot { it.extensionId == extensionId }
        }
        refreshExtensionStateSnapshot()
    }

    override fun enableExtension(extensionId: String) {
        val existing = installedExtensions[extensionId] ?: return
        if (existing.enabled) return
        val updated = existing.copy(enabled = true, failureCount = 0, disabledReason = null)
        installedExtensions[extensionId] = updated
        activateExtensionRuntime(updated)
        refreshExtensionStateSnapshot()
    }

    override fun toggleExtension(extensionId: String): Boolean {
        val existing = installedExtensions[extensionId] ?: return false
        return if (existing.enabled) {
            disableExtension(extensionId)
            false
        } else {
            enableExtension(extensionId)
            true
        }
    }

    override fun onMountOwnerClosed(mountOwnerScopeId: String) {
        _mountedContributions.update { list ->
            list.filterNot { it.mountOwnerScopeId == mountOwnerScopeId }
        }
    }

    override fun reportContributionFailure(extensionId: String, contributionId: String, errorSummary: String): Boolean {
        val existing = installedExtensions[extensionId] ?: return false
        val newFailures = existing.failureCount + 1
        _mountedContributions.update { list ->
            list.filterNot { it.contributionId == contributionId }
        }
        return if (newFailures >= 3) {
            disableExtension(
                extensionId = extensionId,
                reason = "Auto-disabled by Supervisor after $newFailures failures: $errorSummary"
            )
            true
        } else {
            installedExtensions[extensionId] = existing.copy(failureCount = newFailures)
            refreshExtensionStateSnapshot()
            false
        }
    }

    override fun applyProfile(profileId: String): SystemProfile {
        val profile = ProfileCatalogue.AllProfiles.find { it.profileId == profileId }
            ?: ProfileCatalogue.DEFAULT
        _activeProfile.value = profile

        for ((extId, _) in installedExtensions) {
            val shouldEnable = profile.activeSlotContributionIds.contains(extId)
            if (shouldEnable) {
                enableExtension(extId)
            } else {
                disableExtension(extId)
            }
        }
        return profile
    }

    override fun getContributionsForSlot(slotId: String): List<MountedSlotContribution> {
        return _mountedContributions.value.filter { it.slotId == slotId }
    }

    private fun refreshExtensionStateSnapshot() {
        _extensionsFlow.value = installedExtensions.values.sortedBy { it.manifest.extensionId }
    }
}
