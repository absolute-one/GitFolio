package com.example.data.repository

import com.example.data.local.CachedDocumentDao
import com.example.data.local.CachedDocumentEntity
import com.example.data.local.RepoTreeDao
import com.example.data.local.RepoTreeNodeEntity
import com.example.data.local.RepositoryDao
import com.example.data.local.RepositoryEntity
import com.example.data.remote.GitHubRestClient
import com.example.data.remote.GitHubTreeEntryDto
import com.example.domain.model.BentoTileStyle
import com.example.domain.model.GitRepositoryCard
import com.example.domain.model.RawDocumentPayload
import com.example.domain.model.RepoFileNode
import com.example.domain.port.DocumentReaderPort
import com.example.domain.port.FileTreeExplorerPort
import com.example.domain.port.RepositoryCatalogPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Concrete Clean Architecture Gateway implementing RepositoryCatalogPort,
 * FileTreeExplorerPort, and DocumentReaderPort.
 * Strictly fetches live repository metadata, Git tree indexes, and on-demand file contents
 * from GitHub without any dummy/synthetic data.
 */
class GitHubRepositoryGatewayImpl(
    private val repositoryDao: RepositoryDao,
    private val repoTreeDao: RepoTreeDao,
    private val cachedDocumentDao: CachedDocumentDao,
    private val gitHubClient: GitHubRestClient
) : RepositoryCatalogPort, FileTreeExplorerPort, DocumentReaderPort {

    override fun observeRepositories(): Flow<List<GitRepositoryCard>> {
        return repositoryDao.observeAllRepositories().map { list ->
            list.mapIndexed { index, entity -> entity.toDomainModel(index) }
        }
    }

    override suspend fun addGitHubRepository(
        rawRepoInput: String,
        branchOverride: String?,
        repoSpecificToken: String
    ): Result<GitRepositoryCard> = withContext(Dispatchers.IO) {
        val coords = GitHubRestClient.extractGitHubCoordinatesFromSharedText(rawRepoInput)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Use 'owner/repo' or 'https://github.com/owner/repo' format.")
            )

        val (owner, repoName) = coords

        // 1. Fetch live metadata from GitHub using the repo-specific token (or public if blank)
        val metaResult = gitHubClient.fetchRepoMetadata(owner, repoName, repoSpecificToken)
        val meta = metaResult.getOrElse { err ->
            if (branchOverride.isNullOrBlank()) {
                return@withContext Result.failure(err)
            }
            null
        }

        val resolvedOwner = meta?.owner ?: owner
        val resolvedName = meta?.name ?: repoName
        val repoId = "${resolvedOwner.lowercase()}/${resolvedName.lowercase()}"
        val resolvedBranch = branchOverride?.takeIf { it.isNotBlank() }
            ?: meta?.defaultBranch
            ?: "main"
        val resolvedDescription = meta?.description
            ?: "GitHub repository ($resolvedOwner/$resolvedName)"

        // 2. Fetch live recursive Git tree from GitHub (index of file paths only; files are streamed live on open)
        val treeEntriesResult = gitHubClient.fetchRecursiveTree(
            owner = resolvedOwner,
            repo = resolvedName,
            branch = resolvedBranch,
            repoSpecificToken = repoSpecificToken
        ).recoverCatching { firstErr ->
            if (branchOverride.isNullOrBlank()) {
                val altBranch = if (resolvedBranch == "main") "master" else "main"
                gitHubClient.fetchRecursiveTree(
                    owner = resolvedOwner,
                    repo = resolvedName,
                    branch = altBranch,
                    repoSpecificToken = repoSpecificToken
                ).getOrThrow()
            } else {
                throw firstErr
            }
        }

        val treeDtos = treeEntriesResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        val nodeEntities = convertDtosToTreeEntities(repoId, treeDtos)
        val fileNodes = nodeEntities.filter { !it.isDirectory }
        val mdCount = fileNodes.count {
            val lower = it.name.lowercase()
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.startsWith("readme")
        }

        val existingCount = repositoryDao.getRepositoryCount()
        val chosenStyle = when {
            existingCount == 0 -> BentoTileStyle.HERO_WIDE
            existingCount % 3 == 0 -> BentoTileStyle.HERO_WIDE
            existingCount % 2 == 1 -> BentoTileStyle.TALL_ACCENT
            else -> BentoTileStyle.COMPACT_SQUARE
        }
        val accentIndex = abs(repoId.hashCode()) % 4

        val entity = RepositoryEntity(
            id = repoId,
            owner = resolvedOwner,
            name = resolvedName,
            defaultBranch = resolvedBranch,
            description = resolvedDescription,
            repoSpecificToken = repoSpecificToken,
            totalFilesCount = fileNodes.size,
            markdownFilesCount = mdCount,
            lastSyncedEpochMs = System.currentTimeMillis(),
            tileStyleName = chosenStyle.name,
            accentIndex = accentIndex
        )

        repositoryDao.upsertRepository(entity)
        repoTreeDao.deleteTreeForRepo(repoId)
        repoTreeDao.insertTreeNodes(nodeEntities)

        Result.success(entity.toDomainModel(existingCount))
    }

    override suspend fun refreshRepository(repoId: String): Result<GitRepositoryCard> = withContext(Dispatchers.IO) {
        val existing = repositoryDao.getRepositoryById(repoId)
            ?: return@withContext Result.failure(IllegalStateException("Repository not found"))

        val metaResult = gitHubClient.fetchRepoMetadata(
            owner = existing.owner,
            repo = existing.name,
            repoSpecificToken = existing.repoSpecificToken
        )
        val meta = metaResult.getOrNull()

        val branchToSync = meta?.defaultBranch ?: existing.defaultBranch
        val treeResult = gitHubClient.fetchRecursiveTree(
            owner = existing.owner,
            repo = existing.name,
            branch = branchToSync,
            repoSpecificToken = existing.repoSpecificToken
        )

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

        val updatedEntity = existing.copy(
            defaultBranch = branchToSync,
            description = meta?.description ?: existing.description,
            totalFilesCount = fileNodes.size,
            markdownFilesCount = mdCount,
            lastSyncedEpochMs = System.currentTimeMillis()
        )

        repositoryDao.upsertRepository(updatedEntity)
        Result.success(updatedEntity.toDomainModel(updatedEntity.accentIndex))
    }

    override suspend fun removeRepository(repoId: String) = withContext(Dispatchers.IO) {
        repositoryDao.deleteRepositoryById(repoId)
        repoTreeDao.deleteTreeForRepo(repoId)
        cachedDocumentDao.deleteDocumentsForRepo(repoId)
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

        val remoteResult = gitHubClient.fetchRecursiveTree(
            owner = repo.owner,
            repo = repo.name,
            branch = repo.defaultBranch,
            repoSpecificToken = repo.repoSpecificToken
        )

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

    override suspend fun loadDocumentContent(
        repo: GitRepositoryCard,
        node: RepoFileNode,
        forceRefresh: Boolean
    ): Result<RawDocumentPayload> = withContext(Dispatchers.IO) {
        val docId = "${repo.id}:${node.path}"

        // Stream live from GitHub first (unless already cached in this session with matching SHA and !forceRefresh)
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

        val liveResult = gitHubClient.fetchFileRawText(
            owner = repo.owner,
            repo = repo.name,
            branch = repo.defaultBranch,
            path = node.path,
            repoSpecificToken = repo.repoSpecificToken
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

        Result.failure(
            liveResult.exceptionOrNull()
                ?: IllegalStateException("Could not load ${node.path} from GitHub.")
        )
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
            owner = owner,
            name = name,
            defaultBranch = defaultBranch,
            description = description,
            repoSpecificToken = repoSpecificToken,
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
}
