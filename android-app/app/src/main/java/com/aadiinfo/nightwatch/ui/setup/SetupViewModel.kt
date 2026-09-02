package com.aadiinfo.nightwatch.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import kotlinx.coroutines.launch

enum class SetupStep { CREATE_PATIENT, ENTER_CREDENTIALS }

data class SetupUiState(
    val displayName: String = "",
    val nightscoutUrl: String = "",
    val apiSecret: String = "",
    val patientId: String? = null,
    val step: SetupStep = SetupStep.CREATE_PATIENT,
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class SetupViewModel(
    private val patientRepository: PatientRepository,
    private val uid: String,
    existingPatientId: String? = null,
    initialNightscoutUrl: String = ""
) : ViewModel() {

    var uiState by mutableStateOf(
        if (existingPatientId != null) {
            SetupUiState(
                patientId = existingPatientId,
                nightscoutUrl = initialNightscoutUrl,
                step = SetupStep.ENTER_CREDENTIALS
            )
        } else {
            SetupUiState()
        }
    )
        private set

    fun onDisplayNameChange(value: String) {
        uiState = uiState.copy(displayName = value, error = null)
    }

    fun onUrlChange(value: String) {
        uiState = uiState.copy(nightscoutUrl = value, message = null, error = null)
    }

    fun onSecretChange(value: String) {
        uiState = uiState.copy(apiSecret = value, message = null, error = null)
    }

    fun createPatient() {
        if (uiState.displayName.isBlank()) {
            uiState = uiState.copy(error = "Enter a name for who you're monitoring")
            return
        }
        uiState = uiState.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { patientRepository.createPatient(uid, uiState.displayName) }
                .onSuccess { id ->
                    uiState = uiState.copy(loading = false, patientId = id, step = SetupStep.ENTER_CREDENTIALS)
                }
                .onFailure { uiState = uiState.copy(loading = false, error = it.message) }
        }
    }

    fun verifyAndSave(onDone: () -> Unit) {
        val patientId = uiState.patientId ?: return
        if (uiState.nightscoutUrl.isBlank() || uiState.apiSecret.isBlank()) {
            uiState = uiState.copy(error = "Enter both the Gluroo URL and API secret")
            return
        }
        uiState = uiState.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            patientRepository.verifyGlurooConnection(uiState.nightscoutUrl, uiState.apiSecret).fold(
                onSuccess = { verifyMessage ->
                    runCatching {
                        patientRepository.saveCredentials(patientId, uiState.nightscoutUrl, uiState.apiSecret)
                    }.onSuccess {
                        uiState = uiState.copy(loading = false, message = verifyMessage)
                        onDone()
                    }.onFailure {
                        uiState = uiState.copy(loading = false, error = it.message)
                    }
                },
                onFailure = { err -> uiState = uiState.copy(loading = false, error = err.message) }
            )
        }
    }
}
