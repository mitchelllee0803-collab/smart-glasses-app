package com.smartglasses.app.domain.model

import androidx.compose.runtime.Immutable

/** A saved translation session as listed in "All Records". */
@Immutable
data class RecordSummary(
    val id: Long,
    /** Running number used for the default title ("Record 3"). */
    val number: Int,
    /** Name given by the user; null shows the default title. */
    val customTitle: String?,
    val createdAt: Long,
    val source: Language,
    val target: Language,
    /** First phrase of the session, shown as a preview. */
    val previewOriginal: String,
    val previewTranslation: String,
)

@Immutable
data class RecordDetail(val summary: RecordSummary, val lines: List<TranscriptLine>)
