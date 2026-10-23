package com.smartglasses.app.domain

import com.smartglasses.app.domain.model.Language

/**
 * On-device AI translation of finished sentences (Hy-MT2). Optional: when it is unavailable or
 * fails, the standard offline engine translates instead.
 */
interface AiTranslator {
    /** Whether [translate] should be used for this pair right now (model installed and usable). */
    fun handles(source: Language, target: Language): Boolean

    /** Loads the model ahead of the first sentence. Best effort, returns at once. */
    fun prepare()

    /**
     * Translates one finished sentence. [context] holds the preceding source sentences of the
     * conversation, oldest first. Throws on failure; cancellable.
     */
    suspend fun translate(text: String, source: Language, target: Language, context: List<String>): String
}

/**
 * Fixes recognition errors in a finished sentence before it is translated. Must keep the meaning
 * and return [text] unchanged when unsure. The current implementation leaves text as it is.
 */
interface TranscriptCorrector {
    suspend fun correct(text: String, language: Language, context: List<String>): String
}
