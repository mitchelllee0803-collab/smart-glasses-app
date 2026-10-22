package com.smartglasses.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.smartglasses.app.data.history.HistoryDatabase
import com.smartglasses.app.data.history.HistoryRepository
import com.smartglasses.app.data.history.RecordDao
import com.smartglasses.app.data.speech.AudioInput
import com.smartglasses.app.data.speech.MicrophoneCapture
import com.smartglasses.app.data.translation.EngineRepository
import com.smartglasses.app.data.venus.VenusSubtitleSink
import com.smartglasses.app.domain.OfflineSpeechEngine
import com.smartglasses.app.domain.SubtitleSink
import com.smartglasses.app.domain.TranscriptArchive
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Scope for work that outlives screens (connection, translation session). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun historyDatabase(@ApplicationContext context: Context): HistoryDatabase =
        Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()

    @Provides
    fun recordDao(database: HistoryDatabase): RecordDao = database.records()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun subtitleSink(impl: VenusSubtitleSink): SubtitleSink

    @Binds
    abstract fun offlineSpeechEngine(impl: EngineRepository): OfflineSpeechEngine

    @Binds
    abstract fun audioInput(impl: MicrophoneCapture): AudioInput

    @Binds
    abstract fun transcriptArchive(impl: HistoryRepository): TranscriptArchive
}
