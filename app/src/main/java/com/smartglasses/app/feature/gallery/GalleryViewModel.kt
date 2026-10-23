package com.smartglasses.app.feature.gallery

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.gallery.GalleryRepository
import com.smartglasses.app.data.venus.GlassesRepository
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GalleryProblem
import com.smartglasses.app.domain.model.GalleryState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Gallery page; selection for deleting lives here, the photos in [GalleryRepository]. */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val gallery: GalleryRepository,
    glasses: GlassesRepository,
) : ViewModel() {
    val state: StateFlow<GalleryState> = gallery.state
    val problems: SharedFlow<GalleryProblem> = gallery.problems

    val connected: StateFlow<Boolean> = glasses.state.map { it is ConnectionState.Connected }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, glasses.state.value is ConnectionState.Connected)

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    init {
        gallery.refresh()
        // Back after a dropped link: the list was cleared, read it again.
        viewModelScope.launch { connected.drop(1).filter { it }.collect { gallery.refresh() } }
    }

    fun refresh() = gallery.refresh()

    fun import(uris: List<Uri>) = gallery.import(uris)

    fun cast(name: String) = gallery.cast(name)

    fun retry(uploadId: Long) = gallery.retry(uploadId)

    fun discard(uploadId: Long) = gallery.discard(uploadId)

    fun setEditing(on: Boolean) {
        _editing.value = on
        _selected.value = emptySet()
    }

    fun toggle(name: String) {
        _selected.value = _selected.value.let { if (name in it) it - name else it + name }
    }

    fun selectAll(all: Boolean) {
        _selected.value = if (all) state.value.photos.map { it.name }.toSet() else emptySet()
    }

    fun deleteSelected() {
        gallery.delete(_selected.value)
        setEditing(false)
    }

    fun clearAll() = gallery.clearAll()
}
