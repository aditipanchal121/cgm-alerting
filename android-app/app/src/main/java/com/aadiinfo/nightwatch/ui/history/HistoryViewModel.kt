package com.aadiinfo.nightwatch.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.AlertEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(
    private val patientRepository: PatientRepository,
    private val patientId: String
) : ViewModel() {

    val alerts: StateFlow<List<AlertEvent>> = patientRepository.observeAlertHistory(patientId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
