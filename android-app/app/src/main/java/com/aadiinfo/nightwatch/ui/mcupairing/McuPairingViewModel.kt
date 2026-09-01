package com.aadiinfo.nightwatch.ui.mcupairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import kotlinx.coroutines.launch

data class McuPairingUiState(
    val deviceId: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val paired: Boolean = false
)

class McuPairingViewModel(
    private val patientRepository: PatientRepository,
    private val patientId: String
) : ViewModel() {

    var uiState by mutableStateOf(McuPairingUiState())
        private set

    fun onDeviceIdChange(value: String) {
        uiState = uiState.copy(deviceId = value, error = null, paired = false)
    }

    fun pair() {
        if (uiState.deviceId.isBlank()) {
            uiState = uiState.copy(error = "Enter the device ID shown by the ESP32")
            return
        }
        uiState = uiState.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { patientRepository.pairMcuDevice(patientId, uiState.deviceId.trim()) }
                .onSuccess { uiState = uiState.copy(loading = false, paired = true) }
                .onFailure { uiState = uiState.copy(loading = false, error = it.message) }
        }
    }
}
