package com.example.data.engine

import com.example.domain.model.InlineMarkdownSpan
import com.example.domain.model.ListItemEntry
import com.example.domain.model.MarkdownAstBlock
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.RawDocumentPayload
import com.example.domain.model.RepoFileNode
import com.example.domain.model.TableColumnAlignment
import com.example.domain.model.TocHeadingItem
import com.example.domain.port.MarkdownEnginePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TableBlock as CommonMarkTableBlock

/**
 * High-performance CommonMark AST Document Engine.
 * Parses Markdown and source code files into virtualized AST blocks so even 200+ page documents
 * render smoothly inside Jetpack Compose LazyColumn.
 */
class CommonMarkDocumentEngine : MarkdownEnginePort {

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        AutolinkExtension.create(),
        TaskListItemsExtension.create()
    )

    private val parser: Parser = Parser.builder()
        .extensions(extensions)
        .build()

    override suspend fun parseToVirtualizedAst(
        payload: RawDocumentPayload,
        node: RepoFileNode,
        forceRawMarkdownTreatment: Boolean
    ): ParsedMarkdownDocument = withContext(Dispatchers.Default) {
        val rawText = payload.rawContent.replace("\r\n", "\n")
        val lines = rawText.lines()
        val totalLines = lines.size
        val totalWords = countWords(rawText)
        // Roughly 275 words or 45 lines per standard book/technical page
        val estimatedPages = maxOf(1, maxOf(totalWords / 275, totalLines / 45))

        val treatAsPureMarkdown = forceRawMarkdownTreatment ||
            node.isPureMarkdown ||
            node.name.uppercase().startsWith("README") ||
            node.name.uppercase().startsWith("CHANGELOG") ||
            node.name.uppercase().startsWith("CONTRIBUTING")

        val isLicenseFile = node.name.uppercase().startsWith("LICENSE") ||
            node.name.uppercase().startsWith("NOTICE") ||
            node.name.uppercase().startsWith("COPYING")

        val blocks = ArrayList<MarkdownAstBlock>(minOf(lines.size + 8, 4096))
        val toc = ArrayList<TocHeadingItem>()
        var blockCounter = 0

        fun nextId(prefix: String): String {
            blockCounter++
            return "${prefix}_$blockCounter"
        }

        if (!treatAsPureMarkdown && !isLicenseFile) {
            // Source code or configuration file (.py, .kt, .cs, .rs, .json, .toml, etc.)
            // Rendered inside the Markdown Reader with a metadata header + virtualized code slices!
            blocks.add(
                MarkdownAstBlock.FileMetaBannerBlock(
                    blockId = nextId("meta"),
                    filePath = payload.path,
                    language = node.languageHint,
                    totalLines = totalLines,
                    sizeBytes = payload.sizeBytes,
                    note = "Source file rendered in GitFolio Markdown Reader mode"
                )
            )

            val chunkSize = 70
            var startIdx = 0
            while (startIdx < lines.size) {
                val endIdx = minOf(startIdx + chunkSize, lines.size)
                val slice = lines.subList(startIdx, endIdx)
                blocks.add(
                    MarkdownAstBlock.CodeFenceBlock(
                        blockId = nextId("code_slice"),
                        language = node.languageHint,
                        codeLines = slice,
                        startLineNumber = startIdx + 1,
                        totalLinesInOriginalBlock = totalLines
                    )
                )
                startIdx = endIdx
            }

            return@withContext ParsedMarkdownDocument(
                repoSlug = payload.repoSlug,
                branch = payload.branch,
                filePath = payload.path,
                fileName = payload.fileName,
                blocks = blocks,
                tableOfContents = toc,
                totalWords = totalWords,
                totalLines = totalLines,
                estimatedPages = estimatedPages,
                isWrappedSourceFile = true,
                fetchedLive = payload.fetchedLive
            )
        }

        // For LICENSE or pure Markdown files, parse via CommonMark AST engine
        val markdownInput = if (isLicenseFile && !node.isPureMarkdown) {
            buildString {
                appendLine("# ${payload.fileName}")
                appendLine()
                appendLine("> **Repository Legal & License Document** · `${payload.repoSlug}` (`${payload.branch}`)")
                appendLine()
                append(rawText)
            }
        } else {
            rawText
        }

        val document: Node = parser.parse(markdownInput)
        var child = document.firstChild
        while (child != null) {
             convertTopLevelNode(
                node = child,
                blocks = blocks,
                toc = toc,
                nextId = ::nextId
            )
            child = child.next
        }

        if (blocks.isEmpty()) {
            blocks.add(
                MarkdownAstBlock.ParagraphBlock(
                    blockId = nextId("empty"),
                    inlineSpans = listOf(InlineMarkdownSpan.ItalicText(listOf(InlineMarkdownSpan.PlainText("(Empty document)"))))
                )
            )
        }

        ParsedMarkdownDocument(
            repoSlug = payload.repoSlug,
            branch = payload.branch,
            filePath = payload.path,
            fileName = payload.fileName,
            blocks = blocks,
            tableOfContents = toc,
            totalWords = totalWords,
            totalLines = totalLines,
            estimatedPages = estimatedPages,
            isWrappedSourceFile = !treatAsPureMarkdown && !isLicenseFile,
            fetchedLive = payload.fetchedLive
        )
    }

    private fun convertTopLevelNode(
        node: Node,
        blocks: MutableList<MarkdownAstBlock>,
        toc: MutableList<TocHeadingItem>,
        nextId: (String) -> String
    ) {
        when (node) {
            is Heading -> {
                val spans = extractInlineSpans(node)
                val plain = extractPlainText(node).trim()
                val slug = plain.lowercase()
                    .replace(Regex("[^a-z0-9\\s-]"), "")
                    .trim()
                    .replace(Regex("\\s+"), "-")
                val blockId = nextId("h${node.level}")
                val blockIndex = blocks.size
                blocks.add(
                    MarkdownAstBlock.HeadingBlock(
                        blockId = blockId,
                        level = node.level.coerceIn(1, 6),
                        plainText = plain,
                        inlineSpans = spans,
                        anchorSlug = slug
                    )
                )
                if (plain.isNotBlank()) {
                    toc.add(
                        TocHeadingItem(
                            blockIndex = blockIndex,
                            blockId = blockId,
                            level = node.level.coerceIn(1, 6),
                            title = plain
                        )
                    )
                }
            }

            is Paragraph -> {
                val spans = extractInlineSpans(node)
                if (spans.isNotEmpty()) {
                    blocks.add(
                        MarkdownAstBlock.ParagraphBlock(
                            blockId = nextId("p"),
                            inlineSpans = spans
                        )
                    )
                }
            }

            is FencedCodeBlock -> {
                val lang = node.info?.trim()?.substringBefore(' ')?.lowercase().orEmpty().ifBlank { "text" }
                val rawCode = node.literal?.trimEnd('\n', '\r') ?: ""
                val codeLines = if (rawCode.isEmpty()) listOf("") else rawCode.lines()
                appendVirtualizedCodeBlocks(
                    language = lang,
                    codeLines = codeLines,
                    blocks = blocks,
                    nextId = nextId
                )
            }

            is IndentedCodeBlock -> {
                val rawCode = node.literal?.trimEnd('\n', '\r') ?: ""
                val codeLines = if (rawCode.isEmpty()) listOf("") else rawCode.lines()
                appendVirtualizedCodeBlocks(
                    language = "text",
                    codeLines = codeLines,
                    blocks = blocks,
                    nextId = nextId
                )
            }

            is BlockQuote -> {
                val paragraphs = mutableListOf<List<InlineMarkdownSpan>>()
                var calloutType: String? = null
                var qChild = node.firstChild
                while (qChild != null) {
                    val spans = extractInlineSpans(qChild)
                    if (spans.isNotEmpty()) {
                        if (paragraphs.isEmpty()) {
                            val firstText = extractPlainText(qChild).trim()
                            val calloutMatch = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\\s*(.*)", RegexOption.IGNORE_CASE)
                                .find(firstText)
                            if (calloutMatch != null) {
                                calloutType = calloutMatch.groupValues[1].uppercase()
                                val remainder = calloutMatch.groupValues[2].trim()
                                if (remainder.isNotEmpty()) {
                                    paragraphs.add(listOf(InlineMarkdownSpan.PlainText(remainder)))
                                }
                            } else {
                                paragraphs.add(spans)
                            }
                        } else {
                            paragraphs.add(spans)
                        }
                    }
                    qChild = qChild.next
                }
                if (paragraphs.isNotEmpty() || calloutType != null) {
                    blocks.add(
                        MarkdownAstBlock.BlockQuoteBlock(
                            blockId = nextId("quote"),
                            calloutType = calloutType,
                            paragraphs = paragraphs
                        )
                    )
                }
            }

            is BulletList -> {
                val items = mutableListOf<ListItemEntry>()
                collectListItems(
                    listNode = node,
                    depth = 0,
                    ordered = false,
                    startNumber = 1,
                    out = items
                )
                if (items.isNotEmpty()) {
                    // Chunk very large lists into groups of 25 items for smooth LazyColumn virtualization
                    items.chunked(25).forEach { chunk ->
                        blocks.add(
                            MarkdownAstBlock.BulletListBlock(
                                blockId = nextId("ul"),
                                ordered = false,
                                startNumber = 1,
                                items = chunk
                            )
                        )
                    }
                }
            }

            is OrderedList -> {
                val startNum = node.markerStartNumber ?: 1
                val items = mutableListOf<ListItemEntry>()
                collectListItems(
                    listNode = node,
                    depth = 0,
                    ordered = true,
                    startNumber = startNum,
                    out = items
                )
                if (items.isNotEmpty()) {
                    items.chunked(25).forEachIndexed { chunkIdx, chunk ->
                        blocks.add(
                            MarkdownAstBlock.BulletListBlock(
                                blockId = nextId("ol"),
                                ordered = true,
                                startNumber = startNum + (chunkIdx * 25),
                                items = chunk
                            )
                        )
                    }
                }
            }

            is CommonMarkTableBlock -> {
                val parsedTable = parseTableNode(node, nextId("table"))
                if (parsedTable != null) {
                    blocks.add(parsedTable)
                }
            }

            is ThematicBreak -> {
                blocks.add(MarkdownAstBlock.ThematicBreakBlock(blockId = nextId("hr")))
            }

            is HtmlBlock -> {
                val cleaned = node.literal
                    ?.replace(Regex("<[^>]+>"), " ")
                    ?.replace("&nbsp;", " ")
                    ?.replace("&amp;", "&")
                    ?.replace("&lt;", "<")
                    ?.replace("&gt;", ">")
                    ?.trim()
                    .orEmpty()
                if (cleaned.isNotBlank()) {
                    blocks.add(
                        MarkdownAstBlock.ParagraphBlock(
                            blockId = nextId("html"),
                            inlineSpans = listOf(InlineMarkdownSpan.PlainText(cleaned))
                        )
                    )
                }
            }

            else -> {
                val fallbackText = extractPlainText(node).trim()
                if (fallbackText.isNotEmpty()) {
                    blocks.add(
                        MarkdownAstBlock.ParagraphBlock(
                            blockId = nextId("node"),
                            inlineSpans = listOf(InlineMarkdownSpan.PlainText(fallbackText))
                        )
                    )
                }
            }
        }
    }

    private fun appendVirtualizedCodeBlocks(
        language: String,
        codeLines: List<String>,
        blocks: MutableList<MarkdownAstBlock>,
        nextId: (String) -> String
    ) {
        val chunkSize = 75
        val total = codeLines.size
        var idx = 0
        while (idx < total) {
            val end = minOf(idx + chunkSize, total)
            blocks.add(
                MarkdownAstBlock.CodeFenceBlock(
                    blockId = nextId("code"),
                    language = language,
                    codeLines = codeLines.subList(idx, end),
                    startLineNumber = idx + 1,
                    totalLinesInOriginalBlock = total
                )
            )
            idx = end
        }
    }

    private fun collectListItems(
        listNode: Node,
        depth: Int,
        ordered: Boolean,
        startNumber: Int,
        out: MutableList<ListItemEntry>
    ) {
        var index = startNumber
        var itemNode = listNode.firstChild
        while (itemNode != null) {
            if (itemNode is ListItem) {
                var taskChecked: Boolean? = null
                val itemSpans = mutableListOf<InlineMarkdownSpan>()

                var child = itemNode.firstChild
                while (child != null) {
                    when (child) {
                        is TaskListItemMarker -> {
                            taskChecked = child.isChecked
                        }
                        is Paragraph -> {
                            // Also check if TaskListItemMarker is inside paragraph or before it
                            var pChild = child.firstChild
                            if (pChild is TaskListItemMarker) {
                                taskChecked = pChild.isChecked
                                pChild = pChild.next
                            }
                            while (pChild != null) {
                                itemSpans.addAll(convertInlineNode(pChild))
                                pChild = pChild.next
                            }
                        }
                        is BulletList -> {
                            if (itemSpans.isNotEmpty()) {
                                out.add(
                                    ListItemEntry(
                                        depth = depth,
                                        ordinalLabel = if (ordered) "$index." else "•",
                                        taskChecked = taskChecked,
                                        spans = itemSpans.toList()
                                    )
                                )
                                itemSpans.clear()
                            }
                            collectListItems(child, depth + 1, false, 1, out)
                        }
                        is OrderedList -> {
                            if (itemSpans.isNotEmpty()) {
                                out.add(
                                    ListItemEntry(
                                        depth = depth,
                                        ordinalLabel = if (ordered) "$index." else "•",
                                        taskChecked = taskChecked,
                                        spans = itemSpans.toList()
                                    )
                                )
                                itemSpans.clear()
                            }
                            collectListItems(child, depth + 1, true, child.markerStartNumber ?: 1, out)
                        }
                        else -> {
                            itemSpans.addAll(extractInlineSpans(child))
                        }
                    }
                    child = child.next
                }

                if (itemSpans.isNotEmpty()) {
                    val bulletSymbol = when (depth % 3) {
                        0 -> "•"
                        1 -> "◦"
                        else -> "▪"
                    }
                    out.add(
                        ListItemEntry(
                            depth = depth,
                            ordinalLabel = if (ordered) "$index." else bulletSymbol,
                            taskChecked = taskChecked,
                            spans = itemSpans
                        )
                    )
                }
                index++
            }
            itemNode = itemNode.next
        }
    }

    private fun parseTableNode(
        tableNode: CommonMarkTableBlock,
        blockId: String
    ): MarkdownAstBlock.TableBlockNode? {
        val headers = mutableListOf<List<InlineMarkdownSpan>>()
        val alignments = mutableListOf<TableColumnAlignment>()
        val rows = mutableListOf<List<List<InlineMarkdownSpan>>>()

        var section = tableNode.firstChild
        while (section != null) {
            when (section) {
                is TableHead -> {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) {
                                    headers.add(extractInlineSpans(cell))
                                    val align = when (cell.alignment) {
                                        TableCell.Alignment.CENTER -> TableColumnAlignment.CENTER
                                        TableCell.Alignment.RIGHT -> TableColumnAlignment.RIGHT
                                        else -> TableColumnAlignment.LEFT
                                    }
                                    alignments.add(align)
                                }
                                cell = cell.next
                            }
                        }
                        row = row.next
                    }
                }
                is TableBody -> {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            val rowCells = mutableListOf<List<InlineMarkdownSpan>>()
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) {
                                    rowCells.add(extractInlineSpans(cell))
                                }
                                cell = cell.next
                            }
                            if (rowCells.isNotEmpty()) {
                                rows.add(rowCells)
                            }
                        }
                        row = row.next
                    }
                }
            }
            section = section.next
        }

        if (headers.isEmpty() && rows.isEmpty()) return null
        return MarkdownAstBlock.TableBlockNode(
            blockId = blockId,
            headers = headers,
            alignments = alignments,
            rows = rows
        )
    }

    private fun extractInlineSpans(parent: Node): List<InlineMarkdownSpan> {
        val result = mutableListOf<InlineMarkdownSpan>()
        var child = parent.firstChild
        while (child != null) {
            result.addAll(convertInlineNode(child))
            child = child.next
        }
        return result
    }

    private fun convertInlineNode(node: Node): List<InlineMarkdownSpan> {
        return when (node) {
            is Text -> listOf(InlineMarkdownSpan.PlainText(node.literal ?: ""))
            is Code -> listOf(InlineMarkdownSpan.InlineCode(node.literal ?: ""))
            is SoftLineBreak -> listOf(InlineMarkdownSpan.PlainText(" "))
            is HardLineBreak -> listOf(InlineMarkdownSpan.PlainText("\n"))
            is StrongEmphasis -> listOf(InlineMarkdownSpan.BoldText(extractInlineSpans(node)))
            is Emphasis -> listOf(InlineMarkdownSpan.ItalicText(extractInlineSpans(node)))
            is Strikethrough -> listOf(InlineMarkdownSpan.StrikethroughText(extractInlineSpans(node)))
            is Link -> listOf(
                InlineMarkdownSpan.Hyperlink(
                    destination = node.destination ?: "",
                    title = node.title,
                    children = extractInlineSpans(node).ifEmpty {
                        listOf(InlineMarkdownSpan.PlainText(node.destination ?: "link"))
                    }
                )
            )
            is Image -> {
                val alt = extractPlainText(node).ifBlank { node.title ?: "Image" }
                listOf(InlineMarkdownSpan.ImageBadge(altText = alt, url = node.destination ?: ""))
            }
            is HtmlInline -> {
                val literal = node.literal?.lowercase().orEmpty()
                if (literal == "<br>" || literal == "<br/>" || literal == "<br />") {
                    listOf(InlineMarkdownSpan.PlainText("\n"))
                } else {
                    emptyList()
                }
            }
            else -> extractInlineSpans(node)
        }
    }

    private fun extractPlainText(node: Node): String {
        val sb = StringBuilder()
        fun walk(current: Node) {
            when (current) {
                is Text -> sb.append(current.literal ?: "")
                is Code -> sb.append(current.literal ?: "")
                is SoftLineBreak, is HardLineBreak -> sb.append(" ")
                else -> {
                    var c = current.firstChild
                    while (c != null) {
                        walk(c)
                        c = c.next
                    }
                }
            }
        }
        walk(node)
        return sb.toString()
    }

    private fun countWords(text: String): Int {
        var count = 0
        var inWord = false
        for (i in text.indices) {
            val c = text[i]
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }
}
