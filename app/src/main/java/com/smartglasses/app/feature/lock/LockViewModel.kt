package com.smartglasses.app.feature.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartglasses.app.data.security.AppLock
import com.smartglasses.app.data.security.UnlockResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LockUiState(val checking: Boolean = false, val result: UnlockResult? = null)

@HiltViewModel
class LockViewModel @Inject constructor(private val lock: AppLock) : ViewModel() {
    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    fun unlock(password: String) {
        if (_state.value.checking || password.isEmpty()) return
        _state.value = _state.value.copy(checking = true)
        viewModelScope.launch {
            _state.value = LockUiState(checking = false, result = lock.unlock(password))
        }
    }

    fun clearResult() {
        if (_state.value.result != null) _state.value = _state.value.copy(result = null)
    }
}
