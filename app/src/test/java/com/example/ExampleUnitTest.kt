package com.example

import com.example.data.engine.CommonMarkDocumentEngine
import com.example.data.engine.GitGraphLayoutEngine
import com.example.data.remote.GitHubRestClient
import com.example.domain.model.GraphSegmentRole
import com.example.domain.model.MarkdownAstBlock
import com.example.domain.model.RawDocumentPayload
import com.example.domain.model.RepoFileNode
import com.example.domain.usecase.ComputeVisibleTreeNodesUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun parseAndExtractGitHubCoordinates_handlesShareTextAndUrls() {
        assertEquals(
            "git" to "git",
            GitHubRestClient.parseGitHubCoordinates("git/git")
        )
        assertEquals(
            "JetBrains" to "kotlin",
            GitHubRestClient.extractGitHubCoordinatesFromSharedText(
                "Check out this repo shared from browser: https://github.com/JetBrains/kotlin/tree/master!"
            )
        )
        assertEquals(
            "rust-lang" to "mdBook",
            GitHubRestClient.parseGitHubCoordinates("git@github.com:rust-lang/mdBook.git")
        )
    }

    @Test
    fun gitGraphLayoutEngine_computes1000CommitsAcross100BranchesOptimally() = runTest {
        val engine = GitGraphLayoutEngine()
        val commits = engine.generateHighScaleMultiBranchDag(
            repoSlug = "git/git",
            defaultBranch = "master",
            targetCommitCount = 1050,
            targetBranchCount = 112
        )
        assertEquals(1050, commits.size)

        val graph = engine.computeCommitGraphLayout(
            repoId = "git/git",
            repoSlug = "git/git",
            activeBranch = "master",
            commits = commits,
            totalBranchesCount = 112,
            isHighScaleBenchmarkMode = true,
            isLiveFromGitHub = false
        )

        assertEquals(1050, graph.rows.size)
        assertTrue(graph.totalBranchesRepresented >= 100)
        assertTrue(graph.maxConcurrentLanes >= 4)

        // Verify top showcase rows M, L, K, J have parallel lanes and S-curve fork/merge segments
        val rowM = graph.rows[0]
        val rowL = graph.rows[1]
        assertEquals("M", rowM.commit.shortSha)
        assertEquals("L", rowL.commit.shortSha)
        assertTrue(
            graph.rows.any { r ->
                r.segments.any {
                    it.role == GraphSegmentRole.CENTER_TO_BOTTOM_CURVE ||
                        it.role == GraphSegmentRole.TOP_TO_CENTER_CURVE
                }
            }
        )
    }

    @Test
    fun optimizedInRepoSearch_ranksExactPrefixAndFuzzyMatches() = runTest {
        val searchUseCase = ComputeVisibleTreeNodesUseCase()
        val nodes = listOf(
            RepoFileNode("1", "r", "README.md", "README.md", "", false, 100L, "s1", 0),
            RepoFileNode("2", "r", "docs/architecture-guide.md", "architecture-guide.md", "docs", false, 200L, "s2", 1),
            RepoFileNode("3", "r", "src/main/ParserCore.kt", "ParserCore.kt", "src/main", false, 300L, "s3", 2)
        )

        val docsArch = searchUseCase(nodes, emptySet(), "docs arch", markdownOnly = false)
        assertEquals(1, docsArch.size)
        assertEquals("docs/architecture-guide.md", docsArch.first().path)

        val fuzzyReadme = searchUseCase(nodes, emptySet(), "rmd", markdownOnly = false)
        assertTrue(fuzzyReadme.any { it.name == "README.md" })
    }

    @Test
    fun commonMarkEngine_parsesHeadingsTablesAndCodeFiles() = runTest {
        val engine = CommonMarkDocumentEngine()
        val mdNode = RepoFileNode(
            id = "repo:README.md",
            repoId = "repo",
            path = "README.md",
            name = "README.md",
            parentPath = "",
            isDirectory = false,
            sizeBytes = 256L,
            sha = "sha1",
            depth = 0
        )
        val payload = RawDocumentPayload(
            repoId = "repo",
            repoSlug = "git/git",
            branch = "master",
            path = "README.md",
            fileName = "README.md",
            rawContent = """
                # Main Title
                
                | Col A | Col B |
                | :--- | ---: |
                | Val 1 | Val 2 |
                
                - [x] Done task
            """.trimIndent(),
            sizeBytes = 256L,
            sha = "sha1",
            fetchedLive = true
        )

        val parsed = engine.parseToVirtualizedAst(payload, mdNode)
        assertEquals(1, parsed.tableOfContents.size)
        assertEquals("Main Title", parsed.tableOfContents.first().title)
        assertTrue(parsed.blocks.any { it is MarkdownAstBlock.TableBlockNode })
        assertTrue(parsed.blocks.any { it is MarkdownAstBlock.BulletListBlock })

        val csNode = mdNode.copy(id = "repo:Engine.cs", path = "src/Engine.cs", name = "Engine.cs")
        val csPayload = payload.copy(
            path = "src/Engine.cs",
            fileName = "Engine.cs",
            rawContent = "public class Engine {\n    public void Run() {}\n}"
        )
        val parsedCs = engine.parseToVirtualizedAst(csPayload, csNode)
        assertNotNull(parsedCs)
        assertTrue(parsedCs.isWrappedSourceFile)
        assertTrue(parsedCs.blocks.first() is MarkdownAstBlock.FileMetaBannerBlock)
    }
}
