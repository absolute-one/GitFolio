package com.example.domain.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Pure Domain Models following Clean Architecture & SOLID Principles.
 * Annotated with Compose @Immutable / @Stable so Jetpack Compose can skip
 * unnecessary recompositions across lists, trees, and graph rows.
 */

@Immutable
enum class GitHostProvider(
    val providerId: String,
    val displayName: String,
    val baseDomain: String,
    val apiBaseUrl: String,
    val isEnabledInCurrentRelease: Boolean,
    val tokenPlaceholder: String,
    val protocolSubtitle: String
) {
    GITHUB(
        providerId = "github",
        displayName = "GitHub",
        baseDomain = "github.com",
        apiBaseUrl = "https://api.github.com",
        isEnabledInCurrentRelease = true,
        tokenPlaceholder = "ghp_... or github_pat_...",
        protocolSubtitle = "Active · OAuth / Personal Access Token"
    ),
    GITLAB(
        providerId = "gitlab",
        displayName = "GitLab",
        baseDomain = "gitlab.com",
        apiBaseUrl = "https://gitlab.com/api/v4",
        isEnabledInCurrentRelease = false,
        tokenPlaceholder = "glpat-...",
        protocolSubtitle = "Planned · GitLab REST v4 Adapter"
    ),
    CODEBERG(
        providerId = "codeberg",
        displayName = "Codeberg",
        baseDomain = "codeberg.org",
        apiBaseUrl = "https://codeberg.org/api/v1",
        isEnabledInCurrentRelease = false,
        tokenPlaceholder = "Forgejo / Gitea access token",
        protocolSubtitle = "Planned · Forgejo / Codeberg Adapter"
    );

    companion object {
        fun fromId(id: String): GitHostProvider {
            return entries.firstOrNull { it.providerId.equals(id, ignoreCase = true) } ?: GITHUB
        }
    }
}

@Immutable
data class ProviderCredential(
    val provider: GitHostProvider,
    val accessToken: String,
    val updatedAtEpochMs: Long
) {
    val isConfigured: Boolean get() = accessToken.isNotBlank()
}

/**
 * Result of Step 1 in the progressive repository addition flow:
 * Verifies if the repository is accessible (publicly or via OAuth) and returns its branches
 * along with its default branch pre-selected.
 */
@Immutable
data class RepoAccessibilityProbe(
    val owner: String,
    val name: String,
    val description: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val branches: List<String>,
    val authenticatedViaOAuth: Boolean
) {
    val fullSlug: String get() = "$owner/$name"
}

@Immutable
enum class BentoTileStyle(val spanColumns: Int, val minHeightDp: Int) {
    HERO_WIDE(spanColumns = 2, minHeightDp = 176),
    TALL_ACCENT(spanColumns = 1, minHeightDp = 188),
    COMPACT_SQUARE(spanColumns = 1, minHeightDp = 164)
}

@Immutable
data class GitRepositoryCard(
    val id: String,
    val provider: GitHostProvider = GitHostProvider.GITHUB,
    val owner: String,
    val name: String,
    val defaultBranch: String, // Currently active/selected branch
    val availableBranches: List<String> = listOf(defaultBranch),
    val description: String,
    val totalFilesCount: Int,
    val markdownFilesCount: Int,
    val lastSyncedEpochMs: Long,
    val tileStyle: BentoTileStyle,
    val accentIndex: Int
) {
    val ownerHandle: String = "@$owner"
    val fullSlug: String = "$owner/$name"
    val htmlUrl: String = "https://${provider.baseDomain}/$owner/$name"
    val branchTreeHtmlUrl: String = "https://${provider.baseDomain}/$owner/$name/tree/$defaultBranch"
    val isPendingPrivateSync: Boolean = totalFilesCount == 0
    val filesSummaryLabel: String = if (isPendingPrivateSync) {
        "URL SAVED"
    } else {
        "$totalFilesCount files ($markdownFilesCount .md)"
    }
    val fileSummaryLabel: String get() = filesSummaryLabel
    val syncedTimeFormatted: String = formatEpochTimeHhMm(lastSyncedEpochMs)

    companion object {
        private fun formatEpochTimeHhMm(epochMs: Long): String {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = epochMs
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val m = cal.get(java.util.Calendar.MINUTE)
            val hStr = if (h < 10) "0$h" else h.toString()
            val mStr = if (m < 10) "0$m" else m.toString()
            return "$hStr:$mStr"
        }
    }
}

