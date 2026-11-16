package org.wenji.study

import android.content.Context
import androidx.room.*

/** A durable outbox: the same request UUID is reused after process death or timeout. */
@Entity(tableName = "pending_questions")
data class PendingQuestion(@PrimaryKey val requestId: String, val text: String, val createdAt: Long)
@Entity(tableName = "server_cache")
data class CachedState(@PrimaryKey val id: Int = 1, val json: String, val fetchedAt: Long)
@Entity(tableName = "pending_writes")
data class PendingWrite(@PrimaryKey val requestId: String, val path: String, val createdAt: Long)
@Entity(tableName = "pending_write_chunks", primaryKeys = ["requestId", "chunkIndex"])
data class PendingWriteChunk(val requestId: String, val chunkIndex: Int, val body: String)
@Dao
interface LocalDao {
    @Query("SELECT * FROM pending_questions ORDER BY createdAt") suspend fun pending(): List<PendingQuestion>
    @Insert suspend fun insert(question: PendingQuestion)
    @Query("DELETE FROM pending_questions WHERE requestId = :id") suspend fun removePending(id: String)
    @Query("SELECT * FROM server_cache ORDER BY id") suspend fun cache(): List<CachedState>
    @Query("DELETE FROM server_cache") suspend fun clearCache()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCache(state: List<CachedState>)
    @Query("SELECT * FROM pending_writes ORDER BY createdAt") suspend fun writes(): List<PendingWrite>
    @Insert suspend fun insertWrite(write: PendingWrite)
    @Query("DELETE FROM pending_writes WHERE requestId = :id") suspend fun removeWrite(id: String)
    @Query("SELECT * FROM pending_write_chunks WHERE requestId = :id ORDER BY chunkIndex") suspend fun writeChunks(id: String): List<PendingWriteChunk>
    @Insert suspend fun insertChunks(chunks: List<PendingWriteChunk>)
    @Query("DELETE FROM pending_write_chunks WHERE requestId = :id") suspend fun removeChunks(id: String)
}
@Database(entities = [PendingQuestion::class, CachedState::class, PendingWrite::class, PendingWriteChunk::class], version = 1, exportSchema = true)
abstract class LocalStore : RoomDatabase() {
    abstract fun dao(): LocalDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, LocalStore::class.java, "wenji.db").build()
    }
}
