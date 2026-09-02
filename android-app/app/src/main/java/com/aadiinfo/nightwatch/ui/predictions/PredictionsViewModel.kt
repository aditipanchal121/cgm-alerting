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
    patientId: String
) : ViewModel() {

    val uiState: StateFlow<PredictionsUiState> = combine(
        patientRepository.observeReadingsSince(patientId, System.currentTimeMillis() - RECENT_WINDOW_MS),
        patientRepository.observeThresholds(patientId)
    ) { readings, thresholds ->
        val outputs = availablePredictors.map { predictor ->
            PredictorOutput(predictor.name, predictor.description, predictor.predict(readings, thresholds))
        }
        PredictionsUiState(
            recentReadings = readings,
            thresholds = thresholds,
            outputs = outputs,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PredictionsUiState())

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
    }
}
