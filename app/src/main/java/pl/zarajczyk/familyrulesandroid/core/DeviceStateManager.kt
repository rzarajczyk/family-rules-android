package pl.zarajczyk.familyrulesandroid.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState

/**
 * Manages device state changes and provides reactive updates to the UI
 */
class DeviceStateManager {
    private val _currentState = MutableStateFlow(DeviceState.ACTIVE)
    val currentState: StateFlow<DeviceState> = _currentState.asStateFlow()
    
    fun updateState(newState: DeviceState) {
        _currentState.value = newState
    }
    
    fun getCurrentState(): DeviceState = _currentState.value
}
