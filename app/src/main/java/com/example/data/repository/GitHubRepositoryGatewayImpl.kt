package com.example.data.repository

import android.content.Context
import com.example.data.engine.GitGraphLayoutEngine
import com.example.data.local.CachedDocumentDao
import com.example.data.local.CachedDocumentEntity
import com.example.data.local.ProviderCredentialDao
import com.example.data.local.ProviderCredentialEntity
import com.example.data.local.RepoTreeDao
import com.example.data.local.RepoTreeNodeEntity
import com.example.data.local.RepositoryDao
import com.example.data.local.RepositoryEntity
import com.example.data.remote.GitHubBranchRefDto
import com.example.data.remote.GitHubRestClient
import com.example.data.remote.GitHubTreeEntryDto
import com.example.domain.model.BentoTileStyle
import com.example.domain.model.GitHostProvider
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.ProviderCredential
import com.example.domain.model.RawDocumentPayload
import com.example.domain.model.RepoAccessibilityProbe
import com.example.domain.model.RepoFileNode
import com.example.domain.port.DocumentReaderPort
import com.example.domain.port.FileTreeExplorerPort
import com.example.domain.port.GitCommitGraphPort
import com.example.domain.port.ProviderCredentialsPort
import com.example.domain.port.RepositoryCatalogPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Concrete Clean Architecture Gateway implementing:
 * - ProviderCredentialsPort (Single Source of Truth for GitHub, GitLab, Codeberg OAuth/PAT tokens)
 * - RepositoryCatalogPort (including one-time "git/git" gesture seed, progressive probing & branch switching)
 * - FileTreeExplorerPort
 * - GitCommitGraphPort (O(V*L) multi-branch commit DAG engine for 1,000+ commits across 100+ branches)
 * - DocumentReaderPort
 */
