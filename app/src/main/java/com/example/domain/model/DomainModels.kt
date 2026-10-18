package com.example.domain.model

/**
 * Pure Domain Models following Clean Architecture & SOLID Principles.
 * Zero Android framework dependencies.
 */

enum class BentoTileStyle(val spanColumns: Int, val minHeightDp: Int) {
    HERO_WIDE(spanColumns = 2, minHeightDp = 176),
    TALL_ACCENT(spanColumns = 1, minHeightDp = 188),
    COMPACT_SQUARE(spanColumns = 1, minHeightDp = 164)
}

data class GitRepositoryCard(
    val id: String,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val description: String,
    val repoSpecificToken: String, // Stored per-repository as requested
    val totalFilesCount: Int,
    val markdownFilesCount: Int,
    val lastSyncedEpochMs: Long,
    val tileStyle: BentoTileStyle,
    val accentIndex: Int
) {
    val fullSlug: String get() = "$owner/$name"
    val hasAuthToken: Boolean get() = repoSpecificToken.isNotBlank()
    val htmlUrl: String get() = "https://github.com/$owner/$name"
    val branchTreeHtmlUrl: String get() = "https://github.com/$owner/$name/tree/$defaultBranch"
}

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
    // Pre-computed lowercase fields for O(1) allocation-free in-repo search filtering
    val nameLower: String = name.lowercase()
    val pathLower: String = path.lowercase()
    val extension: String = name.substringAfterLast('.', "").lowercase()

    val isPureMarkdown: Boolean =
        extension in setOf("md", "markdown", "mdx", "mkd", "mdown")

    val isLicenseOrReadme: Boolean =
        nameLower.startsWith("readme") ||
            nameLower.startsWith("license") ||
            nameLower.startsWith("changelog") ||
            nameLower.startsWith("contributing")

    val languageHint: String
        get() = when {
            isPureMarkdown -> "markdown"
            nameLower.startsWith("license") -> "text"
            extension in setOf("kt", "kts") -> "kotlin"
            extension == "rs" -> "rust"
            extension == "py" -> "python"
            extension == "cs" -> "csharp"
            extension in setOf("js", "mjs") -> "javascript"
            extension in setOf("ts", "tsx") -> "typescript"
            extension == "java" -> "java"
            extension == "go" -> "go"
            extension in setOf("c", "h") -> "c"
            extension in setOf("cpp", "hpp", "cc") -> "cpp"
            extension == "json" -> "json"
            extension in setOf("yml", "yaml") -> "yaml"
            extension == "toml" -> "toml"
            extension == "xml" -> "xml"
            extension == "sh" -> "bash"
            extension == "sql" -> "sql"
            extension == "dart" -> "dart"
            else -> "text"
        }
}

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

/**
 * AST Block Representation produced by the CommonMark Document Engine.
 * Designed for virtualized LazyColumn rendering of 200+ page documents at 120 FPS.
 */
sealed interface MarkdownAstBlock {
    val blockId: String

    data class HeadingBlock(
        override val blockId: String,
        val level: Int,
        val plainText: String,
        val inlineSpans: List<InlineMarkdownSpan>,
        val anchorSlug: String
    ) : MarkdownAstBlock

    data class ParagraphBlock(
        override val blockId: String,
        val inlineSpans: List<InlineMarkdownSpan>
    ) : MarkdownAstBlock

    data class CodeFenceBlock(
        override val blockId: String,
        val language: String,
        val codeLines: List<String>,
        val startLineNumber: Int,
        val totalLinesInOriginalBlock: Int
    ) : MarkdownAstBlock

    data class BlockQuoteBlock(
        override val blockId: String,
        val calloutType: String?,
        val paragraphs: List<List<InlineMarkdownSpan>>
    ) : MarkdownAstBlock

    data class BulletListBlock(
        override val blockId: String,
        val ordered: Boolean,
        val startNumber: Int,
        val items: List<ListItemEntry>
    ) : MarkdownAstBlock

    data class TableBlockNode(
        override val blockId: String,
        val headers: List<List<InlineMarkdownSpan>>,
        val alignments: List<TableColumnAlignment>,
        val rows: List<List<List<InlineMarkdownSpan>>>
    ) : MarkdownAstBlock

    data class ThematicBreakBlock(
        override val blockId: String
    ) : MarkdownAstBlock

    data class FileMetaBannerBlock(
        override val blockId: String,
        val filePath: String,
        val language: String,
        val totalLines: Int,
        val sizeBytes: Long,
        val note: String
    ) : MarkdownAstBlock
}

enum class TableColumnAlignment {
    LEFT, CENTER, RIGHT
}

data class ListItemEntry(
    val depth: Int,
    val ordinalLabel: String,
    val taskChecked: Boolean?,
    val spans: List<InlineMarkdownSpan>
)

sealed interface InlineMarkdownSpan {
    data class PlainText(val text: String) : InlineMarkdownSpan
    data class BoldText(val children: List<InlineMarkdownSpan>) : InlineMarkdownSpan
    data class ItalicText(val children: List<InlineMarkdownSpan>) : InlineMarkdownSpan
    data class StrikethroughText(val children: List<InlineMarkdownSpan>) : InlineMarkdownSpan
    data class InlineCode(val code: String) : InlineMarkdownSpan
    data class Hyperlink(
        val destination: String,
        val title: String?,
        val children: List<InlineMarkdownSpan>
    ) : InlineMarkdownSpan
    data class ImageBadge(val altText: String, val url: String) : InlineMarkdownSpan
}

data class TocHeadingItem(
    val blockIndex: Int,
    val blockId: String,
    val level: Int,
    val title: String
)

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
