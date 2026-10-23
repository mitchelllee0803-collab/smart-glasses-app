package com.smartglasses.app.feature.touchpad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.settings.SettingsRepository
import com.smartglasses.app.data.venus.GlassesRepository
import com.smartglasses.app.data.venus.TouchGesture
import com.smartglasses.app.data.venus.VirtualTouchpad
import com.smartglasses.app.domain.model.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The floating touchpad above every page: shown while it is switched on and glasses are connected. */
@HiltViewModel
class TouchpadViewModel @Inject constructor(
    settings: SettingsRepository,
    glasses: GlassesRepository,
    private val touchpad: VirtualTouchpad,
) : ViewModel() {

    val visible: StateFlow<Boolean> =
        combine(settings.settings, glasses.state) { s, connection -> s.touchpadPanel && connection is ConnectionState.Connected }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun send(gesture: TouchGesture) {
        viewModelScope.launch { touchpad.send(gesture) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
