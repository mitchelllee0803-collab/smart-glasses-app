package com.smartglasses.app.data.history

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: Int,
    /** Null until the user renames the record. */
    val title: String?,
    val createdAt: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
)

@Entity(
    tableName = "record_lines",
    primaryKeys = ["recordId", "lineId"],
    foreignKeys = [ForeignKey(RecordEntity::class, ["id"], ["recordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recordId")],
)
data class RecordLineEntity(
    val recordId: Long,
    /** Increases in speaking order within a record. */
    val lineId: Long,
    val original: String,
    val translation: String,
)

/** A record with the first phrase as preview. */
data class RecordSummaryRow(
    val id: Long,
    val number: Int,
    val title: String?,
    val createdAt: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val previewOriginal: String?,
    val previewTranslation: String?,
)

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(record: RecordEntity): Long

    @Query("SELECT COALESCE(MAX(number), 0) FROM records")
    suspend fun maxNumber(): Int

    @Upsert
    suspend fun upsertLine(line: RecordLineEntity)

    /** [pattern] is a LIKE pattern (with '\' as escape) or null for all records. */
    @Query(
        """
        SELECT r.id, r.number, r.title, r.createdAt, r.sourceLanguage, r.targetLanguage,
            (SELECT original FROM record_lines WHERE recordId = r.id ORDER BY lineId LIMIT 1) AS previewOriginal,
            (SELECT translation FROM record_lines WHERE recordId = r.id ORDER BY lineId LIMIT 1) AS previewTranslation
        FROM records r
        WHERE :pattern IS NULL
            OR r.title LIKE :pattern ESCAPE '\'
            OR EXISTS (
                SELECT 1 FROM record_lines l WHERE l.recordId = r.id
                AND (l.original LIKE :pattern ESCAPE '\' OR l.translation LIKE :pattern ESCAPE '\')
            )
        ORDER BY r.createdAt DESC, r.id DESC
        """,
    )
    fun summaries(pattern: String?): Flow<List<RecordSummaryRow>>

    @Query(
        """
        SELECT r.id, r.number, r.title, r.createdAt, r.sourceLanguage, r.targetLanguage,
            NULL AS previewOriginal, NULL AS previewTranslation
        FROM records r WHERE r.id = :id
        """,
    )
    fun summary(id: Long): Flow<RecordSummaryRow?>

    @Query("SELECT * FROM record_lines WHERE recordId = :recordId ORDER BY lineId")
    fun lines(recordId: Long): Flow<List<RecordLineEntity>>

    @Query("UPDATE records SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String?)

    @Query("DELETE FROM record_lines WHERE recordId IN (:ids)")
    suspend fun deleteLines(ids: List<Long>)

    @Query("DELETE FROM records WHERE id IN (:ids)")
    suspend fun deleteRecords(ids: List<Long>)

    /** Lines are deleted explicitly as well, so this does not rely on foreign-key enforcement. */
    @Transaction
    suspend fun delete(ids: List<Long>) {
        deleteLines(ids)
        deleteRecords(ids)
    }
}

@Database(entities = [RecordEntity::class, RecordLineEntity::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
}
