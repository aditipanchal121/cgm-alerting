package com.aadiinfo.nightwatch.ui.predictions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.PredictionResult
import com.aadiinfo.nightwatch.domain.availablePredictors
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PredictorOutput(val predictorName: String, val description: String, val result: PredictionResult)

data class PredictionsUiState(
    val recentReadings: List<GlucoseReading> = emptyList(),
    val thresholds: Thresholds = Thresholds(),
    val outputs: List<PredictorOutput> = emptyList(),
    val loading: Boolean = true
)

/** Runs every registered [com.aadiinfo.nightwatch.domain.GlucosePredictor]
 * against the same recent window of readings so their outputs can be
 * compared side by side - purely for on-device experimentation, this does
 * not feed alerting (the backend's own predictor does that independently). */
class PredictionsViewModel(
    patientRepository: PatientRepository,
    patientId: String,
    uid: String
) : ViewModel() {

    val uiState: StateFlow<PredictionsUiState> = combine(
        patientRepository.observeRecentReadings(patientId, RECENT_READINGS_LIMIT),
        patientRepository.observeThresholds(patientId, uid)
    ) { recent, thresholds ->
        // Trimmed here against current time on every emission, not via the
        // query's own bound - see observeRecentReadings's doc comment; a
        // fixed date cutoff computed once would drift since this listener
        // now lives for the whole app session (SharingStarted.Lazily).
        val cutoffMs = System.currentTimeMillis() - RECENT_WINDOW_MS
        val readings = recent.filter { it.dateMs >= cutoffMs }
        val outputs = availablePredictors.map { predictor ->
            PredictorOutput(predictor.name, predictor.description, predictor.predict(readings, thresholds))
        }
        PredictionsUiState(
            recentReadings = readings,
            thresholds = thresholds,
            outputs = outputs,
            loading = false
        )
    // Lazily, not WhileSubscribed - see DashboardViewModel's comment.
    }.stateIn(viewModelScope, SharingStarted.Lazily, PredictionsUiState())

    private companion object {
        // Matches the backend's own PREDICTED_LOW window (alertEngine.ts calls
        // predictMinutesToThreshold with the same ~6 recent Gluroo entries
        // pollGlucose just fetched, i.e. ~30 min at Gluroo's ~5-min cadence).
        // This used to be 60 minutes here, which meant this tab's linear
        // regression was diluting a recent sharp trend with an extra half
        // hour of older data the backend's alert never saw - the two could
        // legitimately disagree on whether a threshold crossing is projected,
        // not because of a bug in either predictor, but because they were
        // structurally different calculations. Keeping the window matched
        // means only genuine staleness (the notification reflects the trend
        // as of whenever it fired; this tab is always live) explains any
        // remaining disagreement.
        const val RECENT_WINDOW_MS = 30 * 60 * 1000L

        // ~6 readings in 30 min at the usual 5-minute cadence - comfortable
        // buffer over that, self-bounding regardless of listener age.
        const val RECENT_READINGS_LIMIT = 12L
    }
}