@Immutable
data class RepoFileNode(
    val id: String,
    val repoId: String,
    val path: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val sha: String,
    val depth: Int
) {
    val nameLower: String = name.lowercase()
    val pathLower: String = path.lowercase()
    val extension: String = name.substringAfterLast('.', "").lowercase()

    val isPureMarkdown: Boolean = MARKDOWN_EXTENSIONS.contains(extension)

    val isLegalDoc: Boolean =
        nameLower.startsWith("license") ||
            nameLower.startsWith("copying") ||
            nameLower.startsWith("notice")

    val isLicenseFile: Boolean get() = isLegalDoc

    val isLicenseOrReadme: Boolean =
        isLegalDoc ||
            nameLower.startsWith("readme") ||
            nameLower.startsWith("changelog") ||
            nameLower.startsWith("contributing")

    val badgeLabel: String = if (isDirectory) {
        "DIR"
    } else {
        extension.uppercase().ifBlank { "DOC" }
    }

    val extensionBadge: String get() = badgeLabel

    val formattedSize: String = formatBytesFast(sizeBytes)

    val languageHint: String
        get() = when {
            isPureMarkdown -> "markdown"
            isLegalDoc -> "text"
            extension == "kt" || extension == "kts" -> "kotlin"
            extension == "rs" -> "rust"
            extension == "py" -> "python"
            extension == "cs" -> "csharp"
            extension == "js" || extension == "mjs" -> "javascript"
            extension == "ts" || extension == "tsx" -> "typescript"
            extension == "java" -> "java"
            extension == "go" -> "go"
            extension == "c" || extension == "h" -> "c"
            extension == "cpp" || extension == "hpp" || extension == "cc" -> "cpp"
            extension == "json" -> "json"
            extension == "yml" || extension == "yaml" -> "yaml"
            extension == "toml" -> "toml"
            extension == "xml" -> "xml"
            extension == "sh" -> "bash"
            extension == "sql" -> "sql"
            extension == "dart" -> "dart"
            else -> "text"
        }

    companion object {
        private val MARKDOWN_EXTENSIONS = hashSetOf("md", "markdown", "mdx", "mkd", "mdown")

        private fun formatBytesFast(bytes: Long): String {
            if (bytes <= 0L) return ""
            if (bytes < 1024L) return "${bytes}B"
            if (bytes < 1024L * 1024L) {
                val tenths = (bytes * 10L) / 1024L
                return "${tenths / 10}.${tenths % 10}KB"
            }
            val tenths = (bytes * 10L) / (1024L * 1024L)
            return "${tenths / 10}.${tenths % 10}MB"
        }
    }
}

/**
 * Models for High-Performance Git Commit Graph (DAG) Visualization
 * Capable of virtualizing 1,000+ commits across 100+ branches at 120fps.
 */
@Immutable
data class GitCommitNode(
    val sha: String,
    val shortSha: String,
    val subject: String,
    val body: String,
    val authorName: String,
    val authorDateIso: String,
    val committedEpochMs: Long,
    val parentShas: List<String>,
    val branchNames: List<String>,
    val primaryBranch: String,
    val isBranchTip: Boolean
) {
    val shaLower: String = sha.lowercase()
    val shortShaLower: String = shortSha.lowercase()
    val subjectLower: String = subject.lowercase()
    val authorNameLower: String = authorName.lowercase()
    val primaryBranchLower: String = primaryBranch.lowercase()
    val branchNamesLower: List<String> = branchNames.map { it.lowercase() }
}

/**
 * Represents a single visual connection segment within a single commit row's Canvas slice.
 */
@Immutable
enum class GraphSegmentRole {
    /** Full vertical line from top (0f) to bottom (1f) of the row */
    FULL_PASS_THROUGH,
    /** Vertical line from top (0f) to node center (0.5f) */
    TOP_TO_CENTER,
    /** Vertical line from node center (0.5f) to bottom (1f) */
    CENTER_TO_BOTTOM,
    /** Smooth S-curve from node center (0.5f) at [fromLane] to bottom (1f) at [toLane] (merge or fork down) */
    CENTER_TO_BOTTOM_CURVE,
    /** Smooth S-curve from top (0f) at [fromLane] to node center (0.5f) at [toLane] (branch join from child) */
    TOP_TO_CENTER_CURVE
}

@Immutable
data class GitRowGraphSegment(
    val fromLane: Int,
    val toLane: Int,
    val colorIndex: Int,
    val role: GraphSegmentRole
)

@Immutable
data class GitGraphCommitRow(
    val rowIndex: Int,
    val commit: GitCommitNode,
    val commitLane: Int,
    val commitColorIndex: Int,
    val badgeLabel: String,
    val isHollowHeadNode: Boolean,
    val segments: List<GitRowGraphSegment>,
    val activeLaneCountInRow: Int
) {
    val badgeLabelLower: String = badgeLabel.lowercase()
    val shortBadgeSha: String = if (commit.shortSha.length <= 7) commit.shortSha else commit.shortSha.take(7)
    val shortSha7: String get() = shortBadgeSha
    val authorAndDateLabel: String = "· ${commit.authorName} (${commit.authorDateIso})"
    val authorAndDateSummary: String get() = authorAndDateLabel
    val estimatedTotalChars: Int =
        commit.subject.length + badgeLabel.length + authorAndDateLabel.length + 6
}

