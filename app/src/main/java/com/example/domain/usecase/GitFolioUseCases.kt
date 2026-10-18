package com.example.domain.usecase

import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.RepoFileNode
import com.example.domain.port.DocumentReaderPort
import com.example.domain.port.FileTreeExplorerPort
import com.example.domain.port.MarkdownEnginePort
import com.example.domain.port.RepositoryCatalogPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Clean Architecture Use Cases orchestrating real GitHub repository operations and search.
 */
class ObserveRepositoriesUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    operator fun invoke(): Flow<List<GitRepositoryCard>> = catalogPort.observeRepositories()
}

class AddGitHubRepositoryUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    suspend operator fun invoke(
        rawRepoInput: String,
        branchOverride: String?,
        repoSpecificToken: String
    ): Result<GitRepositoryCard> {
        val trimmed = rawRepoInput.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a GitHub repository (e.g. owner/repo or URL)."))
        }
        return catalogPort.addGitHubRepository(
            rawRepoInput = trimmed,
            branchOverride = branchOverride?.trim()?.takeIf { it.isNotEmpty() },
            repoSpecificToken = repoSpecificToken.trim()
        )
    }
}

class RefreshRepositoryUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    suspend operator fun invoke(repoId: String): Result<GitRepositoryCard> {
        return catalogPort.refreshRepository(repoId)
    }
}

class RemoveRepositoryUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    suspend operator fun invoke(repoId: String) {
        catalogPort.removeRepository(repoId)
    }
}

class ObserveRepositoryTreeUseCase(
    private val catalogPort: RepositoryCatalogPort,
    private val treeExplorerPort: FileTreeExplorerPort
) {
    fun observe(repoId: String): Flow<List<RepoFileNode>> =
        treeExplorerPort.observeRepositoryTree(repoId)

    suspend fun getRepository(repoId: String): GitRepositoryCard? =
        catalogPort.getRepositoryById(repoId)

    suspend fun refreshTree(repoId: String, forceNetwork: Boolean): Result<List<RepoFileNode>> {
        val repo = catalogPort.getRepositoryById(repoId)
            ?: return Result.failure(IllegalStateException("Repository not found"))
        return treeExplorerPort.fetchOrRefreshTree(repo, forceNetwork)
    }
}

class OpenDocumentAsMarkdownUseCase(
    private val catalogPort: RepositoryCatalogPort,
    private val documentReaderPort: DocumentReaderPort,
    private val markdownEnginePort: MarkdownEnginePort
) {
    suspend operator fun invoke(
        repoId: String,
        node: RepoFileNode,
        forceRefresh: Boolean = false,
        forceRawMarkdownTreatment: Boolean = false
    ): Result<ParsedMarkdownDocument> {
        val repo = catalogPort.getRepositoryById(repoId)
            ?: return Result.failure(IllegalStateException("Repository not found"))

        return documentReaderPort.loadDocumentContent(repo, node, forceRefresh).mapCatching { payload ->
            markdownEnginePort.parseToVirtualizedAst(
                payload = payload,
                node = node,
                forceRawMarkdownTreatment = forceRawMarkdownTreatment
            )
        }
    }
}

/**
 * High-performance In-Repository Search & Tree Flattening UseCase.
 * Runs on Dispatchers.Default with multi-tier relevance scoring:
 * 1. Exact filename match
 * 2. Filename prefix match
 * 3. Filename substring match
 * 4. Multi-token path segment match (e.g. "docs arch" -> "docs/architecture.md")
 * 5. Fuzzy subsequence match (e.g. "rmd" -> "README.md")
 */
class ComputeVisibleTreeNodesUseCase {

    suspend operator fun invoke(
        allNodes: List<RepoFileNode>,
        expandedFolders: Set<String>,
        rawQuery: String,
        markdownOnly: Boolean
    ): List<RepoFileNode> = withContext(Dispatchers.Default) {
        val query = rawQuery.trim().lowercase()

        if (query.isEmpty() && !markdownOnly) {
            return@withContext buildHierarchicalTree(allNodes, expandedFolders)
        }

        val queryTokens = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val scoredResults = ArrayList<Pair<RepoFileNode, Int>>(allNodes.size)

        for (node in allNodes) {
            if (markdownOnly) {
                if (node.isDirectory || (!node.isPureMarkdown && !node.isLicenseOrReadme)) {
                    continue
                }
            }

            if (query.isEmpty()) {
                // Markdown-only mode with no query: show markdown & license files sorted by depth then name
                val baseScore = if (node.isLicenseOrReadme) 200 else 100
                scoredResults.add(node to (baseScore - node.depth))
                continue
            }

            val score = scoreNodeMatch(node, query, queryTokens)
            if (score > 0) {
                scoredResults.add(node to score)
            }
        }

        scoredResults.sortWith(
            compareByDescending<Pair<RepoFileNode, Int>> { it.second }
                .thenBy { it.first.depth }
                .thenBy { it.first.nameLower }
        )

        scoredResults.map { it.first }
    }

    private fun scoreNodeMatch(
        node: RepoFileNode,
        fullQuery: String,
        tokens: List<String>
    ): Int {
        val name = node.nameLower
        val path = node.pathLower

        // All space-separated tokens must match either as a substring in path or fuzzy subsequence in name
        for (token in tokens) {
            if (!path.contains(token) && !isFuzzySubsequence(token, name)) {
                return 0
            }
        }

        var score = 0
        when {
            name == fullQuery -> score += 1000
            name.startsWith(fullQuery) -> score += 750
            name.contains(fullQuery) -> score += 520
            path.contains(fullQuery) -> score += 320
            isFuzzySubsequence(fullQuery, name) -> score += 180
            else -> score += 100
        }

        // Boost markdown and README/LICENSE files slightly in a Markdown reader
        if (node.isPureMarkdown) score += 45
        if (node.isLicenseOrReadme) score += 35
        if (!node.isDirectory) score += 20

        // Prefer shorter filenames / shallower paths on ties
        score -= (node.depth * 4)
        return score.coerceAtLeast(1)
    }

    private fun isFuzzySubsequence(needle: String, haystack: String): Boolean {
        if (needle.length > haystack.length) return false
        var nIdx = 0
        var hIdx = 0
        while (nIdx < needle.length && hIdx < haystack.length) {
            if (needle[nIdx] == haystack[hIdx]) {
                nIdx++
            }
            hIdx++
        }
        return nIdx == needle.length
    }

    private fun buildHierarchicalTree(
        allNodes: List<RepoFileNode>,
        expandedFolders: Set<String>
    ): List<RepoFileNode> {
        val childrenByParent = allNodes.groupBy { it.parentPath }
        val result = ArrayList<RepoFileNode>(allNodes.size)

        fun visitDirectory(parentPath: String) {
            val children = childrenByParent[parentPath].orEmpty()
                .sortedWith(
                    compareByDescending<RepoFileNode> { it.isDirectory }
                        .thenBy { it.nameLower }
                )
            for (child in children) {
                result.add(child)
                if (child.isDirectory && expandedFolders.contains(child.path)) {
                    visitDirectory(child.path)
                }
            }
        }

        visitDirectory("")
        return result
    }
}
