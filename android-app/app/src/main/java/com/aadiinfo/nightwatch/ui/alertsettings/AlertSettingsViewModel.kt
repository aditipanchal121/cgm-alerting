package com.aadiinfo.nightwatch.ui.alertsettings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.AlertType
import com.aadiinfo.nightwatch.domain.model.DEFAULT_ENABLED_ALERT_TYPES
import com.aadiinfo.nightwatch.domain.model.PatientPhysiology
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
    val enabledAlertTypes: Set<AlertType> = DEFAULT_ENABLED_ALERT_TYPES,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

/** ISF and carb ratio (see PatientPhysiology) are loaded from and saved to a
 * separate, patient-level location from everything else in this screen -
 * they're physiological facts shared by the whole family, not personal
 * alerting preferences like the rest of Thresholds. Two independent
 * collectors below, and save() below that does two independent writes. */
class AlertSettingsViewModel(
    private val patientRepository: PatientRepository,
    private val patientId: String,
    private val uid: String,
    private val isOwner: Boolean
) : ViewModel() {

    var uiState by mutableStateOf(AlertSettingsUiState())
        private set

    private var loadedTimezone: String = TimeZone.getDefault().id
    private var loadedUnits: String = "mgdl"
    private var thresholdsLoaded = false
    private var physiologyLoaded = false

    init {
        viewModelScope.launch {
            patientRepository.observeThresholds(patientId, uid).collect { t ->
                loadedTimezone = t.timezone
                loadedUnits = t.units
                thresholdsLoaded = true
                uiState = uiState.copy(
                    lowMgdl = t.lowMgdl.toString(),
                    urgentLowMgdl = t.urgentLowMgdl.toString(),
                    highMgdl = t.highMgdl.toString(),
                    urgentHighMgdl = t.urgentHighMgdl.toString(),
                    iobThreshold = t.iobThreshold.toString(),
                    nightWindowStart = t.nightWindowStart,
                    nightWindowEnd = t.nightWindowEnd,
                    staleMinutes = t.staleMinutes.toString(),
                    enabledAlertTypes = t.enabledAlertTypes,
                    loading = !physiologyLoaded
                )
            }
        }
        viewModelScope.launch {
            patientRepository.observePatientPhysiology(patientId).collect { p ->
                physiologyLoaded = true
                uiState = uiState.copy(
                    insulinSensitivityFactor = p.insulinSensitivityFactor.toString(),
                    carbRatio = p.carbRatio.toString(),
                    loading = !thresholdsLoaded
                )
            }
        }
    }

    fun update(block: (AlertSettingsUiState) -> AlertSettingsUiState) {
        uiState = block(uiState).copy(saved = false, error = null)
    }

    fun setAlertTypeEnabled(type: AlertType, enabled: Boolean) {
        update {
            it.copy(enabledAlertTypes = if (enabled) it.enabledAlertTypes + type else it.enabledAlertTypes - type)
        }
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
                enabledAlertTypes = uiState.enabledAlertTypes
            )
        }.getOrNull()
        val physiology = runCatching {
            PatientPhysiology(
                insulinSensitivityFactor = uiState.insulinSensitivityFactor.toDouble(),
                carbRatio = uiState.carbRatio.toDouble()
            )
        }.getOrNull()

        if (thresholds == null || physiology == null) {
            uiState = uiState.copy(error = "Check that all thresholds are valid numbers")
            return
        }

        uiState = uiState.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                patientRepository.saveThresholds(patientId, uid, thresholds)
                // Only the owner (the patient - see firestore.rules) is
                // allowed to write this; the fields are disabled in the UI
                // for anyone else, so their unchanged values would just be
                // rejected by the rules if written anyway.
                if (isOwner) {
                    patientRepository.savePatientPhysiology(patientId, physiology)
                }
            }
                .onSuccess { uiState = uiState.copy(saving = false, saved = true) }
                .onFailure { uiState = uiState.copy(saving = false, error = it.message) }
        }
    }
}
