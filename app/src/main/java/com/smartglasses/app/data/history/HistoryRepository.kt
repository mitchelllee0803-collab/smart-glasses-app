package com.smartglasses.app.data.history

import com.smartglasses.app.domain.TranscriptArchive
import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.RecordDetail
import com.smartglasses.app.domain.model.RecordSummary
import com.smartglasses.app.domain.model.TranscriptLine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Saved translation sessions, kept only on this phone (app database, excluded from backup). */
@Singleton
class HistoryRepository @Inject constructor(private val dao: RecordDao) : TranscriptArchive {
    /** Keeps record numbers unique when two records are created at once. */
    private val numbering = Mutex()

    override suspend fun createRecord(startedAt: Long, source: Language, target: Language): Long = numbering.withLock {
        dao.insert(
            RecordEntity(
                number = dao.maxNumber() + 1,
                title = null,
                createdAt = startedAt,
                sourceLanguage = source.engineCode,
                targetLanguage = target.engineCode,
            ),
        )
    }

    override suspend fun saveLine(recordId: Long, line: TranscriptLine) =
        dao.upsertLine(RecordLineEntity(recordId, line.id, line.original, line.translation))

    /** All records, newest first; a non-blank [query] matches titles and the text of the phrases. */
    fun records(query: String): Flow<List<RecordSummary>> =
        dao.summaries(likePattern(query)).map { rows -> rows.map { it.toSummary() } }

    fun record(id: Long): Flow<RecordDetail?> = combine(dao.summary(id), dao.lines(id)) { row, lines ->
        row?.let {
            RecordDetail(
                summary = it.toSummary(),
                lines = lines.map { l -> TranscriptLine(l.lineId, l.original, l.translation, isFinal = true) },
            )
        }
    }

    /** A blank [title] restores the default title. */
    suspend fun rename(id: Long, title: String) = dao.rename(id, title.trim().ifEmpty { null })

    suspend fun delete(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.delete(ids.toList())
    }

    private fun RecordSummaryRow.toSummary() = RecordSummary(
        id = id,
        number = number,
        customTitle = title,
        createdAt = createdAt,
        source = Language.fromCode(sourceLanguage) ?: Language.ENGLISH,
        target = Language.fromCode(targetLanguage) ?: Language.ENGLISH,
        previewOriginal = previewOriginal.orEmpty(),
        previewTranslation = previewTranslation.orEmpty(),
    )

    companion object {
        /** LIKE pattern for a substring search, with %, _ and \ in the query matched literally. */
        fun likePattern(query: String): String? {
            val q = query.trim()
            if (q.isEmpty()) return null
            return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
    }
}
