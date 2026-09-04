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

    // Lazily, not WhileSubscribed - this ViewModel already lives for the
    // whole app session (there's no navigation back stack to clear it, just
    // a tab switch that removes DashboardScreen from composition), but
    // snapshotFlow() attaches a brand-new Firestore listener with no cache
    // on every collection, so WhileSubscribed's 5s teardown meant switching
    // tabs and back re-paid the full read cost of the 24h trend query
    // (up to ~288 docs) every time - the single largest driver of this
    // project's Firestore read quota. Lazily starts it once and keeps it
    // live, so only genuinely new/changed documents cost further reads.
    val uiState: StateFlow<DashboardUiState> = combine(
        patientRepository.observePatient(patientId),
        patientRepository.observeLatestReading(patientId),
        patientRepository.observeThresholds(patientId, uid),
        patientRepository.observeRecentReadings(patientId, RECENT_READINGS_LIMIT)
    ) { patient, reading, thresholds, recent ->
        // Trimmed here (against current time, on every emission) rather than
        // relying on the query itself for the "last 24 hours" - see
        // observeRecentReadings's doc comment for why a fixed date cutoff
        // doesn't stay accurate once the listener lives for the whole app
        // session instead of being torn down and recreated periodically.
        val cutoffMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val trend = recent.filter { it.dateMs >= cutoffMs }
        DashboardUiState(patient = patient, reading = reading, thresholds = thresholds, trend = trend, loading = false)
    }.stateIn(viewModelScope, SharingStarted.Lazily, DashboardUiState())

    private companion object {
        // ~288 readings/day at pollGlucose's 5-minute cadence, plus a margin
        // for the occasional missed/delayed cycle - comfortably covers a full
        // rolling 24h window regardless of how long this listener has been
        // attached for.
        const val RECENT_READINGS_LIMIT = 310L
    }
}
