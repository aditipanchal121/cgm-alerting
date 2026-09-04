package com.aadiinfo.nightwatch.ui.alertsettings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.Thresholds
import kotlinx.coroutines.launch
import java.util.TimeZone

data class AlertSettingsUiState(
    val lowMgdl: String = "80",
    val urgentLowMgdl: String = "65",
    val highMgdl: String = "200",
    val urgentHighMgdl: String = "260",
    val iobThreshold: String = "8.0",
    val nightWindowStart: String = "22:00",
    val nightWindowEnd: String = "07:00",
    val staleMinutes: String = "20",
    val insulinSensitivityFactor: String = "40",
    val carbRatio: String = "10",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

class AlertSettingsViewModel(
    private val patientRepository: PatientRepository,
    private val patientId: String,
    private val uid: String
) : ViewModel() {

    var uiState by mutableStateOf(AlertSettingsUiState())
        private set

    private var loadedTimezone: String = TimeZone.getDefault().id
    private var loadedUnits: String = "mgdl"

    init {
        viewModelScope.launch {
            patientRepository.observeThresholds(patientId, uid).collect { t ->
                loadedTimezone = t.timezone
                loadedUnits = t.units
                uiState = uiState.copy(
                    lowMgdl = t.lowMgdl.toString(),
                    urgentLowMgdl = t.urgentLowMgdl.toString(),
                    highMgdl = t.highMgdl.toString(),
                    urgentHighMgdl = t.urgentHighMgdl.toString(),
                    iobThreshold = t.iobThreshold.toString(),
                    nightWindowStart = t.nightWindowStart,
                    nightWindowEnd = t.nightWindowEnd,
                    staleMinutes = t.staleMinutes.toString(),
                    insulinSensitivityFactor = t.insulinSensitivityFactor.toString(),
                    carbRatio = t.carbRatio.toString(),
                    loading = false
                )
            }
        }
    }

    fun update(block: (AlertSettingsUiState) -> AlertSettingsUiState) {
        uiState = block(uiState).copy(saved = false, error = null)
    }

    fun save() {
        val thresholds = runCatching {
            Thresholds(
                units = loadedUnits,
                lowMgdl = uiState.lowMgdl.toInt(),
                urgentLowMgdl = uiState.urgentLowMgdl.toInt(),
                highMgdl = uiState.highMgdl.toInt(),
                urgentHighMgdl = uiState.urgentHighMgdl.toInt(),
                iobThreshold = uiState.iobThreshold.toDouble(),
                nightWindowStart = uiState.nightWindowStart,
                nightWindowEnd = uiState.nightWindowEnd,
                timezone = loadedTimezone,
                staleMinutes = uiState.staleMinutes.toInt(),
                insulinSensitivityFactor = uiState.insulinSensitivityFactor.toDouble(),
                carbRatio = uiState.carbRatio.toDouble()
            )
        }.getOrNull()

        if (thresholds == null) {
            uiState = uiState.copy(error = "Check that all thresholds are valid numbers")
            return
        }

        uiState = uiState.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { patientRepository.saveThresholds(patientId, uid, thresholds) }
                .onSuccess { uiState = uiState.copy(saving = false, saved = true) }
                .onFailure { uiState = uiState.copy(saving = false, error = it.message) }
        }
    }
}
