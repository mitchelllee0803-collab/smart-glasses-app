package com.smartglasses.app.domain

import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.TranscriptLine

/** Stores finished phrases of a translation session so the session can be reviewed later. */
interface TranscriptArchive {
    /** Creates the record of a session; returns its id. */
    suspend fun createRecord(startedAt: Long, source: Language, target: Language): Long

    /** Adds or replaces one finished phrase of the record. */
    suspend fun saveLine(recordId: Long, line: TranscriptLine)
}