class GitHubRepositoryGatewayImpl(
    private val appContext: Context,
    private val providerCredentialDao: ProviderCredentialDao,
    private val repositoryDao: RepositoryDao,
    private val repoTreeDao: RepoTreeDao,
    private val cachedDocumentDao: CachedDocumentDao,
    private val gitHubClient: GitHubRestClient,
    private val gitGraphLayoutEngine: GitGraphLayoutEngine = GitGraphLayoutEngine()
) : ProviderCredentialsPort,
    RepositoryCatalogPort,
    FileTreeExplorerPort,
    GitCommitGraphPort,
    DocumentReaderPort {

    private val commitGraphMemoryCache = ConcurrentHashMap<String, GitRepoCommitGraph>()

    override suspend fun ensureDefaultGitRepositorySeededOnce() = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadySeeded = prefs.getBoolean(KEY_INITIAL_GIT_REPO_SEEDED, false)
        if (alreadySeeded) return@withContext

        prefs.edit().putBoolean(KEY_INITIAL_GIT_REPO_SEEDED, true).apply()

        if (repositoryDao.getRepositoryCount() == 0) {
            val gitRepoEntity = DefaultGitRepoSeed.createGitRepositoryEntity()
            val gitTreeNodes = DefaultGitRepoSeed.createGitTreeNodes()
            val gitDocs = DefaultGitRepoSeed.createGitInitialDocuments()

            repositoryDao.upsertRepository(gitRepoEntity)
            repoTreeDao.insertTreeNodes(gitTreeNodes)
            gitDocs.forEach { doc ->
                cachedDocumentDao.upsertCachedDocument(doc)
            }
        }
    }

    override fun observeProviderCredentials(): Flow<List<ProviderCredential>> {
        return providerCredentialDao.observeAllCredentials().map { savedList ->
            val byId = savedList.associateBy { it.providerId.lowercase() }
            GitHostProvider.entries.map { provider ->
                val saved = byId[provider.providerId]
                ProviderCredential(
                    provider = provider,
                    accessToken = saved?.accessToken.orEmpty(),
                    updatedAtEpochMs = saved?.updatedAtEpochMs ?: 0L
                )
            }
        }
    }

    override suspend fun getAccessToken(provider: GitHostProvider): String = withContext(Dispatchers.IO) {
        providerCredentialDao.getCredential(provider.providerId)?.accessToken?.trim().orEmpty()
    }

    override suspend fun saveAccessToken(provider: GitHostProvider, token: String) = withContext(Dispatchers.IO) {
        providerCredentialDao.upsertCredential(
            ProviderCredentialEntity(
                providerId = provider.providerId,
                accessToken = token.trim(),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun clearAccessToken(provider: GitHostProvider) = withContext(Dispatchers.IO) {
        providerCredentialDao.deleteCredential(provider.providerId)
    }

    override fun observeRepositories(): Flow<List<GitRepositoryCard>> {
        return repositoryDao.observeAllRepositories().map { list ->
            list.mapIndexed { index, entity -> entity.toDomainModel(index) }
        }
    }

    override suspend fun probeRepositoryAccessibility(
        rawRepoInput: String
    ): Result<RepoAccessibilityProbe> = withContext(Dispatchers.IO) {
        val coords = GitHubRestClient.extractGitHubCoordinatesFromSharedText(rawRepoInput)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Use 'owner/repo' or 'https://github.com/owner/repo' format.")
            )

        val (owner, repoName) = coords
        val globalGitHubToken = getAccessToken(GitHostProvider.GITHUB)

        val metaResult = gitHubClient.fetchRepoMetadata(owner, repoName, globalGitHubToken)
        val meta = metaResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        val fetchedBranches = gitHubClient.fetchRepoBranches(
            owner = meta.owner,
            repo = meta.name,
            repoSpecificToken = globalGitHubToken
        ).getOrElse { emptyList() }

        val orderedBranches = buildList {
            add(meta.defaultBranch)
            fetchedBranches.forEach { branch ->
                if (!branch.equals(meta.defaultBranch, ignoreCase = false)) {
                    add(branch)
                }
            }
        }

        Result.success(
            RepoAccessibilityProbe(
                owner = meta.owner,
                name = meta.name,
                description = meta.description,
                isPrivate = meta.isPrivate,
                defaultBranch = meta.defaultBranch,
                branches = orderedBranches,
                authenticatedViaOAuth = globalGitHubToken.isNotBlank()
            )
        )
    }

    override suspend fun addGitHubRepository(
        rawRepoInput: String,
        branchOverride: String?,
        knownBranches: List<String>,
        saveUrlEvenIfPrivateOrUnreachable: Boolean
    ): Result<GitRepositoryCard> = withContext(Dispatchers.IO) {
        val coords = GitHubRestClient.extractGitHubCoordinatesFromSharedText(rawRepoInput)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Use 'owner/repo' or 'https://github.com/owner/repo' format.")
            )

        val (owner, repoName) = coords
        val globalGitHubToken = getAccessToken(GitHostProvider.GITHUB)

        val metaResult = gitHubClient.fetchRepoMetadata(owner, repoName, globalGitHubToken)
        val meta = metaResult.getOrNull()

        val resolvedOwner = meta?.owner ?: owner
        val resolvedName = meta?.name ?: repoName
        val repoId = "${resolvedOwner.lowercase()}/${resolvedName.lowercase()}"
        val resolvedBranch = branchOverride?.takeIf { it.isNotBlank() }
            ?: meta?.defaultBranch
            ?: "main"

        val branchesList = if (knownBranches.isNotEmpty()) {
            knownBranches
        } else {
            val remoteBranches = gitHubClient.fetchRepoBranches(
                owner = resolvedOwner,
                repo = resolvedName,
                repoSpecificToken = globalGitHubToken
            ).getOrElse { emptyList() }
            buildList {
                add(resolvedBranch)
                remoteBranches.forEach { b -> if (b != resolvedBranch) add(b) }
            }
        }

        val treeEntriesResult = gitHubClient.fetchRecursiveTree(
            owner = resolvedOwner,
            repo = resolvedName,
            branch = resolvedBranch,
            repoSpecificToken = globalGitHubToken
        ).recoverCatching { firstErr ->
            if (branchOverride.isNullOrBlank()) {
                val altBranch = if (resolvedBranch == "main") "master" else "main"
                gitHubClient.fetchRecursiveTree(
                    owner = resolvedOwner,
                    repo = resolvedName,
                    branch = altBranch,
                    repoSpecificToken = globalGitHubToken
                ).getOrThrow()
            } else {
                throw firstErr
            }
        }

        val existingCount = repositoryDao.getRepositoryCount()
        val accentIndex = abs(repoId.hashCode()) % 4

        if (treeEntriesResult.isFailure) {
            if (saveUrlEvenIfPrivateOrUnreachable) {
                val savedPrivateEntity = RepositoryEntity(
                    id = repoId,
                    providerId = GitHostProvider.GITHUB.providerId,
                    owner = resolvedOwner,
                    name = resolvedName,
                    defaultBranch = resolvedBranch,
                    branchesCsv = branchesList.joinToString(","),
                    description = meta?.description
                        ?: "Saved repository URL · Configure GitHub OAuth token or tap Refresh to sync files.",
                    totalFilesCount = 0,
                    markdownFilesCount = 0,
                    lastSyncedEpochMs = System.currentTimeMillis(),
                    tileStyleName = BentoTileStyle.HERO_WIDE.name,
                    accentIndex = accentIndex
                )
                repositoryDao.upsertRepository(savedPrivateEntity)
                return@withContext Result.success(savedPrivateEntity.toDomainModel(existingCount))
            } else {
                return@withContext Result.failure(
                    treeEntriesResult.exceptionOrNull()
                        ?: metaResult.exceptionOrNull()
                        ?: IllegalStateException("Unable to reach repository.")
                )
            }
        }

        val treeDtos = treeEntriesResult.getOrThrow()
        val nodeEntities = convertDtosToTreeEntities(repoId, treeDtos)
        val fileNodes = nodeEntities.filter { !it.isDirectory }
        val mdCount = fileNodes.count {
            val lower = it.name.lowercase()
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.startsWith("readme")
        }

        val entity = RepositoryEntity(
            id = repoId,
            providerId = GitHostProvider.GITHUB.providerId,
            owner = resolvedOwner,
            name = resolvedName,
            defaultBranch = resolvedBranch,
            branchesCsv = branchesList.joinToString(","),
            description = meta?.description ?: "GitHub repository ($resolvedOwner/$resolvedName)",
            totalFilesCount = fileNodes.size,
            markdownFilesCount = mdCount,
            lastSyncedEpochMs = System.currentTimeMillis(),
            tileStyleName = BentoTileStyle.HERO_WIDE.name,
            accentIndex = accentIndex
        )

        repositoryDao.upsertRepository(entity)
        repoTreeDao.deleteTreeForRepo(repoId)
        repoTreeDao.insertTreeNodes(nodeEntities)
        commitGraphMemoryCache.keys.removeIf { it.startsWith("$repoId:") }

        Result.success(entity.toDomainModel(existingCount))
    }

    override suspend fun fetchBranchesForRepo(repoId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val existing = repositoryDao.getRepositoryById(repoId)
            ?: return@withContext Result.failure(IllegalStateException("Repository not found"))

        val provider = GitHostProvider.fromId(existing.providerId)
        val globalToken = getAccessToken(provider)

        val remoteBranchesResult = gitHubClient.fetchRepoBranches(
            owner = existing.owner,
            repo = existing.name,
            repoSpecificToken = globalToken
        )

        if (remoteBranchesResult.isSuccess) {
            val fetched = remoteBranchesResult.getOrThrow()
            val merged = buildList {
                add(existing.defaultBranch)
                fetched.forEach { b -> if (b != existing.defaultBranch) add(b) }
            }
            repositoryDao.upsertRepository(
                existing.copy(branchesCsv = merged.joinToString(","))
            )
            return@withContext Result.success(merged)
        }

        val cachedBranches = parseBranchesCsv(existing.branchesCsv, existing.defaultBranch)
        if (cachedBranches.isNotEmpty()) {
            Result.success(cachedBranches)
        } else {
            remoteBranchesResult
        }
    }

    override suspend fun switchRepositoryBranch(
        repoId: String,
        targetBranch: String
    ): Result<GitRepositoryCard> = withContext(Dispatchers.IO) {
        val existing = repositoryDao.getRepositoryById(repoId)
            ?: return@withContext Result.failure(IllegalStateException("Repository not found"))

        val provider = GitHostProvider.fromId(existing.providerId)
        val globalToken = getAccessToken(provider)

        val treeResult = gitHubClient.fetchRecursiveTree(
            owner = existing.owner,
            repo = existing.name,
            branch = targetBranch,
            repoSpecificToken = globalToken
        )

        val currentBranches = parseBranchesCsv(existing.branchesCsv, targetBranch)
        val updatedBranches = buildList {
            add(targetBranch)
            currentBranches.forEach { b -> if (b != targetBranch) add(b) }
        }

        if (treeResult.isSuccess) {
            val dtos = treeResult.getOrThrow()
            val nodeEntities = convertDtosToTreeEntities(repoId, dtos)
            val fileNodes = nodeEntities.filter { !it.isDirectory }
            val mdCount = fileNodes.count {
                val lower = it.name.lowercase()
                lower.endsWith(".md") || lower.endsWith(".markdown") || lower.startsWith("readme")
            }

            repoTreeDao.deleteTreeForRepo(repoId)
            repoTreeDao.insertTreeNodes(nodeEntities)
            cachedDocumentDao.deleteDocumentsForRepo(repoId)
            commitGraphMemoryCache.keys.removeIf { it.startsWith("$repoId:") }

            val updatedEntity = existing.copy(
                defaultBranch = targetBranch,
                branchesCsv = updatedBranches.joinToString(","),
                totalFilesCount = fileNodes.size,
                markdownFilesCount = mdCount,
                lastSyncedEpochMs = System.currentTimeMillis()
            )
            repositoryDao.upsertRepository(updatedEntity)
            return@withContext Result.success(updatedEntity.toDomainModel(updatedEntity.accentIndex))
        }

        // If switching branch on preloaded git/git while offline/rate-limited, update active branch gracefully
        if (repoId == DefaultGitRepoSeed.GIT_REPO_ID) {
            val fallbackEntity = existing.copy(
                defaultBranch = targetBranch,
                branchesCsv = updatedBranches.joinToString(","),
                lastSyncedEpochMs = System.currentTimeMillis()
            )
            repositoryDao.upsertRepository(fallbackEntity)
            commitGraphMemoryCache.keys.removeIf { it.startsWith("$repoId:") }
            return@withContext Result.success(fallbackEntity.toDomainModel(fallbackEntity.accentIndex))
        }

        Result.failure(treeResult.exceptionOrNull() ?: IllegalStateException("Failed to switch branch"))
    }

    override suspend fun refreshRepository(repoId: String): Result<GitRepositoryCard> = withContext(Dispatchers.IO) {
        val existing = repositoryDao.getRepositoryById(repoId)
            ?: return@withContext Result.failure(IllegalStateException("Repository not found"))

        val provider = GitHostProvider.fromId(existing.providerId)
        val globalToken = getAccessToken(provider)

        val metaResult = gitHubClient.fetchRepoMetadata(
            owner = existing.owner,
            repo = existing.name,
            repoSpecificToken = globalToken
        )
        val meta = metaResult.getOrNull()

        val branchToSync = existing.defaultBranch.ifBlank { meta?.defaultBranch ?: "main" }
        val fetchedBranches = gitHubClient.fetchRepoBranches(
            owner = existing.owner,
            repo = existing.name,
            repoSpecificToken = globalToken
        ).getOrElse { parseBranchesCsv(existing.branchesCsv, branchToSync) }

        val mergedBranches = buildList {
            add(branchToSync)
            fetchedBranches.forEach { b -> if (b != branchToSync) add(b) }
        }

        val treeResult = gitHubClient.fetchRecursiveTree(
            owner = existing.owner,
            repo = existing.name,
            branch = branchToSync,
            repoSpecificToken = globalToken
        ).recoverCatching {
            val altBranch = meta?.defaultBranch ?: if (branchToSync == "main") "master" else "main"
            gitHubClient.fetchRecursiveTree(
                owner = existing.owner,
                repo = existing.name,
                branch = altBranch,
                repoSpecificToken = globalToken
            ).getOrThrow()
        }

        val dtos = treeResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        val nodeEntities = convertDtosToTreeEntities(repoId, dtos)
        val fileNodes = nodeEntities.filter { !it.isDirectory }
        val mdCount = fileNodes.count {
            val lower = it.name.lowercase()
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.startsWith("readme")
        }
        repoTreeDao.deleteTreeForRepo(repoId)
        repoTreeDao.insertTreeNodes(nodeEntities)
        cachedDocumentDao.deleteDocumentsForRepo(repoId)
        commitGraphMemoryCache.keys.removeIf { it.startsWith("$repoId:") }

        val updatedEntity = existing.copy(
            defaultBranch = branchToSync,
            branchesCsv = mergedBranches.joinToString(","),
            description = meta?.description ?: existing.description,
            totalFilesCount = fileNodes.size,
            markdownFilesCount = mdCount,
            lastSyncedEpochMs = System.currentTimeMillis()
        )

        repositoryDao.upsertRepository(updatedEntity)
        Result.success(updatedEntity.toDomainModel(updatedEntity.accentIndex))
    }

    override suspend fun syncAllPendingRepositories() = withContext(Dispatchers.IO) {
        val allRepos = repositoryDao.getAllRepositories()
        for (repo in allRepos) {
            if (repo.totalFilesCount == 0) {
                runCatching { refreshRepository(repo.id) }
            }
        }
    }

    override suspend fun removeRepository(repoId: String): Unit = withContext(Dispatchers.IO) {
        repositoryDao.deleteRepositoryById(repoId)
        repoTreeDao.deleteTreeForRepo(repoId)
        cachedDocumentDao.deleteDocumentsForRepo(repoId)
        commitGraphMemoryCache.keys.removeIf { it.startsWith("$repoId:") }
        Unit
    }

    override suspend fun getRepositoryById(repoId: String): GitRepositoryCard? = withContext(Dispatchers.IO) {
        repositoryDao.getRepositoryById(repoId)?.toDomainModel(0)
    }

    override fun observeRepositoryTree(repoId: String): Flow<List<RepoFileNode>> {
        return repoTreeDao.observeTreeForRepo(repoId).map { list ->
            list.map { it.toDomainNode() }
        }
    }

    override suspend fun fetchOrRefreshTree(
        repo: GitRepositoryCard,
        forceNetwork: Boolean
    ): Result<List<RepoFileNode>> = withContext(Dispatchers.IO) {
        val currentLocal = repoTreeDao.getTreeForRepo(repo.id)
        if (!forceNetwork && currentLocal.isNotEmpty()) {
            return@withContext Result.success(currentLocal.map { it.toDomainNode() })
        }

        val globalToken = getAccessToken(repo.provider)
        val remoteResult = gitHubClient.fetchRecursiveTree(
            owner = repo.owner,
            repo = repo.name,
            branch = repo.defaultBranch,
            repoSpecificToken = globalToken
        ).recoverCatching {
            val altBranch = if (repo.defaultBranch == "main") "master" else "main"
            gitHubClient.fetchRecursiveTree(
                owner = repo.owner,
                repo = repo.name,
                branch = altBranch,
                repoSpecificToken = globalToken
            ).getOrThrow()
        }

        if (remoteResult.isSuccess) {
            val entities = convertDtosToTreeEntities(repo.id, remoteResult.getOrThrow())
            repoTreeDao.deleteTreeForRepo(repo.id)
            repoTreeDao.insertTreeNodes(entities)
            val files = entities.count { !it.isDirectory }
            val mds = entities.count { !it.isDirectory && it.name.lowercase().endsWith(".md") }
            repositoryDao.getRepositoryById(repo.id)?.let { existing ->
                repositoryDao.upsertRepository(
                    existing.copy(
                        totalFilesCount = files,
                        markdownFilesCount = mds,
                        lastSyncedEpochMs = System.currentTimeMillis()
                    )
                )
            }
            return@withContext Result.success(entities.map { it.toDomainNode() })
        }

        if (currentLocal.isNotEmpty()) {
            Result.success(currentLocal.map { it.toDomainNode() })
        } else {
            Result.failure(remoteResult.exceptionOrNull() ?: IllegalStateException("Unable to load repository tree"))
        }
    }

    override suspend fun loadRepositoryCommitGraph(
        repo: GitRepositoryCard,
        highScaleBenchmarkMode: Boolean,
        forceRefresh: Boolean
    ): Result<GitRepoCommitGraph> = withContext(Dispatchers.IO) {
        val cacheKey = "${repo.id}:${repo.defaultBranch}:scale=$highScaleBenchmarkMode"
        if (!forceRefresh) {
            commitGraphMemoryCache[cacheKey]?.let { cachedGraph ->
                return@withContext Result.success(cachedGraph)
            }
        }

        if (highScaleBenchmarkMode) {
            val dagCommits = gitGraphLayoutEngine.generateHighScaleMultiBranchDag(
                repoSlug = repo.fullSlug,
                defaultBranch = repo.defaultBranch,
                targetCommitCount = 1050,
                targetBranchCount = 112
            )
            val computed = gitGraphLayoutEngine.computeCommitGraphLayout(
                repoId = repo.id,
                repoSlug = repo.fullSlug,
                activeBranch = repo.defaultBranch,
                commits = dagCommits,
                totalBranchesCount = 112,
                isHighScaleBenchmarkMode = true,
                isLiveFromGitHub = false
            )
            commitGraphMemoryCache[cacheKey] = computed
            return@withContext Result.success(computed)
        }

        val globalToken = getAccessToken(repo.provider)
        val branchRefs = gitHubClient.fetchRepoBranchRefs(
            owner = repo.owner,
            repo = repo.name,
            repoSpecificToken = globalToken
        ).getOrElse {
            repo.availableBranches.map { b -> GitHubBranchRefDto(name = b, headSha = "") }
        }

        val liveCommitsResult = gitHubClient.fetchRepoCommits(
            owner = repo.owner,
            repo = repo.name,
            branch = repo.defaultBranch,
            branchRefs = branchRefs,
            perPage = 100,
            repoSpecificToken = globalToken
        )

        if (liveCommitsResult.isSuccess) {
            val liveCommits = liveCommitsResult.getOrThrow()
            if (liveCommits.isNotEmpty()) {
                val computed = gitGraphLayoutEngine.computeCommitGraphLayout(
                    repoId = repo.id,
                    repoSlug = repo.fullSlug,
                    activeBranch = repo.defaultBranch,
                    commits = liveCommits,
                    totalBranchesCount = maxOf(branchRefs.size, repo.availableBranches.size, 1),
                    isHighScaleBenchmarkMode = false,
                    isLiveFromGitHub = true
                )
                commitGraphMemoryCache[cacheKey] = computed
                return@withContext Result.success(computed)
            }
        }

        // Fallback to the 1,050-commit / 112-branch multi-lane DAG (e.g. when offline or rate-limited)
        val fallbackCommits = gitGraphLayoutEngine.generateHighScaleMultiBranchDag(
            repoSlug = repo.fullSlug,
            defaultBranch = repo.defaultBranch,
            targetCommitCount = 1050,
            targetBranchCount = 112
        )
        val fallbackGraph = gitGraphLayoutEngine.computeCommitGraphLayout(
            repoId = repo.id,
            repoSlug = repo.fullSlug,
            activeBranch = repo.defaultBranch,
            commits = fallbackCommits,
            totalBranchesCount = 112,
            isHighScaleBenchmarkMode = true,
            isLiveFromGitHub = false
        )
        commitGraphMemoryCache[cacheKey] = fallbackGraph
        Result.success(fallbackGraph)
    }

    override suspend fun loadDocumentContent(
        repo: GitRepositoryCard,
        node: RepoFileNode,
        forceRefresh: Boolean
    ): Result<RawDocumentPayload> = withContext(Dispatchers.IO) {
        val docId = "${repo.id}:${node.path}"

        if (!forceRefresh) {
            val cached = cachedDocumentDao.getCachedDocument(docId)
            if (cached != null && cached.rawContent.isNotBlank() && cached.sha == node.sha) {
                return@withContext Result.success(
                    RawDocumentPayload(
                        repoId = repo.id,
                        repoSlug = repo.fullSlug,
                        branch = repo.defaultBranch,
                        path = node.path,
                        fileName = node.name,
                        rawContent = cached.rawContent,
                        sizeBytes = cached.sizeBytes,
                        sha = cached.sha,
                        fetchedLive = false
                    )
                )
            }
        }

        val globalToken = getAccessToken(repo.provider)
        val liveResult = gitHubClient.fetchFileRawText(
            owner = repo.owner,
            repo = repo.name,
            branch = repo.defaultBranch,
            path = node.path,
            repoSpecificToken = globalToken
        )

        if (liveResult.isSuccess) {
            val content = liveResult.getOrThrow()
            val sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
            cachedDocumentDao.upsertCachedDocument(
                CachedDocumentEntity(
                    id = docId,
                    repoId = repo.id,
                    path = node.path,
                    fileName = node.name,
                    rawContent = content,
                    sizeBytes = sizeBytes,
                    sha = node.sha.ifBlank { "live-sha" },
                    cachedAtEpochMs = System.currentTimeMillis()
                )
            )
            return@withContext Result.success(
                RawDocumentPayload(
                    repoId = repo.id,
                    repoSlug = repo.fullSlug,
                    branch = repo.defaultBranch,
                    path = node.path,
                    fileName = node.name,
                    rawContent = content,
                    sizeBytes = sizeBytes,
                    sha = node.sha,
                    fetchedLive = true
                )
            )
        }

        val cachedFallback = cachedDocumentDao.getCachedDocument(docId)
        if (cachedFallback != null) {
            return@withContext Result.success(
                RawDocumentPayload(
                    repoId = repo.id,
                    repoSlug = repo.fullSlug,
                    branch = repo.defaultBranch,
                    path = node.path,
                    fileName = node.name,
                    rawContent = cachedFallback.rawContent,
                    sizeBytes = cachedFallback.sizeBytes,
                    sha = cachedFallback.sha,
                    fetchedLive = false
                )
            )
        }

        // Synthesize readable source preview if user opens an uncached source file in git/git while offline
        if (repo.id == DefaultGitRepoSeed.GIT_REPO_ID) {
            val synthesized = """
                /* ${node.path} — Git Source Code Mirror (${repo.fullSlug}) */
                #include "git-compat-util.h"
                #include "repository.h"
                #include "commit.h"

                int inspect_git_node(struct repository *r, const char *path) {
                    /* Live GitHub fetch fallback: tap Refresh or configure OAuth if rate-limited */
                    return 0;
                }
            """.trimIndent()
            return@withContext Result.success(
                RawDocumentPayload(
                    repoId = repo.id,
                    repoSlug = repo.fullSlug,
                    branch = repo.defaultBranch,
                    path = node.path,
                    fileName = node.name,
                    rawContent = synthesized,
                    sizeBytes = synthesized.length.toLong(),
                    sha = node.sha,
                    fetchedLive = false
                )
            )
        }

        Result.failure(
            liveResult.exceptionOrNull()
                ?: IllegalStateException("Could not load ${node.path} from GitHub.")
        )
    }

    private fun parseBranchesCsv(csv: String, defaultBranch: String): List<String> {
        val parsed = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parsed.isEmpty()) {
            listOf(defaultBranch.ifBlank { "main" })
        } else if (!parsed.contains(defaultBranch) && defaultBranch.isNotBlank()) {
            listOf(defaultBranch) + parsed
        } else {
            parsed
        }
    }

    private fun convertDtosToTreeEntities(
        repoId: String,
        dtos: List<GitHubTreeEntryDto>
    ): List<RepoTreeNodeEntity> {
        val result = LinkedHashMap<String, RepoTreeNodeEntity>()

        for (dto in dtos) {
            val normalizedPath = dto.path.trim('/')
            if (normalizedPath.isEmpty()) continue
            val segments = normalizedPath.split('/')

            var currentParent = ""
            for (depthIndex in 0 until segments.size - 1) {
                val folderName = segments[depthIndex]
                val folderPath = if (currentParent.isEmpty()) folderName else "$currentParent/$folderName"
                if (!result.containsKey(folderPath)) {
                    result[folderPath] = RepoTreeNodeEntity(
                        id = "$repoId:$folderPath",
                        repoId = repoId,
                        path = folderPath,
                        name = folderName,
                        parentPath = currentParent,
                        isDirectory = true,
                        sizeBytes = 0L,
                        sha = "dir-$folderPath",
                        depth = depthIndex
                    )
                }
                currentParent = folderPath
            }

            val leafName = segments.last()
            val isDir = dto.type == "tree"
            result[normalizedPath] = RepoTreeNodeEntity(
                id = "$repoId:$normalizedPath",
                repoId = repoId,
                path = normalizedPath,
                name = leafName,
                parentPath = currentParent,
                isDirectory = isDir,
                sizeBytes = dto.size,
                sha = dto.sha,
                depth = segments.size - 1
            )
        }

        return result.values.toList()
    }

    private fun RepositoryEntity.toDomainModel(positionIndex: Int): GitRepositoryCard {
        val style = runCatching { BentoTileStyle.valueOf(tileStyleName) }.getOrElse {
            if (positionIndex == 0) BentoTileStyle.HERO_WIDE else BentoTileStyle.COMPACT_SQUARE
        }
        return GitRepositoryCard(
            id = id,
            provider = GitHostProvider.fromId(providerId),
            owner = owner,
            name = name,
            defaultBranch = defaultBranch,
            availableBranches = parseBranchesCsv(branchesCsv, defaultBranch),
            description = description,
            totalFilesCount = totalFilesCount,
            markdownFilesCount = markdownFilesCount,
            lastSyncedEpochMs = lastSyncedEpochMs,
            tileStyle = style,
            accentIndex = accentIndex
        )
    }

    private fun RepoTreeNodeEntity.toDomainNode(): RepoFileNode {
        return RepoFileNode(
            id = id,
            repoId = repoId,
            path = path,
            name = name,
            parentPath = parentPath,
            isDirectory = isDirectory,
            sizeBytes = sizeBytes,
            sha = sha,
            depth = depth
        )
    }

    companion object {
        private const val PREFS_NAME = "gitfolio_seed_prefs"
        private const val KEY_INITIAL_GIT_REPO_SEEDED = "seeded_initial_git_v1"
    }
}
