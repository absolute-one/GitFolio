package com.example.domain.usecase

import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitHostProvider
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.ProviderCredential
import com.example.domain.model.RepoAccessibilityProbe
import com.example.domain.model.RepoFileNode
import com.example.domain.port.DocumentReaderPort
import com.example.domain.port.FileTreeExplorerPort
import com.example.domain.port.GitCommitGraphPort
import com.example.domain.port.MarkdownEnginePort
import com.example.domain.port.ProviderCredentialsPort
import com.example.domain.port.RepositoryCatalogPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Clean Architecture Use Cases orchestrating real GitHub repository operations,
 * one-time "git/git" gesture seeding, progressive repo verification, branch switching,
 * O(V + E) multi-branch commit graph loading, and optimized in-repo search.
 */
class ObserveRepositoriesUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    operator fun invoke(): Flow<List<GitRepositoryCard>> = catalogPort.observeRepositories()

    suspend fun ensureDefaultGitSeededOnce() {
        catalogPort.ensureDefaultGitRepositorySeededOnce()
    }
}

class ManageProviderCredentialsUseCase(
    private val credentialsPort: ProviderCredentialsPort,
    private val catalogPort: RepositoryCatalogPort
) {
    fun observeCredentials(): Flow<List<ProviderCredential>> =
        credentialsPort.observeProviderCredentials()

    suspend fun saveToken(provider: GitHostProvider, rawToken: String) {
        val cleaned = rawToken.trim()
        if (cleaned.isEmpty()) {
            credentialsPort.clearAccessToken(provider)
        } else {
            credentialsPort.saveAccessToken(provider, cleaned)
            catalogPort.syncAllPendingRepositories()
        }
    }

    suspend fun clearToken(provider: GitHostProvider) {
        credentialsPort.clearAccessToken(provider)
    }
}

class ProbeRepositoryAccessibilityUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    suspend operator fun invoke(rawRepoInput: String): Result<RepoAccessibilityProbe> {
        val trimmed = rawRepoInput.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a GitHub repository link or owner/repo."))
        }
        return catalogPort.probeRepositoryAccessibility(trimmed)
    }
}

class AddGitHubRepositoryUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    suspend operator fun invoke(
        rawRepoInput: String,
        branchOverride: String?,
        knownBranches: List<String> = emptyList(),
        saveUrlEvenIfPrivateOrUnreachable: Boolean = false
    ): Result<GitRepositoryCard> {
        val trimmed = rawRepoInput.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a GitHub repository (e.g. owner/repo or URL)."))
        }
        return catalogPort.addGitHubRepository(
            rawRepoInput = trimmed,
            branchOverride = branchOverride?.trim()?.takeIf { it.isNotEmpty() },
            knownBranches = knownBranches,
            saveUrlEvenIfPrivateOrUnreachable = saveUrlEvenIfPrivateOrUnreachable
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

class SwitchRepositoryBranchUseCase(
    private val catalogPort: RepositoryCatalogPort
) {
    suspend fun fetchBranches(repoId: String): Result<List<String>> {
        return catalogPort.fetchBranchesForRepo(repoId)
    }

    suspend fun switchBranch(repoId: String, targetBranch: String): Result<GitRepositoryCard> {
        return catalogPort.switchRepositoryBranch(repoId, targetBranch.trim())
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

class LoadRepositoryCommitGraphUseCase(
    private val catalogPort: RepositoryCatalogPort,
    private val commitGraphPort: GitCommitGraphPort
) {
    suspend operator fun invoke(
        repoId: String,
        highScaleBenchmarkMode: Boolean = false,
        forceRefresh: Boolean = false
    ): Result<GitRepoCommitGraph> {
        val repo = catalogPort.getRepositoryById(repoId)
            ?: return Result.failure(IllegalStateException("Repository not found"))
        return commitGraphPort.loadRepositoryCommitGraph(
            repo = repo,
            highScaleBenchmarkMode = highScaleBenchmarkMode,
            forceRefresh = forceRefresh
        )
    }

    suspend fun filterCommitRows(
        allRows: List<GitGraphCommitRow>,
        rawQuery: String,
        branchFilter: String?
    ): List<GitGraphCommitRow> = withContext(Dispatchers.Default) {
        val q = rawQuery.trim().lowercase()
        val branch = branchFilter?.trim()?.takeIf { it.isNotEmpty() }

        if (q.isEmpty() && branch == null) return@withContext allRows

        allRows.filter { row ->
            val matchesBranch = branch == null ||
                row.commit.primaryBranch.equals(branch, ignoreCase = true) ||
                row.commit.branchNames.any { it.equals(branch, ignoreCase = true) } ||
                row.badgeLabel.equals(branch, ignoreCase = true)

            if (!matchesBranch) return@filter false
            if (q.isEmpty()) return@filter true

            row.commit.subject.lowercase().contains(q) ||
                row.commit.shortSha.lowercase().contains(q) ||
                row.commit.sha.lowercase().startsWith(q) ||
                row.commit.authorName.lowercase().contains(q) ||
                row.badgeLabel.lowercase().contains(q) ||
                row.commit.branchNames.any { it.lowercase().contains(q) }
        }
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

        if (node.isPureMarkdown) score += 45
        if (node.isLicenseOrReadme) score += 35
        if (!node.isDirectory) score += 20

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