@Immutable
data class GitRepoCommitGraph(
    val repoId: String,
    val repoSlug: String,
    val activeBranch: String,
    val totalCommits: Int,
    val totalBranchesRepresented: Int,
    val maxConcurrentLanes: Int,
    val rows: List<GitGraphCommitRow>,
    val isHighScaleBenchmarkMode: Boolean,
    val isLiveFromGitHub: Boolean
) {
    val maxEstimatedRowChars: Int = rows.maxOfOrNull { it.estimatedTotalChars } ?: 64
}

@Immutable
data class RawDocumentPayload(
    val repoId: String,
    val repoSlug: String,
    val branch: String,
    val path: String,
    val fileName: String,
    val rawContent: String,
    val sizeBytes: Long,
    val sha: String,
    val fetchedLive: Boolean
)

@Stable
sealed interface MarkdownAstBlock {
    val blockId: String

    @Immutable
    data class HeadingBlock(
        override val blockId: String,
        val level: Int,
        val plainText: String,
        val inlineSpans: List<InlineMarkdownSpan>,
        val anchorSlug: String
    ) : MarkdownAstBlock

    @Immutable
    data class ParagraphBlock(
        override val blockId: String,
        val inlineSpans: List<InlineMarkdownSpan>
    ) : MarkdownAstBlock

    @Immutable
    data class CodeFenceBlock(
        override val blockId: String,
        val language: String,
        val codeLines: List<String>,
        val startLineNumber: Int,
        val totalLinesInOriginalBlock: Int
    ) : MarkdownAstBlock

    @Immutable
    data class BlockQuoteBlock(
        override val blockId: String,
        val calloutType: String?,
        val paragraphs: List<List<InlineMarkdownSpan>>
    ) : MarkdownAstBlock

    @Immutable
    data class BulletListBlock(
        override val blockId: String,
        val ordered: Boolean,
        val startNumber: Int,
        val items: List<ListItemEntry>
    ) : MarkdownAstBlock

    @Immutable
    data class TableBlockNode(
        override val blockId: String,
        val headers: List<List<InlineMarkdownSpan>>,
        val alignments: List<TableColumnAlignment>,
        val rows: List<List<List<InlineMarkdownSpan>>>
    ) : MarkdownAstBlock

    @Immutable
    data class ThematicBreakBlock(
        override val blockId: String
    ) : MarkdownAstBlock

    @Immutable
    data class FileMetaBannerBlock(
        override val blockId: String,
        val filePath: String,
        val language: String,
        val totalLines: Int,
        val sizeBytes: Long,
        val note: String
    ) : MarkdownAstBlock
}

@Immutable
enum class TableColumnAlignment {
    LEFT, CENTER, RIGHT
}

@Immutable
data class ListItemEntry(
    val depth: Int,
    val ordinalLabel: String,
    val taskChecked: Boolean?,
    val spans: List<InlineMarkdownSpan>
)

@Stable
sealed interface InlineMarkdownSpan {
    @Immutable
    data class PlainText(val text: String) : InlineMarkdownSpan
    @Immutable
    data class BoldText(val children: List<InlineMarkdownSpan>) : InlineMarkdownSpan
    @Immutable
    data class ItalicText(val children: List<InlineMarkdownSpan>) : InlineMarkdownSpan
    @Immutable
    data class StrikethroughText(val children: List<InlineMarkdownSpan>) : InlineMarkdownSpan
    @Immutable
    data class InlineCode(val code: String) : InlineMarkdownSpan
    @Immutable
    data class Hyperlink(
        val destination: String,
        val title: String?,
        val children: List<InlineMarkdownSpan>
    ) : InlineMarkdownSpan
    @Immutable
    data class ImageBadge(val altText: String, val url: String) : InlineMarkdownSpan
}

@Immutable
data class TocHeadingItem(
    val blockIndex: Int,
    val blockId: String,
    val level: Int,
    val title: String
)

@Immutable
data class ParsedMarkdownDocument(
    val repoSlug: String,
    val branch: String,
    val filePath: String,
    val fileName: String,
    val blocks: List<MarkdownAstBlock>,
    val tableOfContents: List<TocHeadingItem>,
    val totalWords: Int,
    val totalLines: Int,
    val estimatedPages: Int,
    val isWrappedSourceFile: Boolean,
    val fetchedLive: Boolean
) {
    val htmlBlobUrl: String
        get() = "https://github.com/$repoSlug/blob/$branch/$filePath"
}
