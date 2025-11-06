package pl.zarajczyk.familyrulesandroid.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.zarajczyk.familyrulesandroid.adapter.ActualDeviceState
import pl.zarajczyk.familyrulesandroid.adapter.DeviceState

/**
 * Manages device state changes and provides reactive updates to the UI
 */
class DeviceStateManager {
    private val _currentState = MutableStateFlow(ActualDeviceState.ACTIVE)
    val currentState: StateFlow<ActualDeviceState> = _currentState.asStateFlow()
    
    fun updateState(newState: ActualDeviceState) {
        _currentState.value = newState
    }
    
    fun getCurrentState(): ActualDeviceState = _currentState.value
}
