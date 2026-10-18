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

@Entity(tableName = "repositories")
data class RepositoryEntity(
    @PrimaryKey val id: String,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val description: String,
    val repoSpecificToken: String,
    val totalFilesCount: Int,
    val markdownFilesCount: Int,
    val lastSyncedEpochMs: Long,
    val tileStyleName: String,
    val accentIndex: Int
)

@Entity(tableName = "repo_tree_nodes")
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

@Entity(tableName = "cached_documents")
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
interface RepositoryDao {
    @Query("SELECT * FROM repositories ORDER BY lastSyncedEpochMs DESC")
    fun observeAllRepositories(): Flow<List<RepositoryEntity>>

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
interface RepoTreeDao {
    @Query("SELECT * FROM repo_tree_nodes WHERE repoId = :repoId ORDER BY isDirectory DESC, path ASC")
    fun observeTreeForRepo(repoId: String): Flow<List<RepoTreeNodeEntity>>

    @Query("SELECT * FROM repo_tree_nodes WHERE repoId = :repoId ORDER BY isDirectory DESC, path ASC")
    suspend fun getTreeForRepo(repoId: String): List<RepoTreeNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreeNodes(nodes: List<RepoTreeNodeEntity>)

    @Query("DELETE FROM repo_tree_nodes WHERE repoId = :repoId")
    suspend fun deleteTreeForRepo(repoId: String)
}

@Dao
interface CachedDocumentDao {
    @Query("SELECT * FROM cached_documents WHERE id = :docId LIMIT 1")
    suspend fun getCachedDocument(docId: String): CachedDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedDocument(entity: CachedDocumentEntity)

    @Query("DELETE FROM cached_documents WHERE repoId = :repoId")
    suspend fun deleteDocumentsForRepo(repoId: String)
}

@Database(
    entities = [
        RepositoryEntity::class,
        RepoTreeNodeEntity::class,
        CachedDocumentEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class GitFolioDatabase : RoomDatabase() {
    abstract fun repositoryDao(): RepositoryDao
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
