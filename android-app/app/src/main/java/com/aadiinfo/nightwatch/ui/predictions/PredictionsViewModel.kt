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

data class PredictorOutput(val predictorName: String, val result: PredictionResult)

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
            PredictorOutput(predictor.name, predictor.predict(readings, thresholds))
        }
        PredictionsUiState(
            recentReadings = readings,
            thresholds = thresholds,
            outputs = outputs,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PredictionsUiState())

    private companion object {
        // Matches the ~30 min horizon predictors project toward - enough
        // recent context for a short-term extrapolation without diluting it
        // with data too old to be relevant to "what happens in 30 minutes."
        const val RECENT_WINDOW_MS = 60 * 60 * 1000L
    }
}
