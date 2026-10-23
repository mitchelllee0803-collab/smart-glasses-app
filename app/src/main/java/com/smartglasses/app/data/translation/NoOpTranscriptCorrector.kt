package com.smartglasses.app.data.translation

import com.smartglasses.app.domain.TranscriptCorrector
import com.smartglasses.app.domain.model.Language
import javax.inject.Inject

/** Placeholder until the offline AI text correction step is built: the transcript passes unchanged. */
class NoOpTranscriptCorrector @Inject constructor() : TranscriptCorrector {
    override suspend fun correct(text: String, language: Language, context: List<String>): String = text
}
