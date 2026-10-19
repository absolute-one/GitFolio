package com.example.contracts

/**
 * Keystone Architecture — Section 8, 10, 11, 13 & 16:
 * Machine-checkable Catalogues for Capabilities, Commands, Event Topics, Extension Slots, and Profiles.
 */

enum class Capability(val id: String, val description: String) {
    DATA_READ("data.read", "Read repositories, file trees, commits, and markdown documents"),
    DATA_WRITE("data.write", "Add, sync, or switch branches on repositories"),
    DATA_DELETE("data.delete", "Remove repositories or clear durable records"),
    NETWORK_FETCH("network.fetch", "Fetch live repository trees, branches, commits, or files"),
    SECRET_MANAGE("secret.manage", "Configure or clear OAuth tokens in the secret vault"),
    UI_PREFERENCES("ui.preferences", "Adjust theme, typography, or layout preferences"),
    SYSTEM_ADMIN("system.admin", "Approve operations, switch profiles, or manage extensions")
}

data class CommandDescriptor(
    val id: String, // imperative domain.verbObject
    val title: String,
    val iconName: String,
    val defaultShortcut: String,
    val requiredCapability: Capability,
    val lane: Lane,
    val inputSchema: List<String>,
    val outputSchema: List<String>,
    val possibleErrors: List<ErrorCode>,
    val callableByAutomatedActors: Boolean,
    val mutatesDurableState: Boolean = false
)

/**
 * Single unified Command Catalogue (Law 6 & Section 8).
 * Reserved core namespaces: repository.*, credential.*, explorer.*, graph.*, document.*, reader.*, ui.*, mutation.*, profile.*, extension.*
 */
object CommandCatalogue {
    const val REPOSITORY_PROBE = "repository.probe"
    const val REPOSITORY_ADD = "repository.add"
    const val REPOSITORY_REMOVE = "repository.remove"
    const val REPOSITORY_REFRESH = "repository.refresh"
    const val REPOSITORY_SWITCH_BRANCH = "repository.switchBranch"
    const val REPOSITORY_OPEN = "repository.open"
    const val REPOSITORY_MOUNT_SHARED = "repository.mountShared"
    const val REPOSITORY_SEED_DEFAULT = "repository.seedDefault"

    const val CREDENTIAL_SAVE = "credential.save"
    const val CREDENTIAL_CLEAR = "credential.clear"

    const val EXPLORER_SET_VIEW_MODE = "explorer.setViewMode"
    const val EXPLORER_SEARCH_FILES = "explorer.searchFiles"
    const val EXPLORER_TOGGLE_MARKDOWN_ONLY = "explorer.toggleMarkdownOnly"
    const val EXPLORER_TOGGLE_FOLDER = "explorer.toggleFolder"
    const val EXPLORER_EXPAND_ALL = "explorer.expandAll"
    const val EXPLORER_COLLAPSE_ALL = "explorer.collapseAll"
    const val EXPLORER_REFRESH_BRANCHES = "explorer.refreshBranches"

    const val GRAPH_LOAD = "graph.load"
    const val GRAPH_SEARCH_COMMITS = "graph.searchCommits"
    const val GRAPH_FILTER_BRANCH = "graph.filterBranch"
    const val GRAPH_TOGGLE_HIGH_SCALE = "graph.toggleHighScale"

    const val DOCUMENT_OPEN = "document.open"
    const val READER_ADJUST_FONT_SCALE = "reader.adjustFontScale"
    const val READER_TOGGLE_SERIF = "reader.toggleSerif"

    const val UI_TOGGLE_THEME = "ui.toggleTheme"
    const val UI_NAVIGATE_BACK = "ui.navigateBack"

    const val MUTATION_APPROVE = "mutation.approve"
    const val MUTATION_REJECT = "mutation.reject"
    const val MUTATION_UNDO_LAST_RUN = "mutation.undoLastRun"
    const val CACHE_REBUILD_DERIVED = "cache.rebuildDerived"

    const val PROFILE_SWITCH = "profile.switch"
    const val EXTENSION_TOGGLE = "extension.toggle"
    const val ACTOR_RUN_SYNC_SWEEP = "actor.runSyncSweep"

