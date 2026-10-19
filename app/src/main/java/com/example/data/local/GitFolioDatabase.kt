package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Keystone Architecture — Section 14 (Data Placement):
 *
 * 1. SOURCE OF TRUTH & DURABLE OPERATIONAL STATE:
 *    - `repositories` (source of truth for user-mounted repositories)
 *    - `provider_credentials` (secret store for host OAuth tokens)
 *    - `resource_versions` (durable monotonic version + contentHash per resourceId)
 *    - `operation_journal` (durable write-ahead mutation protocol journal for crash recovery, idempotency, and undo)
 *    - `warden_audit_log` (append-only tamper-evident hash chain of Warden decisions)
 *
 * 2. REBUILDABLE DERIVED STATE (can be wiped and regenerated at any time without losing truth):
 *    - `repo_tree_nodes` (derived file tree index)
 *    - `cached_documents` (derived markdown blob cache)
 */

@Entity(tableName = "provider_credentials")
data class ProviderCredentialEntity(
    @PrimaryKey val providerId: String, // "github", "gitlab", "codeberg"
    val accessToken: String,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "repositories")
data class RepositoryEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val branchesCsv: String = "",
    val description: String,
    val totalFilesCount: Int,
    val markdownFilesCount: Int,
    val lastSyncedEpochMs: Long,
    val tileStyleName: String,
    val accentIndex: Int
)

@Entity(tableName = "resource_versions")
data class ResourceVersionEntity(
    @PrimaryKey val resourceId: String,
    val version: Long,
    val contentHash: String,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "operation_journal",
    indices = [
        androidx.room.Index(value = ["state", "updatedAtEpochMs"]),
        androidx.room.Index(value = ["updatedAtEpochMs"])
    ]
)
data class OperationJournalEntity(
    @PrimaryKey val operationId: String,
    val commandTopic: String,
    val resourceId: String,
    val expectedPreconditionHash: String,
    val targetResultHash: String,
    val argumentsHash: String,
    val argumentsJson: String,
    val beforeSnapshotJson: String?,
    val afterSnapshotJson: String?,
    val state: String, // OperationState.name
    val actorId: String,
    val actorKind: String,
    val workspaceId: String,
    val workspaceGeneration: Long,
    val resultSummary: String?,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "warden_audit_log")
data class AuditChainEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0L,
    val operationId: String,
    val actorId: String,
    val actorKind: String,
    val capability: String,
    val topic: String,
    val resourceId: String,
    val decision: String,
    val timestampEpochMs: Long,
    val previousHash: String,
    val entryHash: String
)

@Entity(
    tableName = "repo_tree_nodes",
    indices = [
        androidx.room.Index(value = ["repoId", "isDirectory", "path"])
    ]
)
data class RepoTreeNodeEntity(
    @PrimaryKey val id: String, // "$repoId:$path"
    val repoId: String,
    val path: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val sha: String,
    val depth: Int
)

@Entity(
    tableName = "cached_documents",
    indices = [
        androidx.room.Index(value = ["repoId"])
    ]
)
data class CachedDocumentEntity(
    @PrimaryKey val id: String, // "$repoId:$path"
    val repoId: String,
    val path: String,
    val fileName: String,
    val rawContent: String,
    val sizeBytes: Long,
    val sha: String,
    val cachedAtEpochMs: Long
)

@Dao
interface ProviderCredentialDao {
    @Query("SELECT * FROM provider_credentials")
    fun observeAllCredentials(): Flow<List<ProviderCredentialEntity>>

    @Query("SELECT * FROM provider_credentials WHERE providerId = :providerId LIMIT 1")
    suspend fun getCredential(providerId: String): ProviderCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCredential(entity: ProviderCredentialEntity)

    @Query("DELETE FROM provider_credentials WHERE providerId = :providerId")
    suspend fun deleteCredential(providerId: String)
}

@Dao
interface RepositoryDao {
    @Query("SELECT * FROM repositories ORDER BY lastSyncedEpochMs DESC")
    fun observeAllRepositories(): Flow<List<RepositoryEntity>>

    @Query("SELECT * FROM repositories")
    suspend fun getAllRepositories(): List<RepositoryEntity>

    @Query("SELECT COUNT(*) FROM repositories")
    suspend fun getRepositoryCount(): Int

    @Query("SELECT * FROM repositories WHERE id = :repoId LIMIT 1")
    suspend fun getRepositoryById(repoId: String): RepositoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRepository(entity: RepositoryEntity)

