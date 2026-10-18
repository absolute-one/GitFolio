package com.example.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.InlineMarkdownSpan
import com.example.domain.model.MarkdownAstBlock
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.TableColumnAlignment
import com.example.ui.ReaderLoadState
import com.example.ui.theme.CobaltAccent
import com.example.ui.theme.CoralRose
import com.example.ui.theme.CyberMint
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LavenderPop
import com.example.ui.theme.LoraFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownReaderScreen(
    loadState: ReaderLoadState,
    fontScale: Float,
    useSerifBody: Boolean,
    onBack: () -> Unit,
    onAdjustFontScale: (Float) -> Unit,
    onToggleBodyFont: () -> Unit
) {
    BackHandler(onBack = onBack)

    var showTocSheet by rememberSaveable { mutableStateOf(false) }
    var showTypographyBar by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val readingProgress by remember(loadState) {
        derivedStateOf {
            val ready = loadState as? ReaderLoadState.Ready ?: return@derivedStateOf 0f
            val total = ready.document.blocks.size.coerceAtLeast(1)
            val current = listState.firstVisibleItemIndex
            (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Minimalist Reader Header: Back Button + Page Title + Optional TOC/Font Trigger
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("reader_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        when (loadState) {
                            is ReaderLoadState.Ready -> {
                                val doc = loadState.document
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = doc.fileName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("reader_document_title")
                                    )
                                    Text(
                                        text = "${doc.filePath} · ~${doc.estimatedPages}p · ${doc.blocks.size} blocks",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = { showTypographyBar = !showTypographyBar },
                                    modifier = Modifier.testTag("reader_typography_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TextFields,
                                        contentDescription = stringResource(R.string.reader_typography),
                                        tint = if (showTypographyBar) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }

                                val uriHandler = LocalUriHandler.current
                                IconButton(
                                    onClick = {
                                        runCatching { uriHandler.openUri(doc.htmlBlobUrl) }
                                    },
                                    modifier = Modifier.testTag("reader_open_browser_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open file in browser",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (doc.tableOfContents.isNotEmpty()) {
                                    IconButton(
                                        onClick = { showTocSheet = true },
                                        modifier = Modifier.testTag("reader_toc_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                            contentDescription = stringResource(R.string.table_of_contents),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            else -> {
                                Text(
                                    text = "Document Reader",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (showTypographyBar) {
                        ReaderTypographyBar(
                            fontScale = fontScale,
                            useSerifBody = useSerifBody,
                            onDecrease = { onAdjustFontScale(-0.1f) },
                            onIncrease = { onAdjustFontScale(0.1f) },
                            onToggleFontFamily = onToggleBodyFont
                        )
                    }

                    LinearProgressIndicator(
                        progress = { readingProgress },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }
            }

            // Reader Page Content
            when (loadState) {
                ReaderLoadState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Parsing CommonMark AST…",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is ReaderLoadState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = loadState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = onBack) {
                                Text("Return to Repository Tree")
                            }
                        }
                    }
                }

                is ReaderLoadState.Ready -> {
                    val doc = loadState.document
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        SelectionContainer {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(
                                    start = 20.dp,
                                    end = 20.dp,
                                    top = 20.dp,
                                    bottom = 96.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier
                                    .widthIn(max = 760.dp)
                                    .fillMaxSize()
                                    .testTag("markdown_virtualized_list")
                            ) {
                                itemsIndexed(
                                    items = doc.blocks,
                                    key = { _, block -> block.blockId },
                                    contentType = { _, block -> block::class.simpleName }
                                ) { _, block ->
                                    MarkdownAstBlockView(
                                        block = block,
                                        fontScale = fontScale,
                                        useSerifBody = useSerifBody
                                    )
                                }
                            }
                        }
                    }

                    if (showTocSheet) {
                        TableOfContentsBottomSheet(
                            document = doc,
                            onDismiss = { showTocSheet = false },
                            onSelectHeading = { targetBlockIndex ->
                                showTocSheet = false
                                coroutineScope.launch {
                                    listState.scrollToItem(targetBlockIndex)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTypographyBar(
    fontScale: Float,
    useSerifBody: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onToggleFontFamily: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = onDecrease,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = "A-",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Text(
                    text = "${(fontScale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    onClick = onIncrease,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = "A+",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Surface(
                onClick = onToggleFontFamily,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = if (useSerifBody) "Font: Lora Serif" else "Font: Space Grotesk",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun MarkdownAstBlockView(
    block: MarkdownAstBlock,
    fontScale: Float,
    useSerifBody: Boolean
) {
    val bodyFontFamily = if (useSerifBody) LoraFontFamily else SpaceGroteskFontFamily
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.primary
    val linkColor = CyberMint

    when (block) {
        is MarkdownAstBlock.HeadingBlock -> {
            val baseSize = when (block.level) {
                1 -> 28f
                2 -> 22f
                3 -> 19f
                4 -> 17f
                5 -> 15.5f
                else -> 14.5f
            } * fontScale

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (block.level <= 2) 10.dp else 4.dp)
            ) {
                val styled = remember(block.inlineSpans, codeBg, codeFg, linkColor) {
                    buildInlineAnnotatedString(block.inlineSpans, codeBg, codeFg, linkColor)
                }
                Text(
                    text = styled,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = baseSize.sp,
                    lineHeight = (baseSize * 1.28f).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (block.level <= 2) {
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }

        is MarkdownAstBlock.ParagraphBlock -> {
            val styled = remember(block.inlineSpans, codeBg, codeFg, linkColor) {
                buildInlineAnnotatedString(block.inlineSpans, codeBg, codeFg, linkColor)
            }
            val fontSize = (16.5f * fontScale).sp
            Text(
                text = styled,
                fontFamily = bodyFontFamily,
                fontSize = fontSize,
                lineHeight = (27f * fontScale).sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.94f)
            )
        }

        is MarkdownAstBlock.CodeFenceBlock -> {
            CodeBlockVirtualizedCard(
                block = block,
                fontScale = fontScale
            )
        }

        is MarkdownAstBlock.BlockQuoteBlock -> {
            val accentColor = when (block.calloutType) {
                "IMPORTANT" -> LavenderPop
                "WARNING", "CAUTION" -> CoralRose
                "TIP" -> CyberMint
                "NOTE" -> CobaltAccent
                else -> ElectricAmber
            }

            Surface(
                shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp, topStart = 4.dp, bottomStart = 4.dp),
                color = accentColor.copy(alpha = 0.09f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .background(accentColor)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (block.calloutType != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val icon = when (block.calloutType) {
                                    "WARNING", "CAUTION" -> Icons.Default.Warning
                                    "TIP" -> Icons.Default.Lightbulb
                                    else -> Icons.Default.Info
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = block.calloutType,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            }
                        }

                        block.paragraphs.forEach { spans ->
                            val styled = remember(spans, codeBg, codeFg, linkColor) {
                                buildInlineAnnotatedString(spans, codeBg, codeFg, linkColor)
                            }
                            Text(
                                text = styled,
                                fontFamily = bodyFontFamily,
                                fontStyle = FontStyle.Italic,
                                fontSize = (15.5f * fontScale).sp,
                                lineHeight = (24f * fontScale).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        is MarkdownAstBlock.BulletListBlock -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (item.depth * 18).dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (item.taskChecked != null) {
                            Icon(
                                imageVector = if (item.taskChecked) {
                                    Icons.Default.CheckBox
                                } else {
                                    Icons.Default.CheckBoxOutlineBlank
                                },
                                contentDescription = null,
                                tint = if (item.taskChecked) CyberMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Text(
                                text = item.ordinalLabel,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = (14f * fontScale).sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .width(26.dp)
                                    .padding(top = 2.dp)
                            )
                        }

                        val styled = remember(item.spans, codeBg, codeFg, linkColor) {
                            buildInlineAnnotatedString(item.spans, codeBg, codeFg, linkColor)
                        }
                        Text(
                            text = styled,
                            fontFamily = bodyFontFamily,
                            fontSize = (16f * fontScale).sp,
                            lineHeight = (25f * fontScale).sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        is MarkdownAstBlock.TableBlockNode -> {
            MarkdownGfmTableCard(
                table = block,
                fontScale = fontScale,
                codeBg = codeBg,
                codeFg = codeFg,
                linkColor = linkColor
            )
        }

        is MarkdownAstBlock.ThematicBreakBlock -> {
            HorizontalDivider(
                thickness = 1.5.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        is MarkdownAstBlock.FileMetaBannerBlock -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = block.filePath,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${block.language.uppercase()} · ${block.totalLines} lines · ${block.note}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockVirtualizedCard(
    block: MarkdownAstBlock.CodeFenceBlock,
    fontScale: Float
) {
    val horizScroll = rememberScrollState()
    val highlighted = remember(block.codeLines, block.language) {
        highlightSourceLines(block.codeLines, block.language)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111319),
        border = BorderStroke(1.dp, Color(0xFF282D3C)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Code Header Bar (only shown on first slice of a multi-slice block or single block)
            if (block.startLineNumber == 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF191C26))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = block.language.uppercase(),
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricAmber
                    )
                    Text(
                        text = "${block.totalLinesInOriginalBlock} lines",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFF8B92A5)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizScroll)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Line numbers gutter
                val lineNumbersText = remember(block.startLineNumber, block.codeLines.size) {
                    (block.startLineNumber until (block.startLineNumber + block.codeLines.size))
                        .joinToString("\n") { it.toString().padStart(3, ' ') }
                }
                Text(
                    text = lineNumbersText,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = (12.5f * fontScale).sp,
                    lineHeight = (20f * fontScale).sp,
                    color = Color(0xFF565E74)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = highlighted,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = (12.5f * fontScale).sp,
                    lineHeight = (20f * fontScale).sp,
                    color = Color(0xFFEDEFF5)
                )
            }
        }
    }
}

@Composable
private fun MarkdownGfmTableCard(
    table: MarkdownAstBlock.TableBlockNode,
    fontScale: Float,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color
) {
    val horizScroll = rememberScrollState()
    val columnCount = maxOf(
        table.headers.size,
        table.rows.maxOfOrNull { it.size } ?: 0
    ).coerceAtLeast(1)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizScroll)
        ) {
            // Header Row
            if (table.headers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(vertical = 8.dp)
                ) {
                    for (col in 0 until columnCount) {
                        val cellSpans = table.headers.getOrNull(col).orEmpty()
                        val align = table.alignments.getOrNull(col) ?: TableColumnAlignment.LEFT
                        val styled = remember(cellSpans, codeBg, codeFg, linkColor) {
                            buildInlineAnnotatedString(cellSpans, codeBg, codeFg, linkColor)
                        }
                        Text(
                            text = styled,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = (13.5f * fontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = when (align) {
                                TableColumnAlignment.CENTER -> TextAlign.Center
                                TableColumnAlignment.RIGHT -> TextAlign.End
                                TableColumnAlignment.LEFT -> TextAlign.Start
                            },
                            modifier = Modifier
                                .width(156.dp)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            // Body Rows
            table.rows.forEachIndexed { rowIdx, rowCells ->
                val rowBg = if (rowIdx % 2 == 1) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                }
                Row(
                    modifier = Modifier
                        .background(rowBg)
                        .padding(vertical = 7.dp)
                ) {
                    for (col in 0 until columnCount) {
                        val cellSpans = rowCells.getOrNull(col).orEmpty()
                        val align = table.alignments.getOrNull(col) ?: TableColumnAlignment.LEFT
                        val styled = remember(cellSpans, codeBg, codeFg, linkColor) {
                            buildInlineAnnotatedString(cellSpans, codeBg, codeFg, linkColor)
                        }
                        Text(
                            text = styled,
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = (13.5f * fontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = when (align) {
                                TableColumnAlignment.CENTER -> TextAlign.Center
                                TableColumnAlignment.RIGHT -> TextAlign.End
                                TableColumnAlignment.LEFT -> TextAlign.Start
                            },
                            modifier = Modifier
                                .width(156.dp)
                                .padding(horizontal = 12.dp, vertical = 3.dp)
                        )
                    }
                }
                if (rowIdx < table.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableOfContentsBottomSheet(
    document: ParsedMarkdownDocument,
    onDismiss: () -> Unit,
    onSelectHeading: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Table of Contents (${document.tableOfContents.size} headings)",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("toc_heading_list")
            ) {
                itemsIndexed(
                    items = document.tableOfContents,
                    key = { _, item -> item.blockId }
                ) { _, item ->
                    Surface(
                        onClick = { onSelectHeading(item.blockIndex) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = (12 + (item.level - 1) * 14).dp,
                                    end = 12.dp,
                                    top = 10.dp,
                                    bottom = 10.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "H${item.level}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (item.level <= 2) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildInlineAnnotatedString(
    spans: List<InlineMarkdownSpan>,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        fun appendSpans(items: List<InlineMarkdownSpan>) {
            for (span in items) {
                when (span) {
                    is InlineMarkdownSpan.PlainText -> append(span.text)
                    is InlineMarkdownSpan.BoldText -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendSpans(span.children)
                        }
                    }
                    is InlineMarkdownSpan.ItalicText -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendSpans(span.children)
                        }
                    }
                    is InlineMarkdownSpan.StrikethroughText -> {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            appendSpans(span.children)
                        }
                    }
                    is InlineMarkdownSpan.InlineCode -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                background = codeBg,
                                color = codeFg,
                                fontSize = 13.5.sp
                            )
                        ) {
                            append(" ${span.code} ")
                        }
                    }
                    is InlineMarkdownSpan.Hyperlink -> {
                        val dest = span.destination
                        if (dest.startsWith("http://") || dest.startsWith("https://")) {
                            withLink(
                                LinkAnnotation.Url(
                                    url = dest,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                )
                            ) {
                                appendSpans(span.children)
                            }
                        } else {
                            withStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                appendSpans(span.children)
                            }
                        }
                    }
                    is InlineMarkdownSpan.ImageBadge -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                background = codeBg,
                                color = LavenderPop,
                                fontSize = 12.sp
                            )
                        ) {
                            append(" [IMG: ${span.altText}] ")
                        }
                    }
                }
            }
        }
        appendSpans(spans)
    }
}

private val KEYWORD_REGEX = Regex(
    "\\b(fun|val|var|class|interface|object|suspend|return|if|else|when|for|while|in|is|package|import|pub|fn|let|mut|struct|impl|use|def|from|async|await|public|private|sealed|record|namespace|using|true|false|null)\\b"
)
private val STRING_REGEX = Regex("\"([^\"\\\\]|\\\\.)*\"")

private fun highlightSourceLines(lines: List<String>, language: String): AnnotatedString {
    val joined = lines.joinToString("\n")
    return buildAnnotatedString {
        append(joined)

        lines.forEachIndexed { idx, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || (trimmed.startsWith("#") && language != "markdown")) {
                val startOffset = lines.take(idx).sumOf { it.length + 1 }
                addStyle(
                    style = SpanStyle(color = Color(0xFF6B738A), fontStyle = FontStyle.Italic),
                    start = startOffset,
                    end = startOffset + line.length
                )
            }
        }

        KEYWORD_REGEX.findAll(joined).forEach { match ->
            addStyle(
                style = SpanStyle(color = Color(0xFFFF8552), fontWeight = FontWeight.SemiBold),
                start = match.range.first,
                end = match.range.last + 1
            )
        }

        STRING_REGEX.findAll(joined).forEach { match ->
            addStyle(
                style = SpanStyle(color = Color(0xFF4ECDC4)),
                start = match.range.first,
                end = match.range.last + 1
            )
        }
    }
}