    val ReservedCorePrefixes = setOf(
        "repository.",
        "credential.",
        "explorer.",
        "graph.",
        "document.",
        "reader.",
        "ui.",
        "mutation.",
        "cache.",
        "profile.",
        "extension.",
        "actor."
    )

    val AllCoreCommands: List<CommandDescriptor> = listOf(
        CommandDescriptor(
            id = REPOSITORY_PROBE,
            title = "Verify Repository Access",
            iconName = "search",
            defaultShortcut = "Ctrl+Shift+P",
            requiredCapability = Capability.NETWORK_FETCH,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("rawInput"),
            outputSchema = listOf("fullSlug", "defaultBranch", "branchesCsv"),
            possibleErrors = listOf(ErrorCode.NotFound, ErrorCode.PermissionDenied, ErrorCode.Timeout),
            callableByAutomatedActors = true,
            mutatesDurableState = false
        ),
        CommandDescriptor(
            id = REPOSITORY_ADD,
            title = "Add Git Repository",
            iconName = "add",
            defaultShortcut = "Ctrl+N",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("rawInput", "selectedBranch", "knownBranchesCsv"),
            outputSchema = listOf("repoId", "fullSlug", "defaultBranch", "totalFilesCount"),
            possibleErrors = listOf(ErrorCode.Conflict, ErrorCode.PermissionDenied, ErrorCode.StorageUnavailable),
            callableByAutomatedActors = true,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = REPOSITORY_REMOVE,
            title = "Remove Repository",
            iconName = "delete",
            defaultShortcut = "Del",
            requiredCapability = Capability.DATA_DELETE,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("repoId"),
            outputSchema = listOf("repoId"),
            possibleErrors = listOf(ErrorCode.ApprovalRequired, ErrorCode.NotFound, ErrorCode.Conflict),
            callableByAutomatedActors = true,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = REPOSITORY_REFRESH,
            title = "Sync Repository Tree",
            iconName = "refresh",
            defaultShortcut = "Ctrl+R",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.BACKGROUND,
            inputSchema = listOf("repoId"),
            outputSchema = listOf("repoId", "totalFilesCount", "markdownFilesCount"),
            possibleErrors = listOf(ErrorCode.Timeout, ErrorCode.PermissionDenied, ErrorCode.Busy),
            callableByAutomatedActors = true,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = REPOSITORY_SWITCH_BRANCH,
            title = "Switch Repository Branch",
            iconName = "account_tree",
            defaultShortcut = "Ctrl+B",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("repoId", "targetBranch"),
            outputSchema = listOf("repoId", "defaultBranch"),
            possibleErrors = listOf(ErrorCode.Conflict, ErrorCode.NotFound, ErrorCode.Timeout),
            callableByAutomatedActors = true,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = REPOSITORY_OPEN,
            title = "Open Repository Workspace",
            iconName = "folder_open",
            defaultShortcut = "Enter",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("repoId"),
            outputSchema = listOf("repoId", "workspaceGeneration"),
            possibleErrors = listOf(ErrorCode.NotFound),
            callableByAutomatedActors = false,
            mutatesDurableState = false
        ),
        CommandDescriptor(
            id = REPOSITORY_MOUNT_SHARED,
            title = "Mount Shared Git URL",
            iconName = "share",
            defaultShortcut = "Ctrl+Shift+M",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("rawSharedUrl"),
            outputSchema = listOf("repoId", "fullSlug"),
            possibleErrors = listOf(ErrorCode.Internal, ErrorCode.PermissionDenied),
            callableByAutomatedActors = false,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = REPOSITORY_SEED_DEFAULT,
            title = "Seed Default git/git Repository",
            iconName = "auto_awesome",
            defaultShortcut = "",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.BACKGROUND,
            inputSchema = emptyList(),
            outputSchema = listOf("seeded"),
            possibleErrors = listOf(ErrorCode.StorageUnavailable),
            callableByAutomatedActors = true,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = CREDENTIAL_SAVE,
            title = "Save Host OAuth Token",
            iconName = "key",
            defaultShortcut = "Ctrl+K",
            requiredCapability = Capability.SECRET_MANAGE,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("providerId", "token"),
            outputSchema = listOf("providerId"),
            possibleErrors = listOf(ErrorCode.PermissionDenied, ErrorCode.StorageUnavailable),
            callableByAutomatedActors = false,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = CREDENTIAL_CLEAR,
            title = "Clear Host OAuth Token",
            iconName = "key_off",
            defaultShortcut = "",
            requiredCapability = Capability.SECRET_MANAGE,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("providerId"),
            outputSchema = listOf("providerId"),
            possibleErrors = listOf(ErrorCode.PermissionDenied),
            callableByAutomatedActors = false,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = EXPLORER_SET_VIEW_MODE,
            title = "Switch Explorer View Mode",
            iconName = "swap_horiz",
            defaultShortcut = "Ctrl+G",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("viewMode"),
            outputSchema = listOf("viewMode"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = EXPLORER_SEARCH_FILES,
            title = "Search Repository Files",
            iconName = "search",
            defaultShortcut = "Ctrl+F",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("query"),
            outputSchema = listOf("visibleCount"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = EXPLORER_TOGGLE_MARKDOWN_ONLY,
            title = "Toggle Markdown-Only Filter",
            iconName = "filter_list",
            defaultShortcut = "Ctrl+M",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = listOf("markdownOnly"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = EXPLORER_TOGGLE_FOLDER,
            title = "Toggle Folder Expansion",
            iconName = "folder",
            defaultShortcut = "Space",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("folderPath"),
            outputSchema = listOf("folderPath"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = EXPLORER_EXPAND_ALL,
            title = "Expand All Directories",
            iconName = "unfold_more",
            defaultShortcut = "Ctrl+Shift+E",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = emptyList(),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = EXPLORER_COLLAPSE_ALL,
            title = "Collapse All Directories",
            iconName = "unfold_less",
            defaultShortcut = "Ctrl+Shift+C",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = emptyList(),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = EXPLORER_REFRESH_BRANCHES,
            title = "Refresh Repository Branch List",
            iconName = "refresh",
            defaultShortcut = "",
            requiredCapability = Capability.NETWORK_FETCH,
            lane = Lane.BACKGROUND,
            inputSchema = listOf("repoId"),
            outputSchema = listOf("branchesCsv"),
            possibleErrors = listOf(ErrorCode.Timeout),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = GRAPH_LOAD,
            title = "Load Topological Git Commit Graph",
            iconName = "account_tree",
            defaultShortcut = "Ctrl+Shift+G",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.BACKGROUND,
            inputSchema = listOf("repoId", "highScaleMode", "forceRefresh"),
            outputSchema = listOf("totalCommits", "totalBranches"),
            possibleErrors = listOf(ErrorCode.Timeout, ErrorCode.Busy),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = GRAPH_SEARCH_COMMITS,
            title = "Filter Commit Graph Nodes",
            iconName = "search",
            defaultShortcut = "",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("query"),
            outputSchema = listOf("filteredCount"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = GRAPH_FILTER_BRANCH,
            title = "Filter Commit Graph by Branch",
            iconName = "filter_alt",
            defaultShortcut = "",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("branch"),
            outputSchema = listOf("branch"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = GRAPH_TOGGLE_HIGH_SCALE,
            title = "Toggle 1K+ Commit High-Scale DAG",
            iconName = "bolt",
            defaultShortcut = "Ctrl+Shift+H",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.BACKGROUND,
            inputSchema = listOf("repoId"),
            outputSchema = listOf("highScaleMode"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = DOCUMENT_OPEN,
            title = "Open File in Markdown Reader",
            iconName = "description",
            defaultShortcut = "Enter",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("repoId", "filePath"),
            outputSchema = listOf("filePath", "blockCount"),
            possibleErrors = listOf(ErrorCode.NotFound, ErrorCode.Timeout),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = READER_ADJUST_FONT_SCALE,
            title = "Adjust Reader Font Scale",
            iconName = "format_size",
            defaultShortcut = "Ctrl+=",
            requiredCapability = Capability.UI_PREFERENCES,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("delta"),
            outputSchema = listOf("fontScale"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = READER_TOGGLE_SERIF,
            title = "Toggle Editorial Serif / Sans Typography",
            iconName = "font_download",
            defaultShortcut = "Ctrl+T",
            requiredCapability = Capability.UI_PREFERENCES,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = listOf("useSerif"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = UI_TOGGLE_THEME,
            title = "Toggle Dark / Light Theme",
            iconName = "dark_mode",
            defaultShortcut = "Ctrl+Shift+T",
            requiredCapability = Capability.UI_PREFERENCES,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = listOf("darkTheme"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = UI_NAVIGATE_BACK,
            title = "Navigate Back",
            iconName = "arrow_back",
            defaultShortcut = "Esc",
            requiredCapability = Capability.UI_PREFERENCES,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = listOf("destination"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = MUTATION_APPROVE,
            title = "Approve Pending Warden Operation",
            iconName = "verified_user",
            defaultShortcut = "",
            requiredCapability = Capability.SYSTEM_ADMIN,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("approvalId"),
            outputSchema = listOf("operationId", "state"),
            possibleErrors = listOf(ErrorCode.ApprovalInvalid, ErrorCode.Conflict),
            callableByAutomatedActors = false,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = MUTATION_REJECT,
            title = "Reject Pending Warden Operation",
            iconName = "cancel",
            defaultShortcut = "",
            requiredCapability = Capability.SYSTEM_ADMIN,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("approvalId"),
            outputSchema = listOf("operationId", "state"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = MUTATION_UNDO_LAST_RUN,
            title = "Conditional Undo Last Mutation",
            iconName = "undo",
            defaultShortcut = "Ctrl+Z",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = listOf("undoneOperationId"),
            possibleErrors = listOf(ErrorCode.Conflict, ErrorCode.NotFound),
            callableByAutomatedActors = false,
            mutatesDurableState = true
        ),
        CommandDescriptor(
            id = CACHE_REBUILD_DERIVED,
            title = "Rebuild Derived Caches from Source of Truth",
            iconName = "build",
            defaultShortcut = "",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.BACKGROUND,
            inputSchema = emptyList(),
            outputSchema = listOf("rebuiltCount"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = true
        ),
        CommandDescriptor(
            id = PROFILE_SWITCH,
            title = "Switch Composition Profile",
            iconName = "tune",
            defaultShortcut = "",
            requiredCapability = Capability.SYSTEM_ADMIN,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("profileId"),
            outputSchema = listOf("profileId"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = EXTENSION_TOGGLE,
            title = "Enable / Disable Slot Extension",
            iconName = "extension",
            defaultShortcut = "",
            requiredCapability = Capability.SYSTEM_ADMIN,
            lane = Lane.INTERACTIVE,
            inputSchema = listOf("extensionId"),
            outputSchema = listOf("extensionId", "enabled"),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        ),
        CommandDescriptor(
            id = ACTOR_RUN_SYNC_SWEEP,
            title = "Run Automated Background Sync Actor",
            iconName = "smart_toy",
            defaultShortcut = "",
            requiredCapability = Capability.DATA_WRITE,
            lane = Lane.BACKGROUND,
            inputSchema = emptyList(),
            outputSchema = listOf("syncedCount"),
            possibleErrors = listOf(ErrorCode.Busy, ErrorCode.PermissionDenied),
            callableByAutomatedActors = true,
            mutatesDurableState = true
        )
    )
}

/**
 * Past-tense Event Topic Catalogue (Section 8).
 */
object TopicCatalogue {
    const val REPOSITORY_PROBED = "repository.probed"
    const val REPOSITORY_ADDED = "repository.added"
    const val REPOSITORY_REMOVED = "repository.removed"
    const val REPOSITORY_SYNCED = "repository.synced"
    const val REPOSITORY_BRANCH_SWITCHED = "repository.branchSwitched"
    const val REPOSITORY_BRANCHES_LOADED = "repository.branchesLoaded"
    const val REPOSITORY_SHARED_MOUNTED = "repository.sharedMounted"

    const val CREDENTIAL_UPDATED = "credential.updated"
    const val WORKSPACE_OPENED = "workspace.opened"
    const val DOCUMENT_LOADED = "document.loaded"
    const val GRAPH_LOADED = "graph.loaded"

    const val MUTATION_COMMITTED = "mutation.committed"
    const val MUTATION_CONFLICTED = "mutation.conflicted"
    const val MUTATION_AWAITING_APPROVAL = "mutation.awaitingApproval"
    const val MUTATION_UNDONE = "mutation.undone"
    const val CACHE_DERIVED_REBUILT = "cache.derivedRebuilt"
    const val SYNC_PROGRESS_UPDATED = "sync.progressUpdated" // Coalescable topic
    const val PROFILE_SWITCHED = "profile.switched"
    const val EXTENSION_STATE_CHANGED = "extension.stateChanged"
    const val AUDIT_RECORDED = "audit.recorded"

    val AllTopics: Set<String> = setOf(
        REPOSITORY_PROBED,
        REPOSITORY_ADDED,
        REPOSITORY_REMOVED,
        REPOSITORY_SYNCED,
        REPOSITORY_BRANCH_SWITCHED,
        REPOSITORY_BRANCHES_LOADED,
        REPOSITORY_SHARED_MOUNTED,
        CREDENTIAL_UPDATED,
        WORKSPACE_OPENED,
        DOCUMENT_LOADED,
        GRAPH_LOADED,
        MUTATION_COMMITTED,
        MUTATION_CONFLICTED,
        MUTATION_AWAITING_APPROVAL,
        MUTATION_UNDONE,
        CACHE_DERIVED_REBUILT,
        SYNC_PROGRESS_UPDATED,
        PROFILE_SWITCHED,
        EXTENSION_STATE_CHANGED,
        AUDIT_RECORDED
    )

    val CoalescableTopics: Set<String> = setOf(
        SYNC_PROGRESS_UPDATED
    )
}

/**
 * Extension Slot Catalogue (Law 9 & Section 11).
 */
enum class SlotKind {
    DECLARATIVE,
    HOOK,
    CUSTOM_VIEW
}

data class SlotDescriptor(
    val slotId: String,
    val title: String,
    val kind: SlotKind,
    val allowedTiers: Set<TrustTier>,
    val requiredCapability: Capability?,
    val securityCritical: Boolean = false
)

object SlotCatalogue {
    const val HOME_TOOLBAR_ACTIONS = "toolbar.actions"
    const val EXPLORER_HEADER_BADGES = "explorer.headerBadges"
    const val READER_STATUS_BAR = "reader.statusBar"
    const val WORKSPACE_OPEN_GUARD = "workspace.openGuard"
    const val STORAGE_CODEC = "storage.codec"
    const val ACTOR_TOOLS = "actor.tools"

    val AllSlots: List<SlotDescriptor> = listOf(
        SlotDescriptor(
            slotId = HOME_TOOLBAR_ACTIONS,
            title = "Home Shelf Toolbar Actions",
            kind = SlotKind.DECLARATIVE,
            allowedTiers = setOf(TrustTier.CORE, TrustTier.COMMUNITY),
            requiredCapability = null,
            securityCritical = false
        ),
        SlotDescriptor(
            slotId = EXPLORER_HEADER_BADGES,
            title = "Explorer Telemetry Badges",
            kind = SlotKind.DECLARATIVE,
            allowedTiers = setOf(TrustTier.CORE, TrustTier.COMMUNITY),
            requiredCapability = Capability.DATA_READ,
            securityCritical = false
        ),
        SlotDescriptor(
            slotId = READER_STATUS_BAR,
            title = "Markdown Reader Status Contributions",
            kind = SlotKind.DECLARATIVE,
            allowedTiers = setOf(TrustTier.CORE, TrustTier.COMMUNITY, TrustTier.REMOTE),
            requiredCapability = Capability.DATA_READ,
            securityCritical = false
        ),
        SlotDescriptor(
            slotId = WORKSPACE_OPEN_GUARD,
            title = "Workspace Open Policy Guard",
            kind = SlotKind.HOOK,
            allowedTiers = setOf(TrustTier.CORE),
            requiredCapability = Capability.SYSTEM_ADMIN,
            securityCritical = true
        ),
        SlotDescriptor(
            slotId = STORAGE_CODEC,
            title = "Durable Storage Codec",
            kind = SlotKind.HOOK,
            allowedTiers = setOf(TrustTier.CORE),
            requiredCapability = Capability.DATA_WRITE,
            securityCritical = true
        ),
        SlotDescriptor(
            slotId = ACTOR_TOOLS,
            title = "Automated Actor Command Tools",
            kind = SlotKind.HOOK,
            allowedTiers = setOf(TrustTier.CORE, TrustTier.COMMUNITY),
            requiredCapability = Capability.DATA_READ,
            securityCritical = false
        )
    )
}

/**
 * Profiles: Variation by Composition of Slot Fillers (Section 13).
 * Never subclass the tree; instantiate the same tree with a profile.
 */
data class SystemProfile(
    val profileId: String,
    val displayName: String,
    val description: String,
    val requireApprovalForUserDeletes: Boolean,
    val allowOutboundNetworkByDefault: Boolean,
    val readOnlyMode: Boolean,
    val storageCodecName: String,
    val activeSlotContributionIds: Set<String>
)

object ProfileCatalogue {
    val DEFAULT = SystemProfile(
        profileId = "default",
        displayName = "Standard Profile",
        description = "Balanced read/write workspace with outbound GitHub sync and instant user mutations.",
        requireApprovalForUserDeletes = false,
        allowOutboundNetworkByDefault = true,
        readOnlyMode = false,
        storageCodecName = "plain-utf8",
        activeSlotContributionIds = setOf("ext.word-count", "ext.commit-stats")
    )

    val PROTECTED_AUDIT = SystemProfile(
        profileId = "protected",
        displayName = "Protected Warden Profile",
        description = "Strict Warden approval gate on destructive mutations + sealed storage codec.",
        requireApprovalForUserDeletes = true,
        allowOutboundNetworkByDefault = true,
        readOnlyMode = false,
        storageCodecName = "checksum-sealed",
        activeSlotContributionIds = setOf("ext.word-count", "ext.commit-stats", "ext.security-guard")
    )

    val OFFLINE_AIRGAPPED = SystemProfile(
        profileId = "airgapped",
        displayName = "Air-Gapped Local Profile",
        description = "Blocks all outbound network calls via Outbound Policy gate; serves durable + cached truth only.",
        requireApprovalForUserDeletes = false,
        allowOutboundNetworkByDefault = false,
        readOnlyMode = false,
        storageCodecName = "plain-utf8",
        activeSlotContributionIds = setOf("ext.word-count")
    )

    val AllProfiles: List<SystemProfile> = listOf(DEFAULT, PROTECTED_AUDIT, OFFLINE_AIRGAPPED)
}

/**
 * Machine-checkable Catalogue Validator (Section 16).
 */
object CatalogueValidator {
    data class ValidationReport(
        val isValid: Boolean,
        val commandCount: Int,
        val topicCount: Int,
        val slotCount: Int,
        val capabilityCount: Int,
        val violations: List<String>
    )

    fun validateAll(): ValidationReport {
        val violations = mutableListOf<String>()
        val seenCommands = HashSet<String>()

        for (cmd in CommandCatalogue.AllCoreCommands) {
            if (!seenCommands.add(cmd.id)) {
                violations.add("Duplicate command ID: ${cmd.id}")
            }
            if (!cmd.id.contains(".")) {
                violations.add("Command ID must follow domain.verbObject grammar: ${cmd.id}")
            }
        }

        val seenSlots = HashSet<String>()
        for (slot in SlotCatalogue.AllSlots) {
            if (!seenSlots.add(slot.slotId)) {
                violations.add("Duplicate slot ID: ${slot.slotId}")
            }
            if (slot.securityCritical && slot.allowedTiers != setOf(TrustTier.CORE)) {
                violations.add("Security-critical slot ${slot.slotId} must only allow CORE tier")
            }
        }

        for (topic in TopicCatalogue.CoalescableTopics) {
            if (!TopicCatalogue.AllTopics.contains(topic)) {
                violations.add("Coalescable topic $topic missing from TopicCatalogue.AllTopics")
            }
        }

        return ValidationReport(
            isValid = violations.isEmpty(),
            commandCount = seenCommands.size,
            topicCount = TopicCatalogue.AllTopics.size,
            slotCount = seenSlots.size,
            capabilityCount = Capability.entries.size,
            violations = violations
        )
    }
}
