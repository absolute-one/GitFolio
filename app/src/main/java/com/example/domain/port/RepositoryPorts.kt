package com.example.domain.port

import com.example.domain.model.GitHostProvider
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.ProviderCredential
import com.example.domain.model.RawDocumentPayload
import com.example.domain.model.RepoAccessibilityProbe
import com.example.domain.model.RepoFileNode
import kotlinx.coroutines.flow.Flow

/**
 * Segregated Domain Ports (Interfaces) adhering to SOLID principles.
 */

interface ProviderCredentialsPort {
    fun observeProviderCredentials(): Flow<List<ProviderCredential>>
    suspend fun getAccessToken(provider: GitHostProvider): String
    suspend fun saveAccessToken(provider: GitHostProvider, token: String)
    suspend fun clearAccessToken(provider: GitHostProvider)
}

interface RepositoryCatalogPort {
    fun observeRepositories(): Flow<List<GitRepositoryCard>>

    /**
     * Seeds the official "git/git" repository once on first app launch as a welcoming gesture.
     * If the user later deletes it and empties their list, it is never re-seeded automatically.
     */
    suspend fun ensureDefaultGitRepositorySeededOnce()

    /**
     * Step 1 of Progressive Repo Addition:
     * Checks if the repo link/slug is accessible (publicly or via OAuth) and fetches its branches
     * + default branch.
     */
    suspend fun probeRepositoryAccessibility(rawRepoInput: String): Result<RepoAccessibilityProbe>

    /**
     * Step 2 of Progressive Repo Addition (or direct Share mounting):
     * Adds the repository with the selected branch.
     */
    suspend fun addGitHubRepository(
        rawRepoInput: String,
        branchOverride: String?,
        knownBranches: List<String> = emptyList(),
        saveUrlEvenIfPrivateOrUnreachable: Boolean = false
    ): Result<GitRepositoryCard>

    suspend fun refreshRepository(repoId: String): Result<GitRepositoryCard>

    /**
     * Fetches the latest branch list for an existing repository and switches its active branch,
     * re-indexing the Git file tree for the new branch.
     */
    suspend fun fetchBranchesForRepo(repoId: String): Result<List<String>>
    suspend fun switchRepositoryBranch(repoId: String, targetBranch: String): Result<GitRepositoryCard>

    suspend fun syncAllPendingRepositories()
    suspend fun removeRepository(repoId: String)
    suspend fun getRepositoryById(repoId: String): GitRepositoryCard?
}

interface FileTreeExplorerPort {
    fun observeRepositoryTree(repoId: String): Flow<List<RepoFileNode>>
    suspend fun fetchOrRefreshTree(repo: GitRepositoryCard, forceNetwork: Boolean): Result<List<RepoFileNode>>
}

interface GitCommitGraphPort {
    suspend fun loadRepositoryCommitGraph(
        repo: GitRepositoryCard,
        highScaleBenchmarkMode: Boolean = false,
        forceRefresh: Boolean = false
    ): Result<GitRepoCommitGraph>
}

interface DocumentReaderPort {
    suspend fun loadDocumentContent(
        repo: GitRepositoryCard,
        node: RepoFileNode,
        forceRefresh: Boolean = false
    ): Result<RawDocumentPayload>
}

interface MarkdownEnginePort {
    suspend fun parseToVirtualizedAst(
        payload: RawDocumentPayload,
        node: RepoFileNode,
        forceRawMarkdownTreatment: Boolean = false
    ): ParsedMarkdownDocument
}

/**
 * Future-ready Extensibility Ports (Open/Closed Principle) for future commit history & file editing.
 */
data class FutureCommitRecord(
    val sha: String,
    val authorName: String,
    val message: String,
    val committedAtIso: String
)

data class FutureFileEditDraft(
    val repoId: String,
    val path: String,
    val baseSha: String,
    val updatedMarkdownContent: String,
    val commitMessage: String
)

interface FutureGitHistoryPort {
    suspend fun fetchFileCommitHistory(repoId: String, filePath: String): Result<List<FutureCommitRecord>>
}

interface FutureDocumentMutationPort {
    val isEditingEnabledInCurrentRelease: Boolean get() = false
    suspend fun stageAndCommitFileEdit(draft: FutureFileEditDraft): Result<String>
}
