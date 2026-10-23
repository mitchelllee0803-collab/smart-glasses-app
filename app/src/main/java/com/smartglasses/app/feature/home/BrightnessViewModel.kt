package com.smartglasses.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.venus.BrightnessController
import com.smartglasses.app.data.venus.BrightnessProblem
import com.smartglasses.app.data.venus.BrightnessState
import com.smartglasses.app.data.venus.VenusBrightnessLink
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The dashboard's brightness popup. */
@HiltViewModel
class BrightnessViewModel @Inject constructor(link: VenusBrightnessLink) : ViewModel() {
    private val controller = BrightnessController(link, viewModelScope)

    val state: StateFlow<BrightnessState> = controller.state
    val problems: SharedFlow<BrightnessProblem> = controller.problems

    fun load() = controller.load()

    fun setPercent(percent: Int) = controller.setPercent(percent)

    fun setAuto(enabled: Boolean) = controller.setAuto(enabled)
}
