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
    // Null when there aren't enough readings in `trend` to compute a rate.
    val percentTimeAtHighRate: Double? = null,
    val loading: Boolean = true
)

// Dexcom's published trend-arrow cutoff for a double arrow - see
// alertEngine.ts's compression-low detection.
private const val HIGH_RATE_MGDL_PER_MIN = 3.0

/** Fraction of [readings]' total elapsed time spent moving at or beyond
 * [HIGH_RATE_MGDL_PER_MIN], time-weighted (not just a fraction of sample
 * count) - each consecutive pair's gap counts toward "high" or "normal"
 * based on that pair's own rate, so a long gap between two calm readings
 * isn't weighted the same as a short gap between two fast-moving ones. */
private fun percentTimeAtHighRate(readings: List<GlucoseReading>): Double? {
    if (readings.size < 2) return null
    var highRateMinutes = 0.0
    var totalMinutes = 0.0
    for (i in 1 until readings.size) {
        val dt = (readings[i].dateMs - readings[i - 1].dateMs) / 60_000.0
        if (dt <= 0) continue
        val rate = kotlin.math.abs(readings[i].sgv - readings[i - 1].sgv) / dt
        totalMinutes += dt
        if (rate >= HIGH_RATE_MGDL_PER_MIN) highRateMinutes += dt
    }
    return if (totalMinutes > 0) (highRateMinutes / totalMinutes) * 100.0 else null
}

class DashboardViewModel(
    patientRepository: PatientRepository,
    patientId: String,
    uid: String
) : ViewModel() {

    // Lazily, not WhileSubscribed - a tab switch would otherwise tear down
    // and re-pay the full 24h trend query's read cost every time.
    val uiState: StateFlow<DashboardUiState> = combine(
        patientRepository.observePatient(patientId),
        patientRepository.observeLatestReading(patientId),
        patientRepository.observeThresholds(patientId, uid),
        patientRepository.observeRecentReadings(patientId, RECENT_READINGS_LIMIT)
    ) { patient, reading, thresholds, recent ->
        // Trimmed against current time on every emission, not the query
        // itself, since this listener lives for the whole app session.
        val cutoffMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val trend = recent.filter { it.dateMs >= cutoffMs }
        DashboardUiState(
            patient = patient,
            reading = reading,
            thresholds = thresholds,
            trend = trend,
            percentTimeAtHighRate = percentTimeAtHighRate(trend),
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, DashboardUiState())

    private companion object {
        // ~288 readings/day at 5-minute cadence, plus margin for missed cycles.
        const val RECENT_READINGS_LIMIT = 310L
    }
}
