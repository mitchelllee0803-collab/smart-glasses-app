package com.smartglasses.app.feature.glasses

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.venus.GlassesRepository
import com.smartglasses.app.data.venus.VenusResult
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GlassesInfo
import com.smartglasses.app.domain.model.GlassesReset
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Settings → About Glasses: the connected glasses' name, unbind and factory reset. */
@HiltViewModel
class AboutGlassesViewModel @Inject constructor(private val glasses: GlassesRepository) : ViewModel() {
    val connection: StateFlow<ConnectionState> = glasses.state

    private val _running = MutableStateFlow<GlassesReset?>(null)
    /** The unbind or reset waiting for the glasses' answer. */
    val running: StateFlow<GlassesReset?> = _running.asStateFlow()

    private val _finished = MutableSharedFlow<Pair<GlassesReset, Boolean>>(extraBufferCapacity = 1)
    /** (action, accepted by the glasses). */
    val finished: SharedFlow<Pair<GlassesReset, Boolean>> = _finished.asSharedFlow()

    fun reset(kind: GlassesReset) {
        if (_running.value != null) return
        _running.value = kind
        viewModelScope.launch {
            val ok = glasses.reset(kind) == VenusResult.SUCCESS
            _running.value = null
            _finished.emit(kind to ok)
        }
    }
}

@Immutable
sealed interface GlassesInfoState {
    data object Loading : GlassesInfoState
    data class Loaded(val info: GlassesInfo) : GlassesInfoState
    /** Not connected, or the glasses did not answer. */
    data object Unavailable : GlassesInfoState
}

/** About Glasses → Glasses Info: what the glasses report about themselves, read on opening. */
@HiltViewModel
class GlassesInfoViewModel @Inject constructor(private val glasses: GlassesRepository) : ViewModel() {
    private val _state = MutableStateFlow<GlassesInfoState>(GlassesInfoState.Loading)
    val state: StateFlow<GlassesInfoState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = GlassesInfoState.Loading
        viewModelScope.launch {
            _state.value = glasses.deviceInfo()?.let(GlassesInfoState::Loaded) ?: GlassesInfoState.Unavailable
        }
    }
}
