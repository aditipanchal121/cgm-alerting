package com.aadiinfo.nightwatch.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.AlertEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(
    patientRepository: PatientRepository,
    patientId: String,
    uid: String
) : ViewModel() {

    // Lazily, not WhileSubscribed - see DashboardViewModel's comment. Same
    // mechanism here: a fresh listener on the full alert history every time
    // the History tab is revisited, instead of once per app session.
    val alerts: StateFlow<List<AlertEvent>> = patientRepository.observeAlertHistory(patientId, uid)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
