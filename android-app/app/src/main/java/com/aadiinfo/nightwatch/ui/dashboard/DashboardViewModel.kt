package com.aadiinfo.nightwatch.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Patient
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TreatmentEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val patient: Patient? = null,
    val reading: GlucoseReading? = null,
    val thresholds: Thresholds = Thresholds(),
    val trend: List<GlucoseReading> = emptyList(),
    val treatments: List<TreatmentEvent> = emptyList(),
    // Null when there aren't enough readings in `trend` to compute a rate.
    val percentTimeAtHighRate: Double? = null,
    val loading: Boolean = true
)

// Dexcom's own published trend-arrow cutoff for a double arrow - see
// alertEngine.ts's compression-low detection, which cites the same figure.
// An external, clinically-recognized threshold rather than one derived from
// this patient's own data, so it means the same thing across people/weeks.
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
        patientRepository.observeRecentReadings(patientId, RECENT_READINGS_LIMIT),
        patientRepository.observeRecentTreatments(patientId, RECENT_TREATMENTS_LIMIT)
    ) { patient, reading, thresholds, recent, treatments ->
        // Trimmed here (against current time, on every emission) rather than
        // relying on the query itself for the "last 24 hours" - see
        // observeRecentReadings's doc comment for why a fixed date cutoff
        // doesn't stay accurate once the listener lives for the whole app
        // session instead of being torn down and recreated periodically.
        val cutoffMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val trend = recent.filter { it.dateMs >= cutoffMs }
        val recentTreatments = treatments.filter { it.mills >= cutoffMs }
        DashboardUiState(
            patient = patient,
            reading = reading,
            thresholds = thresholds,
            trend = trend,
            treatments = recentTreatments,
            percentTimeAtHighRate = percentTimeAtHighRate(trend),
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, DashboardUiState())

    private companion object {
        // ~288 readings/day at pollGlucose's 5-minute cadence, plus a margin
        // for the occasional missed/delayed cycle - comfortably covers a full
        // rolling 24h window regardless of how long this listener has been
        // attached for.
        const val RECENT_READINGS_LIMIT = 310L

        // Boluses/carb corrections happen a handful of times a day - 100 is a
        // generous margin over a full 24h window, self-bounding regardless of
        // listener age (same reasoning as RECENT_READINGS_LIMIT).
        const val RECENT_TREATMENTS_LIMIT = 100L
    }
}
