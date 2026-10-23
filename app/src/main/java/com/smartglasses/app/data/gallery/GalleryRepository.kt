package com.smartglasses.app.data.gallery

import android.content.Context
import android.net.Uri
import com.jueyuantech.venussdk.VNConstant
import com.jueyuantech.venussdk.bean.VNFile
import com.smartglasses.app.data.venus.GlassesRepository
import com.smartglasses.app.data.venus.VenusClient
import com.smartglasses.app.data.venus.VenusEvent
import com.smartglasses.app.data.venus.VenusResult
import com.smartglasses.app.di.ApplicationScope
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GalleryPhoto
import com.smartglasses.app.domain.model.GalleryProblem
import com.smartglasses.app.domain.model.GalleryState
import com.smartglasses.app.domain.model.GalleryUpload
import com.smartglasses.app.domain.model.GlassesDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The glasses' Gallery (guide §5.5, §5.6, §6.6): list, import, cast, delete, clear.
 *
 * The SDK cannot download photos from the glasses, so each photo imported here keeps a copy in
 * app storage (one folder per pair of glasses) for the grid. Photos put there by other apps have
 * no picture on the phone. Uploads run one at a time. The test device keeps its "glasses" photos
 * in its folder only. State changes run on one confined dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GalleryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val venus: VenusClient,
    private val glasses: GlassesRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    private val _problems = MutableSharedFlow<GalleryProblem>(extraBufferCapacity = 8)
    val problems: SharedFlow<GalleryProblem> = _problems.asSharedFlow()

    /** The last listing, by name: removing and showing a photo take the SDK's own file objects. */
    private var deviceFiles: Map<String, VNFile> = emptyMap()
    private val queue = Channel<GalleryUpload>(Channel.UNLIMITED)
    private val uploadIds = AtomicLong()

    init {
        scope.launch { for (upload in queue) runUpload(upload) }
        scope.launch(confined) {
            venus.events.collect { event ->
                // The wearer left the Gallery: no photo is shown any more.
                if (event is VenusEvent.ViewChanged && event.view != VNConstant.View.GALLERY) _state.update { it.copy(casting = null) }
            }
        }
        scope.launch(confined) {
            // Other glasses (or none) have other photos.
            glasses.state.map { (it as? ConnectionState.Connected)?.device?.address }.distinctUntilChanged().collect {
                deviceFiles = emptyMap()
                _state.value = GalleryState()
            }
        }
    }

    private val device: GlassesDevice? get() = (glasses.state.value as? ConnectionState.Connected)?.device

    private fun folder(device: GlassesDevice): File =
        File(context.filesDir, "gallery/" + device.address.replace(NOT_FILE_SAFE, "_")).apply { mkdirs() }

    /** Reads the photo list and free space again (Refresh Storage, opening the page). */
    fun refresh() {
        scope.launch(confined) { load() }
    }

    /** Runs in [confined]. */
    private suspend fun load() {
        val device = device ?: return
        val dir = folder(device)
        _state.update { it.copy(loading = true) }
        if (device.simulated) {
            val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
            val usedKb = files.sumOf { it.length() } / 1024
            _state.update { s ->
                s.copy(
                    loading = false,
                    photos = files.filter { f -> s.uploads.none { it.name == f.name } }.map { GalleryPhoto(it.name, it.length(), it) },
                    freeKb = (SIMULATED_STORAGE_KB - usedKb).coerceAtLeast(0),
                )
            }
            return
        }
        val files = venus.galleryFiles()
        val freeKb = venus.romUsage()?.remaining?.toLong()
        if (files == null) {
            _state.update { it.copy(loading = false, freeKb = freeKb ?: it.freeKb) }
            _problems.tryEmit(GalleryProblem.LIST_FAILED)
            return
        }
        deviceFiles = files.filter { it.name != null }.associateBy { it.name }
        // Copies of photos no longer on the glasses are of no use; keep those still uploading.
        val uploading = _state.value.uploads.map { it.name }.toSet()
        dir.listFiles().orEmpty().filter { it.name !in deviceFiles && it.name !in uploading }.forEach { it.delete() }
        _state.update { s ->
            s.copy(
                loading = false,
                photos = deviceFiles.values.map { f -> GalleryPhoto(f.name, f.size?.toLong(), File(dir, f.name).takeIf { it.exists() }) },
                freeKb = freeKb ?: s.freeKb,
            )
        }
    }

    /** Photos picked on the phone: shrunk (see [GalleryImages]) and queued for upload. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(confined) {
            val device = device ?: return@launch
            val dir = folder(device)
            _state.update { it.copy(preparing = true) }
            var unreadable = 0
            for (uri in uris) {
                val jpeg = withContext(Dispatchers.Default) { GalleryImages.prepare(context.contentResolver, uri) }
                if (jpeg == null) {
                    unreadable++
                    continue
                }
                val name = GalleryImages.nameFor(jpeg)
                val file = File(dir, name)
                val onGlasses = _state.value.photos.firstOrNull { it.name == name }
                if (onGlasses != null || _state.value.uploads.any { it.name == name }) {
                    // Already there; at most its picture on the phone was missing.
                    if (onGlasses != null && onGlasses.thumbnail == null) {
                        withContext(Dispatchers.IO) { file.writeBytes(jpeg) }
                        _state.update { s -> s.copy(photos = s.photos.map { if (it.name == name) it.copy(thumbnail = file) else it }) }
                    }
                    continue
                }
                withContext(Dispatchers.IO) { file.writeBytes(jpeg) }
                val upload = GalleryUpload(uploadIds.incrementAndGet(), name, file)
                _state.update { it.copy(uploads = it.uploads + upload) }
                queue.send(upload)
            }
            _state.update { it.copy(preparing = false) }
            if (unreadable > 0) _problems.tryEmit(GalleryProblem.IMPORT_FAILED)
        }
    }

    fun retry(uploadId: Long) {
        scope.launch(confined) {
            val upload = _state.value.uploads.firstOrNull { it.id == uploadId && it.failed } ?: return@launch
            val again = upload.copy(failed = false, progress = 0f)
            _state.update { s -> s.copy(uploads = s.uploads.map { if (it.id == uploadId) again else it }) }
            queue.send(again)
        }
    }

    /** Drops a failed upload and its copy on the phone. */
    fun discard(uploadId: Long) {
        scope.launch(confined) {
            val upload = _state.value.uploads.firstOrNull { it.id == uploadId && it.failed } ?: return@launch
            _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == uploadId }) }
            if (_state.value.photos.none { it.name == upload.name }) upload.file.delete()
        }
    }

    /** One at a time, in import order. */
    private suspend fun runUpload(upload: GalleryUpload) {
        // Removed meanwhile, e.g. the glasses changed.
        if (_state.value.uploads.none { it.id == upload.id && !it.failed }) return
        val device = device ?: return fail(upload, GalleryProblem.UPLOAD_FAILED)
        if (device.simulated) {
            delay(SIMULATED_UPLOAD_MS)
            return finish(upload)
        }
        val freeKb = venus.romUsage()?.remaining?.toLong()
        if (freeKb != null && freeKb * 1024 < upload.file.length() + SPACE_MARGIN_BYTES) return fail(upload, GalleryProblem.NO_SPACE)
        var shownPercent = -1
        val error = venus.upload(upload.file, VNConstant.VNFile.Type.GALLERY) { sent, total ->
            val progress = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
            val percent = (progress * 100).toInt()
            if (percent != shownPercent) {
                shownPercent = percent
                _state.update { s -> s.copy(uploads = s.uploads.map { if (it.id == upload.id) it.copy(progress = progress) else it }) }
            }
        }
        if (error == null) finish(upload) else fail(upload, GalleryProblem.UPLOAD_FAILED)
    }

    private suspend fun finish(upload: GalleryUpload): Unit = withContext(confined) {
        _state.update { s -> s.copy(uploads = s.uploads.filterNot { it.id == upload.id }) }
        load()
    }

    private suspend fun fail(upload: GalleryUpload, problem: GalleryProblem): Unit = withContext(confined) {
        _state.update { s -> s.copy(uploads = s.uploads.map { if (it.id == upload.id) it.copy(failed = true) else it }) }
        _problems.tryEmit(problem)
        Unit
    }

    /** Opens the glasses' Gallery and shows [name] there. */
    fun cast(name: String) {
        scope.launch(confined) {
            if (_state.value.castPending != null) return@launch
            val device = device ?: return@launch
            _state.update { it.copy(castPending = name) }
            val shown = device.simulated || deviceFiles[name]?.let { file ->
                venus.setView(VNConstant.View.GALLERY) == VenusResult.SUCCESS && venus.showGalleryFile(file) == VenusResult.SUCCESS
            } == true
            _state.update { it.copy(castPending = null, casting = if (shown) name else it.casting) }
            if (!shown) _problems.tryEmit(GalleryProblem.CAST_FAILED)
        }
    }

    fun delete(names: Set<String>) {
        if (names.isEmpty()) return
        scope.launch(confined) {
            val device = device ?: return@launch
            val dir = folder(device)
            var failed = false
            for (name in names) {
                val removed = device.simulated || deviceFiles[name]?.let { venus.removeFile(it) == VenusResult.SUCCESS } == true
                if (removed) File(dir, name).delete() else failed = true
            }
            _state.update { if (it.casting in names) it.copy(casting = null) else it }
            load()
            if (failed) _problems.tryEmit(GalleryProblem.DELETE_FAILED)
        }
    }

    /** Clear Device Photos: every photo in the glasses' Gallery, also ones from other apps. */
    fun clearAll() {
        scope.launch(confined) {
            val device = device ?: return@launch
            val cleared = device.simulated || venus.clearGallery() == VenusResult.SUCCESS
            if (cleared) {
                val uploading = _state.value.uploads.map { it.name }.toSet()
                folder(device).listFiles().orEmpty().filter { it.name !in uploading }.forEach { it.delete() }
                _state.update { it.copy(casting = null) }
            }
            load()
            if (!cleared) _problems.tryEmit(GalleryProblem.CLEAR_FAILED)
        }
    }

    private companion object {
        val NOT_FILE_SAFE = Regex("[^A-Za-z0-9]")
        /** Room for the upload's own bookkeeping on the glasses. */
        const val SPACE_MARGIN_BYTES = 16 * 1024L
        /** Like the real glasses (about 4 MB free). */
        const val SIMULATED_STORAGE_KB = 4_200L
        const val SIMULATED_UPLOAD_MS = 600L
    }
}
