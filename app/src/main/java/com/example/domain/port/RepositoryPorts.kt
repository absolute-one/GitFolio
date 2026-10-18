package com.example.domain.port

import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ParsedMarkdownDocument
import com.example.domain.model.RawDocumentPayload
import com.example.domain.model.RepoFileNode
import kotlinx.coroutines.flow.Flow

/**
 * Segregated Domain Ports (Interfaces) adhering to SOLID:
 * - Single Responsibility Principle (SRP)
 * - Open/Closed Principle (OCP)
 * - Interface Segregation Principle (ISP)
 * - Dependency Inversion Principle (DIP)
 */

interface RepositoryCatalogPort {
    fun observeRepositories(): Flow<List<GitRepositoryCard>>
    suspend fun addGitHubRepository(
        rawRepoInput: String,
        branchOverride: String?,
        repoSpecificToken: String
    ): Result<GitRepositoryCard>
    suspend fun refreshRepository(repoId: String): Result<GitRepositoryCard>
    suspend fun removeRepository(repoId: String)
    suspend fun getRepositoryById(repoId: String): GitRepositoryCard?
}

interface FileTreeExplorerPort {
    fun observeRepositoryTree(repoId: String): Flow<List<RepoFileNode>>
    suspend fun fetchOrRefreshTree(repo: GitRepositoryCard, forceNetwork: Boolean): Result<List<RepoFileNode>>
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