    @Query("DELETE FROM repositories WHERE id = :repoId")
    suspend fun deleteRepositoryById(repoId: String)
}

@Dao
interface ResourceVersionDao {
    @Query("SELECT * FROM resource_versions WHERE resourceId = :resourceId LIMIT 1")
    suspend fun getResourceVersion(resourceId: String): ResourceVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResourceVersion(entity: ResourceVersionEntity)

    @Query("SELECT * FROM resource_versions")
    fun observeAllResourceVersions(): Flow<List<ResourceVersionEntity>>
}

@Dao
interface OperationJournalDao {
    @Query("SELECT * FROM operation_journal WHERE operationId = :operationId LIMIT 1")
    suspend fun getOperation(operationId: String): OperationJournalEntity?

    @Query("SELECT * FROM operation_journal WHERE state NOT IN ('COMMITTED', 'REJECTED', 'CONFLICTED', 'FAILED') ORDER BY updatedAtEpochMs ASC")
    suspend fun getNonTerminalOperations(): List<OperationJournalEntity>

    @Query("SELECT * FROM operation_journal WHERE state = 'COMMITTED' AND beforeSnapshotJson IS NOT NULL ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestUndoableCommittedOperation(): OperationJournalEntity?

    @Query("SELECT * FROM operation_journal ORDER BY updatedAtEpochMs DESC LIMIT 60")
    fun observeRecentOperations(): Flow<List<OperationJournalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOperation(entity: OperationJournalEntity)
}

@Dao
interface AuditChainDao {
    @Query("SELECT * FROM warden_audit_log ORDER BY seq DESC LIMIT 1")
    suspend fun getLatestAuditEntry(): AuditChainEntity?

    @Query("SELECT * FROM warden_audit_log ORDER BY seq DESC LIMIT 80")
    fun observeRecentAuditEntries(): Flow<List<AuditChainEntity>>

    @Query("SELECT * FROM warden_audit_log ORDER BY seq ASC")
    suspend fun getAllAuditEntriesOrdered(): List<AuditChainEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEntry(entity: AuditChainEntity): Long
}

@Dao
interface RepoTreeDao {
    @Query("SELECT * FROM repo_tree_nodes WHERE repoId = :repoId ORDER BY isDirectory DESC, path ASC")
    fun observeTreeForRepo(repoId: String): Flow<List<RepoTreeNodeEntity>>

    @Query("SELECT * FROM repo_tree_nodes WHERE repoId = :repoId ORDER BY isDirectory DESC, path ASC")
    suspend fun getTreeForRepo(repoId: String): List<RepoTreeNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreeNodes(nodes: List<RepoTreeNodeEntity>)

    @Query("DELETE FROM repo_tree_nodes WHERE repoId = :repoId")
    suspend fun deleteTreeForRepo(repoId: String)

    @Query("DELETE FROM repo_tree_nodes")
    suspend fun clearAllDerivedTreeNodes()
}

@Dao
interface CachedDocumentDao {
    @Query("SELECT * FROM cached_documents WHERE id = :docId LIMIT 1")
    suspend fun getCachedDocument(docId: String): CachedDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedDocument(entity: CachedDocumentEntity)

    @Query("DELETE FROM cached_documents WHERE repoId = :repoId")
    suspend fun deleteDocumentsForRepo(repoId: String)

    @Query("DELETE FROM cached_documents")
    suspend fun clearAllDerivedDocuments()
}

@Database(
    entities = [
        ProviderCredentialEntity::class,
        RepositoryEntity::class,
        ResourceVersionEntity::class,
        OperationJournalEntity::class,
        AuditChainEntity::class,
        RepoTreeNodeEntity::class,
        CachedDocumentEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class GitFolioDatabase : RoomDatabase() {
    abstract fun providerCredentialDao(): ProviderCredentialDao
    abstract fun repositoryDao(): RepositoryDao
    abstract fun resourceVersionDao(): ResourceVersionDao
    abstract fun operationJournalDao(): OperationJournalDao
    abstract fun auditChainDao(): AuditChainDao
    abstract fun repoTreeDao(): RepoTreeDao
    abstract fun cachedDocumentDao(): CachedDocumentDao

    companion object {
        @Volatile
        private var INSTANCE: GitFolioDatabase? = null

        fun getInstance(context: Context): GitFolioDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GitFolioDatabase::class.java,
                    "gitfolio_clean_reader.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
