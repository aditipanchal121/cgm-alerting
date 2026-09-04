package com.aadiinfo.nightwatch.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.domain.model.Thresholds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val patient: Patient? = null,
    val reading: GlucoseReading? = null,
    val thresholds: Thresholds = Thresholds(),
    val trend: List<GlucoseReading> = emptyList(),
    val loading: Boolean = true
)

class DashboardViewModel(
    patientRepository: PatientRepository,
    patientId: String,
    uid: String
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        patientRepository.observePatient(patientId),
        patientRepository.observeLatestReading(patientId),
        patientRepository.observeThresholds(patientId, uid),
        patientRepository.observeReadingsSince(patientId, System.currentTimeMillis() - 24 * 60 * 60 * 1000)
    ) { patient, reading, thresholds, trend ->
        DashboardUiState(patient = patient, reading = reading, thresholds = thresholds, trend = trend, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())
}
